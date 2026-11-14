package dev.roundmap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;

/**
 * Lightweight underground overlay in vertical 16-block bands.
 * This is a 2D cave-floor map, not a 3D scanner. Only existing client
 * chunks are read; remote/hidden terrain is never requested.
 */
final class RoundMapCaveAtlas {
    private static final int MAGIC = 0x524D4351; // RMCQ
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "RoundMap-cave-save");
        thread.setDaemon(true);
        return thread;
    });
    private static final class Tile {
        final int[] colors = new int[256];
        int stage; // 0 unknown, 1 4x4, 2 full
        Tile() {}
        Tile(int[] pixels, int stage) {
            System.arraycopy(pixels, 0, colors, 0, 256);
            this.stage = stage;
        }
    }
    private static final Map<Long, Tile> TILES = new HashMap<>();
    private static final ArrayDeque<Long> COARSE = new ArrayDeque<>();
    private static final ArrayDeque<Long> FINE = new ArrayDeque<>();
    private static String worldKey = "";
    private static int band = Integer.MIN_VALUE;
    private static int centerX = Integer.MIN_VALUE, centerZ;
    private static Path path;
    private static long active = Long.MIN_VALUE, refreshAt, savedAt;
    private static int activeStep, activeIndex, revision;
    private static boolean dirty;

    static int band() { return band; }
    static int revision() { return revision; }
    static int chunks() { return TILES.size(); }
    static int colorAt(int x, int z) {
        Tile t = TILES.get(((long)(x >> 4) << 32) | ((z >> 4) & 0xFFFFFFFFL));
        return t == null ? 0 : t.colors[((z & 15) << 4) | (x & 15)];
    }
    static int renderColor(int x, int z) {
        int color = colorAt(x, z);
        return color == 0 ? 0xFF263039 : color;
    }

    private static void save() {
        if (path == null || !dirty) return;
        dirty = false;
        Path destination = path;
        Map<Long, Tile> snapshot = new HashMap<>();
        for (Map.Entry<Long, Tile> e : TILES.entrySet()) {
            if (e.getValue().stage > 0)
                snapshot.put(e.getKey(), new Tile(e.getValue().colors, e.getValue().stage));
        }
        IO.submit(() -> {
            try {
                Files.createDirectories(destination.getParent());
                Path temp = destination.resolveSibling(destination.getFileName() + ".tmp");
                try (DataOutputStream out = new DataOutputStream(
                    new GZIPOutputStream(Files.newOutputStream(temp)))) {
                    out.writeInt(MAGIC);
                    out.writeInt(snapshot.size());
                    for (Map.Entry<Long, Tile> entry : snapshot.entrySet()) {
                        out.writeLong(entry.getKey());
                        out.writeByte(entry.getValue().stage);
                        for (int color : entry.getValue().colors) out.writeInt(color);
                    }
                }
                Files.move(temp, destination,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.err.println("[RoundMap] Cave cache save failed: " + e);
            }
        });
    }

    private static void switchBand(Minecraft mc) {
        int newBand = mc.player.getBlockY() >> 4;
        String key = RoundMapWorldIdentity.key(mc);
        if (key.equals(worldKey) && band == newBand) return;
        save();
        worldKey = key;
        band = newBand;
        path = RoundMapWorldIdentity.cavePath(key, newBand);
        TILES.clear();
        COARSE.clear();
        FINE.clear();
        centerX = Integer.MIN_VALUE;
        active = Long.MIN_VALUE;
        savedAt = System.nanoTime();
        revision++;
        RoundMapTextures.invalidate();
        final String targetKey = worldKey;
        final int targetBand = band;
        final Path source = path;
        IO.submit(() -> {
            Map<Long, Tile> loaded = new HashMap<>();
            if (Files.isRegularFile(source)) {
                try (DataInputStream in = new DataInputStream(
                    new GZIPInputStream(Files.newInputStream(source)))) {
                    if (in.readInt() != MAGIC) return;
                    int count = in.readInt();
                    if (count < 0 || count > 30000) return;
                    for (int i = 0; i < count; i++) {
                        long keyOfTile = in.readLong();
                        int stage = in.readUnsignedByte();
                        int[] colors = new int[256];
                        for (int p = 0; p < 256; p++) colors[p] = in.readInt();
                        loaded.put(keyOfTile, new Tile(colors, stage));
                    }
                } catch (IOException e) {
                    System.err.println("[RoundMap] Cave cache load failed: " + e);
                }
            }
            mc.execute(() -> {
                if (targetKey.equals(worldKey) && targetBand == band) {
                    for (Map.Entry<Long, Tile> entry : loaded.entrySet()) {
                        Tile old = TILES.get(entry.getKey());
                        if (old == null || old.stage < entry.getValue().stage)
                            TILES.put(entry.getKey(), entry.getValue());
                    }
                    centerX = Integer.MIN_VALUE;
                    revision++;
                }
            });
        });
    }

    private static int scan(Minecraft mc, int wx, int wz) {
        // Restrict ourselves to nearby underground floors in this vertical
        // band, and reject daylight-exposed surfaces.
        int top = Math.min(mc.level.getMaxY() - 3, (band + 1) * 16 + 4);
        int bottom = Math.max(mc.level.getMinY(), (band - 1) * 16);
        for (int y = top; y >= bottom; y--) {
            BlockPos floor = new BlockPos(wx, y, wz);
            BlockState block = mc.level.getBlockState(floor);
            if (block.isAir()) continue;
            BlockPos head = floor.above();
            if (!mc.level.getBlockState(head).isAir() ||
                !mc.level.getBlockState(head.above()).isAir()) continue;
            if (mc.level.getBrightness(LightLayer.SKY, head) >= 13) continue;
            MapColor map = block.getMapColor(mc.level, floor);
            if (map == MapColor.NONE) continue;
            int color = map.col | 0xFF000000;
            int lamp = mc.level.getBrightness(LightLayer.BLOCK, head);
            int factor = Math.min(255, 125 + lamp * 8);
            int r = Math.min(255, ((color >> 16) & 255) * factor / 255 + lamp * 3);
            int g = Math.min(255, ((color >> 8) & 255) * factor / 255 + lamp * 2);
            int b = Math.min(255, (color & 255) * factor / 255 + lamp);
            return 0xFF000000 | r << 16 | g << 8 | b;
        }
        return 0;
    }

    private static void queue(int cx, int cz) {
        COARSE.clear(); FINE.clear();
        for (int radius = 0; radius <= 6; radius++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    long key = ((long)(cx + dx) << 32) | ((cz + dz) & 0xFFFFFFFFL);
                    Tile tile = TILES.get(key);
                    if (tile == null || tile.stage == 0) COARSE.addLast(key);
                    if (radius <= 3 && (tile == null || tile.stage < 2))
                        FINE.addLast(key);
                }
            }
        }
    }

    static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null || !RoundMapClient.caveScreenActive()) return;
        switchBand(mc);
        int cx = mc.player.getBlockX() >> 4, cz = mc.player.getBlockZ() >> 4;
        long now = System.nanoTime();
        if (cx != centerX || cz != centerZ || now - refreshAt > 4_000_000_000L) {
            centerX = cx; centerZ = cz; refreshAt = now;
            queue(cx, cz);
        }
        long deadline = System.nanoTime() + 2_000_000L;
        int samples = 40;
        while (samples-- > 0 && System.nanoTime() < deadline) {
            if (active == Long.MIN_VALUE) {
                boolean coarse = !COARSE.isEmpty();
                Long next = coarse ? COARSE.pollFirst() : FINE.pollFirst();
                if (next == null) break;
                Tile tile = TILES.get(next);
                if (tile != null && tile.stage >= (coarse ? 1 : 2)) continue;
                int tx = (int)(next >> 32), tz = (int)(long)next;
                if (!mc.level.hasChunkAt(new BlockPos(tx * 16, 0, tz * 16))) continue;
                active = next;
                activeStep = coarse ? 4 : 1;
                activeIndex = 0;
            }
            Tile tile = TILES.computeIfAbsent(active, k -> new Tile());
            int total = activeStep == 4 ? 16 : 256;
            if (activeIndex >= total) {
                tile.stage = Math.max(tile.stage, activeStep == 4 ? 1 : 2);
                active = Long.MIN_VALUE;
                revision++;
                dirty = true;
                continue;
            }
            int lx = activeStep == 4 ? (activeIndex % 4) * 4 + 2 : activeIndex & 15;
            int lz = activeStep == 4 ? (activeIndex / 4) * 4 + 2 : activeIndex >> 4;
            int wx = ((int)(active >> 32)) * 16 + lx;
            int wz = ((int)(long)active) * 16 + lz;
            int sampled = scan(mc, wx, wz);
            if (activeStep == 4) {
                int bx = lx - 2, bz = lz - 2;
                for (int dz = 0; dz < 4; dz++)
                    for (int dx = 0; dx < 4; dx++)
                        tile.colors[((bz + dz) << 4) | bx + dx] = sampled;
            } else tile.colors[(lz << 4) | lx] = sampled;
            activeIndex++;
            if (activeIndex % 16 == 0) {
                revision++; dirty = true;
            }
        }
        if (dirty && now - savedAt > 40_000_000_000L) {
            save();
            savedAt = now;
        }
    }

    static void shutdown() {
        save();
        IO.shutdown();
    }
    private RoundMapCaveAtlas() {}
}

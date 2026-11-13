package dev.roundmap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * World-specific, persistent atlas. Terrain reads run only in the client tick,
 * never from a render call and never in a worker thread.
 * Disk compression runs on one daemon worker without touching Minecraft levels.
 */
final class RoundMapAtlas {
    private static final int MAGIC = 0x524D4132; // RMA2
    private static final int RADIUS_CHUNKS = 10;
    private static final int READS_PER_TICK = 8;
    private static final long SAVE_EVERY_NANOS = 60_000_000_000L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread t = new Thread(task, "RoundMap-atlas-io");
        t.setDaemon(true);
        return t;
    });
    private static final Map<Long, Tile> tiles = new HashMap<>();
    private static final ArrayDeque<Long> coarseQueue = new ArrayDeque<>();
    private static final ArrayDeque<Long> fineQueue = new ArrayDeque<>();
    private static Object world;
    private static String worldKey = "";
    private static Path diskPath;
    private static boolean dirty;
    private static long lastSave, lastRefresh;
    private static int lastChunkX = Integer.MIN_VALUE, lastChunkZ = Integer.MIN_VALUE;
    private static long active = Long.MIN_VALUE;
    private static int activeIndex, activeStep;
    private static int revision;

    private static final class Tile {
        final int[] pixels = new int[256];
        int stage; // 0 unknown, 1 coarse, 2 full detail
        Tile() {}
        Tile(int stage, int[] colors) {
            this.stage = stage;
            System.arraycopy(colors, 0, pixels, 0, Math.min(256, colors.length));
        }
    }

    static int revision() { return revision; }
    static int exploredChunks() { return tiles.size(); }

    private static long key(int cx, int cz) {
        return ((long)cx << 32) | (cz & 0xFFFFFFFFL);
    }

    static int colorAt(int x, int z) {
        Tile t = tiles.get(key(x >> 4, z >> 4));
        return t == null ? 0 : t.pixels[((z & 15) << 4) | (x & 15)];
    }

    private static Path pathFor(Minecraft mc) {
        String server = mc.getCurrentServer() == null ? null : mc.getCurrentServer().ip;
        String id = server == null
            ? "single:" + (mc.getSingleplayerServer() == null ? "local" : mc.getSingleplayerServer().getWorldData().getLevelName())
            : "remote:" + server;
        worldKey = id + "|" + mc.level.dimension().toString();
        String hex = Integer.toHexString(worldKey.hashCode()) +
            "-" + Integer.toHexString(worldKey.length());
        return FabricLoader.getInstance().getConfigDir()
            .resolve("roundmap").resolve("explored").resolve(hex + ".dat.gz");
    }

    private static void switchWorld(Minecraft mc) {
        if (world == mc.level) return;
        saveAsync();
        tiles.clear();
        coarseQueue.clear();
        fineQueue.clear();
        active = Long.MIN_VALUE;
        lastChunkX = Integer.MIN_VALUE;
        lastChunkZ = Integer.MIN_VALUE;
        world = mc.level;
        revision++;
        dirty = false;
        if (mc.level == null) {
            diskPath = null;
            worldKey = "";
            return;
        }
        diskPath = pathFor(mc);
        final Path source = diskPath;
        final String targetWorld = worldKey;
        IO.submit(() -> {
            Map<Long, Tile> loaded = new HashMap<>();
            if (Files.isRegularFile(source)) {
                try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(source)))) {
                    if (in.readInt() != MAGIC) return;
                    int count = in.readInt();
                    if (count < 0 || count > 40000) return;
                    for (int i = 0; i < count; i++) {
                        long k = in.readLong();
                        int stage = in.readUnsignedByte();
                        int[] colors = new int[256];
                        for (int p = 0; p < 256; p++) colors[p] = in.readInt();
                        loaded.put(k, new Tile(stage, colors));
                    }
                } catch (IOException e) {
                    System.err.println("[RoundMap] Unable to load explored terrain: " + e);
                }
            }
            mc.execute(() -> {
                if (targetWorld.equals(worldKey) && world == mc.level) {
                    for (Map.Entry<Long, Tile> entry : loaded.entrySet()) {
                        Tile previous = tiles.get(entry.getKey());
                        if (previous == null || previous.stage < entry.getValue().stage)
                            tiles.put(entry.getKey(), entry.getValue());
                    }
                    revision++;
                    lastChunkX = Integer.MIN_VALUE;
                }
            });
        });
    }

    static void tick(Minecraft mc) {
        switchWorld(mc);
        if (mc.level == null || mc.player == null) return;
        int cx = mc.player.getBlockX() >> 4, cz = mc.player.getBlockZ() >> 4;
        long now = System.nanoTime();
        if (cx != lastChunkX || cz != lastChunkZ || now - lastRefresh > 10_000_000_000L) {
            rebuildQueues(cx, cz);
            lastRefresh = now;
            lastChunkX = cx;
            lastChunkZ = cz;
        }
        int budget = READS_PER_TICK;
        while (budget > 0) {
            if (active == Long.MIN_VALUE && !nextTile(mc)) break;
            int tileX = (int)(active >> 32), tileZ = (int)active;
            Tile tile = tiles.computeIfAbsent(active, k -> new Tile());
            if (activeIndex >= (activeStep == 4 ? 16 : 256)) {
                tile.stage = Math.max(tile.stage, activeStep == 4 ? 1 : 2);
                active = Long.MIN_VALUE;
                revision++;
                dirty = true;
                continue;
            }
            int localX, localZ;
            if (activeStep == 4) {
                localX = (activeIndex % 4) * 4 + 2;
                localZ = (activeIndex / 4) * 4 + 2;
            } else {
                localX = activeIndex & 15;
                localZ = activeIndex >> 4;
            }
            int x = tileX * 16 + localX, z = tileZ * 16 + localZ;
            if (!mc.level.hasChunkAt(new BlockPos(x, 0, z))) {
                active = Long.MIN_VALUE;
                continue;
            }
            BlockPos pos = new BlockPos(x, 0, z);
            BlockPos ground = mc.level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos).below();
            BlockState state = mc.level.getBlockState(ground);
            MapColor mapColor = state.getMapColor(mc.level, ground);
            int color = mapColor == MapColor.NONE ? 0xFF545C5C : mapColor.col | 0xFF000000;
            int offset = Math.max(-35, Math.min(35, (ground.getY() - 70) / 3));
            int r = Math.min(255, Math.max(0, ((color >> 16) & 255) + offset));
            int g = Math.min(255, Math.max(0, ((color >> 8) & 255) + offset));
            int b = Math.min(255, Math.max(0, (color & 255) + offset));
            color = 0xFF000000 | (r << 16) | (g << 8) | b;
            if (activeStep == 4) {
                int baseX = localX - 2, baseZ = localZ - 2;
                for (int dz = 0; dz < 4; dz++)
                    for (int dx = 0; dx < 4; dx++)
                        tile.pixels[((baseZ + dz) << 4) | baseX + dx] = color;
            } else tile.pixels[(localZ << 4) | localX] = color;
            activeIndex++;
            budget--;
            dirty = true;
            if (activeIndex % 16 == 0) revision++;
        }
        if (dirty && now - lastSave > SAVE_EVERY_NANOS) {
            saveAsync();
            lastSave = now;
        }
    }

    private static boolean nextTile(Minecraft mc) {
        for (int i = 0; i < 600; i++) {
            boolean coarse = !coarseQueue.isEmpty();
            Long next = coarse ? coarseQueue.pollFirst() : fineQueue.pollFirst();
            if (next == null) return false;
            Tile t = tiles.get(next);
            if (t != null && t.stage >= (coarse ? 1 : 2)) continue;
            int tx = (int)(next >> 32), tz = (int)(long)next;
            if (!mc.level.hasChunkAt(new BlockPos(tx * 16, 0, tz * 16))) continue;
            active = next;
            activeIndex = 0;
            activeStep = coarse ? 4 : 1;
            return true;
        }
        return false;
    }

    private static void rebuildQueues(int centerX, int centerZ) {
        coarseQueue.clear();
        fineQueue.clear();
        active = Long.MIN_VALUE;
        // Rings ensure the close surroundings appear before remote terrain.
        for (int radius = 0; radius <= RADIUS_CHUNKS; radius++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    long k = key(centerX + dx, centerZ + dz);
                    Tile tile = tiles.get(k);
                    if (tile == null || tile.stage == 0) coarseQueue.addLast(k);
                    // Refine nearby tiles after the coarse outer ring is available.
                    if (radius <= 4 && (tile == null || tile.stage < 2))
                        fineQueue.addLast(k);
                }
            }
        }
    }

    private static void saveAsync() {
        if (diskPath == null || !dirty) return;
        dirty = false;
        Path path = diskPath;
        Map<Long, Tile> snapshot = new HashMap<>();
        for (Map.Entry<Long, Tile> e : tiles.entrySet()) {
            if (e.getValue().stage > 0) snapshot.put(e.getKey(), new Tile(e.getValue().stage, e.getValue().pixels));
        }
        IO.submit(() -> {
            try {
                Files.createDirectories(path.getParent());
                Path temp = path.resolveSibling(path.getFileName() + ".tmp");
                try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(temp)))) {
                    out.writeInt(MAGIC);
                    out.writeInt(snapshot.size());
                    for (Map.Entry<Long, Tile> e : snapshot.entrySet()) {
                        out.writeLong(e.getKey());
                        out.writeByte(e.getValue().stage);
                        for (int pixel : e.getValue().pixels) out.writeInt(pixel);
                    }
                }
                Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.err.println("[RoundMap] Unable to save explored terrain: " + e);
            }
        });
    }

    private RoundMapAtlas() {}
}

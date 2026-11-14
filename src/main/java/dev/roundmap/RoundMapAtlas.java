package dev.roundmap;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import net.minecraft.world.level.block.Blocks;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

/**
 * World-specific, persistent atlas. Terrain reads run only in the client tick,
 * never from a render call and never in a worker thread.
 * Disk compression runs on one daemon worker without touching Minecraft levels.
 */
final class RoundMapAtlas {
    private static final int MAGIC = 0x524D4135; // RMA5: color, height and light
    private static final int OLD_MAGIC = 0x524D4134; // RMA4: no light
    private static final int PREVIEW_MAGIC = 0x524D4133; // RMA3: 2 stages
    private static final int LEGACY_MAGIC = 0x524D4132; // RMA2: no height
    // Survey only chunks actually within the player's configured view distance.
    private static final int MAX_VISIBLE_RADIUS = 12;
    static int readsPerTick = 96;
    static boolean detailed = true;
    static boolean contours = true;
    static boolean relief = true;
    static boolean lighting = true;
    static int timeMode = 0; // 0 world time, 1 forced day, 2 forced night
    private static boolean nightActive;
    private static final long SAVE_EVERY_NANOS = 60_000_000_000L;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(task -> {
        Thread t = new Thread(task, "RoundMap-atlas-io");
        t.setDaemon(true);
        return t;
    });
    private static final Map<Long, Tile> tiles = new HashMap<>();
    private static final ArrayDeque<Long> nearbyCoarse = new ArrayDeque<>();
    private static final ArrayDeque<Long> nearbyMedium = new ArrayDeque<>();
    private static final ArrayDeque<Long> coarseQueue = new ArrayDeque<>();
    private static final ArrayDeque<Long> nearbyFine = new ArrayDeque<>();
    private static final ArrayDeque<Long> mediumQueue = new ArrayDeque<>();
    private static final ArrayDeque<Long> fineQueue = new ArrayDeque<>();
    private static Object world;
    private static String worldKey = "";
    private static Path diskPath;
    private static boolean dirty;
    private static long lastSave, lastRefresh;
    private static int lastChunkX = Integer.MIN_VALUE, lastChunkZ = Integer.MIN_VALUE;
    private static int lastViewRadius = -1;
    private static long active = Long.MIN_VALUE;
    private static int activeIndex, activeStep;
    private static int revision;

    private static final class Tile {
        final int[] pixels = new int[256];
        final short[] heights = new short[256];
        final byte[] lights = new byte[256];
        int stage; // 0 unknown, 1 preview, 2 medium 4x4, 3 full 16x16
        Tile() { Arrays.fill(heights, Short.MIN_VALUE); }
        Tile(int stage, int[] colors, short[] h, byte[] light) {
            this();
            this.stage = stage;
            System.arraycopy(colors, 0, pixels, 0, Math.min(256, colors.length));
            if (h != null) System.arraycopy(h, 0, heights, 0, Math.min(256, h.length));
            if (light != null) System.arraycopy(light, 0, lights, 0, Math.min(256, light.length));
        }
    }

    static void forceRefresh() { lastChunkX = Integer.MIN_VALUE; }
    static void onResourcePackReload() {
        // Preserve explored tiles. Force re-refinement when they become
        // visible, rather than erasing saved exploration on every F3+T.
        for (Tile t : tiles.values()) if (t.stage == 3) t.stage = 2;
        nearbyCoarse.clear();
        nearbyMedium.clear();
        coarseQueue.clear();
        nearbyFine.clear();
        mediumQueue.clear();
        fineQueue.clear();
        active = Long.MIN_VALUE;
        forceRefresh();
        revision++;
        dirty = true;
        RoundMapTextures.invalidate();
    }
    static int revision() { return revision; }
    static int exploredChunks() {
        int explored = 0;
        for (Tile tile : tiles.values()) if (tile.stage > 0) explored++;
        return explored;
    }
    static int detailedChunks() {
        int count = 0;
        for (Tile tile : tiles.values()) if (tile.stage >= 3) count++;
        return count;
    }

    /**
     * Prior alpha builds sometimes cached a near-pure-red model fallback
     * in entire chunks (the red sea seen when zooming out). Vanilla map
     * colors and genuine red terrain have substantially more green/blue.
     * Reject only this very narrow error signature, not ordinary reds.
     */
    private static boolean invalidRed(int color) {
        int r = (color >>> 16) & 255;
        int g = (color >>> 8) & 255;
        int b = color & 255;
        return r >= 175 && g <= 24 && b <= 24 && Math.abs(g - b) <= 10;
    }

    // Alpha.16 saved this artificial gray (#81868D) to the atlas.
    // The altitude brightness offset shifts all three channels equally,
    // so check their exact spacing rather than natural grayscale colors.
    private static boolean legacyGray(int color) {
        int r = (color >>> 16) & 255;
        int g = (color >>> 8) & 255;
        int b = color & 255;
        return r >= 103 && r <= 154 && g == r + 5 && b == g + 7;
    }

    // Only a known old *gray sentinel* denotes missing terrain.
    // Alpha.15 mistakenly treated bright-red real/custom block colors as
    // absent, turning entire explored worlds into fog.
    private static boolean invalidFallback(int color) {
        int r = (color >>> 16) & 255;
        int g = (color >>> 8) & 255;
        int b = color & 255;
        return r >= 59 && r <= 109 && g - r == 8 && b == g;
    }

    private static long key(int cx, int cz) {
        return ((long)cx << 32) | (cz & 0xFFFFFFFFL);
    }

    static int colorAt(int x, int z) {
        Tile t = tiles.get(key(x >> 4, z >> 4));
        if (t == null) return 0;
        int color = t.pixels[((z & 15) << 4) | (x & 15)];
        return invalidFallback(color) ? 0 : color;
    }

    static int lightAt(int x, int z) {
        Tile tile = tiles.get(key(x >> 4, z >> 4));
        if (tile == null) return 0;
        return tile.lights[((z & 15) << 4) | (x & 15)] & 255;
    }

    static int heightAt(int x, int z) {
        Tile t = tiles.get(key(x >> 4, z >> 4));
        if (t == null) return Short.MIN_VALUE;
        int index = ((z & 15) << 4) | (x & 15);
        return invalidFallback(t.pixels[index]) ? Short.MIN_VALUE : t.heights[index];
    }

    // Directional hillshade with two neighbouring height samples; computed from
    // cached tiles, never from live blocks or chunks during rendering.
    private static int mix(int a, int b, int percent) {
        int inv = 100 - percent;
        int r = (((a >> 16) & 255) * inv + ((b >> 16) & 255) * percent) / 100;
        int g = (((a >> 8) & 255) * inv + ((b >> 8) & 255) * percent) / 100;
        int blue = ((a & 255) * inv + (b & 255) * percent) / 100;
        return 0xFF000000 | r << 16 | g << 8 | blue;
    }

    static int shadedColorAt(int x, int z) {
        int color = colorAt(x, z);
        if (color == 0) return 0;
        int h = heightAt(x, z);
        if (h == Short.MIN_VALUE) return color;
        int west = heightAt(x - 1, z);
        int north = heightAt(x, z - 1);
        int east = heightAt(x + 1, z);
        int south = heightAt(x, z + 1);
        if (west == Short.MIN_VALUE) west = h;
        if (north == Short.MIN_VALUE) north = h;
        if (east == Short.MIN_VALUE) east = h;
        if (south == Short.MIN_VALUE) south = h;
        // Four-sided height gradients keep fine cliffs, ridges and river
        // banks visible after zooming in. Uses the existing height atlas,
        // never reads Minecraft blocks from the render loop.
        int directional = (west - east) * 4 + (north - south) * 3;
        int ledge = (h - west) * 3 + (h - north) * 2;
        int shade = relief ? Math.max(-70, Math.min(70,
            directional + ledge)) : 0;
        if (contours && (Math.floorDiv(h, 10) != Math.floorDiv(west, 10) ||
                         Math.floorDiv(h, 10) != Math.floorDiv(north, 10) ||
                         Math.floorDiv(h, 10) != Math.floorDiv(east, 10) ||
                         Math.floorDiv(h, 10) != Math.floorDiv(south, 10))) {
            shade = Math.max(-80, shade - 19);
        }
        int red = Math.max(0, Math.min(255, ((color >> 16) & 255) + shade));
        int green = Math.max(0, Math.min(255, ((color >> 8) & 255) + shade));
        int blue = Math.max(0, Math.min(255, (color & 255) + shade));
        if (lighting && nightActive) {
            int blockLight = Math.min(15, lightAt(x, z));
            double brightness = Math.min(1.0, .28 + blockLight * .054);
            red = (int)Math.min(255, red * brightness + blockLight * 1.65);
            green = (int)Math.min(255, green * brightness + blockLight * 1.0);
            blue = (int)Math.min(255, blue * brightness + blockLight * .35);
        }
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private static void switchWorld(Minecraft mc) {
        String currentWorldKey = RoundMapWorldIdentity.key(mc);
        if (world == mc.level && worldKey.equals(currentWorldKey)) return;
        saveAsync();
        tiles.clear();
        nearbyCoarse.clear();
        nearbyMedium.clear();
        coarseQueue.clear();
        nearbyFine.clear();
        mediumQueue.clear();
        fineQueue.clear();
        active = Long.MIN_VALUE;
        lastViewRadius = -1;
        lastChunkX = Integer.MIN_VALUE;
        lastChunkZ = Integer.MIN_VALUE;
        world = mc.level;
        revision++;
        dirty = false;
        lastSave = System.nanoTime();
        if (mc.level == null) {
            diskPath = null;
            worldKey = "";
            return;
        }
        worldKey = currentWorldKey;
        diskPath = RoundMapWorldIdentity.atlasPath(worldKey);
        RoundMapTextures.invalidate();
        final Path source = diskPath;
        final String targetWorld = worldKey;
        IO.submit(() -> {
            Map<Long, Tile> loaded = new HashMap<>();
            if (Files.isRegularFile(source)) {
                try (DataInputStream in = new DataInputStream(new GZIPInputStream(Files.newInputStream(source)))) {
                    int version = in.readInt();
                    if (version != MAGIC && version != OLD_MAGIC
                        && version != PREVIEW_MAGIC && version != LEGACY_MAGIC) return;
                    int count = in.readInt();
                    if (count < 0 || count > 40000) return;
                    for (int i = 0; i < count; i++) {
                        long k = in.readLong();
                        int stage = in.readUnsignedByte();
                        int[] colors = new int[256];
                        for (int p = 0; p < 256; p++) colors[p] = in.readInt();
                        short[] heights = null;
                        if (version == MAGIC || version == OLD_MAGIC
                            || version == PREVIEW_MAGIC) {
                            heights = new short[256];
                            for (int p = 0; p < 256; p++) heights[p] = in.readShort();
                        }
                        byte[] lights = new byte[256];
                        if (version == MAGIC) in.readFully(lights);
                        if (version == OLD_MAGIC && stage == 3)
                            stage = 2; // Revisit old tiles to capture light levels.
                        else if (version == PREVIEW_MAGIC)
                            stage = stage == 1 ? 2 : stage == 2 ? 2 : stage;
                        else if (version == LEGACY_MAGIC)
                            stage = stage > 0 ? 2 : 0;
                        boolean invalid = false;
                        int legacyGrayPixels = 0, oldRedPixels = 0;
                        for (int p = 0; p < 256; p++) {
                            if (invalidFallback(colors[p])) {
                                colors[p] = 0;
                                if (heights != null) heights[p] = Short.MIN_VALUE;
                                invalid = true;
                            } else {
                                if (legacyGray(colors[p])) legacyGrayPixels++;
                                if (invalidRed(colors[p])) oldRedPixels++;
                            }
                        }
                        // Alpha.16 overwrote the actual block texture with
                        // flat gray. Keep the old explored data visible while
                        // the player is away, but schedule a full 16x16
                        // re-sample when the chunk returns to view.
                        if (legacyGrayPixels >= 64 || oldRedPixels >= 192)
                            stage = Math.min(stage, 2);
                        // Alpha.15 may have saved zeros in a fully explored
                        // tile. Re-scan those rather than losing the chunk.
                        if (invalid) stage = 0;
                        loaded.put(k, new Tile(stage, colors, heights, lights));
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
        long dayTicks = Math.floorMod(mc.level.getDayTime(), 24000L);
        boolean night = timeMode == 2 || (timeMode == 0
            && dayTicks >= 12500L && dayTicks <= 23000L);
        if (night != nightActive) {
            nightActive = night;
            RoundMapTextures.invalidate();
        }
        int cx = mc.player.getBlockX() >> 4, cz = mc.player.getBlockZ() >> 4;
        // The client may know about distant chunks, but exploring them before
        // they enter the player's visible/rendered range reveals too much map.
        int viewRadius = Math.max(2, Math.min(MAX_VISIBLE_RADIUS,
            mc.options.renderDistance().get() - 1));
        long now = System.nanoTime();
        if (cx != lastChunkX || cz != lastChunkZ || viewRadius != lastViewRadius
            || now - lastRefresh > 1_000_000_000L) {
            rebuildQueues(cx, cz, viewRadius);
            lastRefresh = now;
            lastChunkX = cx;
            lastChunkZ = cz;
            lastViewRadius = viewRadius;
        }
        // Main-thread chunk reads get a fixed time slice to protect FPS.
        long deadline = System.nanoTime() + 4_000_000L;
        int budget = Math.max(16, readsPerTick);
        boolean first = true;
        while (budget > 0 && (first || System.nanoTime() < deadline)) {
            first = false;
            if (active == Long.MIN_VALUE && !nextTile(mc)) break;
            int tileX = (int)(active >> 32), tileZ = (int)active;
            Tile tile = tiles.computeIfAbsent(active, k -> new Tile());
            if (activeIndex >= (activeStep == 16 ? 1 : activeStep == 4 ? 16 : 256)) {
                tile.stage = Math.max(tile.stage, activeStep == 16 ? 1 : activeStep == 4 ? 2 : 3);
                active = Long.MIN_VALUE;
                revision++;
                dirty = true;
                continue;
            }
            int localX, localZ;
            if (activeStep == 16) {
                localX = 8;
                localZ = 8;
            } else if (activeStep == 4) {
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
            // An unloaded/placeholder chunk can report a height below the
            // actual world. Do not turn void air into a dark gray map tile.
            if (state.isAir()) {
                active = Long.MIN_VALUE;
                continue;
            }
            MapColor mapColor = state.getMapColor(mc.level, ground);
            if (mapColor == MapColor.NONE) {
                // A resource-pack model may define the full appearance of
                // a block even if vanilla MapColor is NONE. Try its top face.
                // Never decode resource-pack textures for the fast one-pixel
                // chunk preview. Medium/full passes will inspect the model.
                int modelColor = activeStep == 16 ? 0
                    : RoundMapBlockPalette.color(mc, state, ground, 0);
                boolean hasTexture = modelColor != 0;
                // Ignore thin plants or other non-map-color blocks; use the
                // underlying solid surface where possible.
                for (int depth = 1; depth <= 6 && mapColor == MapColor.NONE && !hasTexture; depth++) {
                    BlockPos lower = ground.below(depth);
                    BlockState candidate = mc.level.getBlockState(lower);
                    if (!candidate.isAir()) {
                        MapColor sample = candidate.getMapColor(mc.level, lower);
                        if (sample != MapColor.NONE) {
                            ground = lower;
                            state = candidate;
                            mapColor = sample;
                        }
                    }
                }
                if (mapColor == MapColor.NONE && !hasTexture) {
                    active = Long.MIN_VALUE;
                    continue;
                }
            }
            // Preview avoids costly texture lookups; later passes use
            // active resource-pack top-face models for full detail.
            int color = activeStep == 16 && mapColor != MapColor.NONE
                ? mapColor.col | 0xFF000000
                : RoundMapBlockPalette.color(mc, state, ground,
                    mapColor == MapColor.NONE ? 0 : mapColor.col | 0xFF000000);
            // Never paint an entire custom server floor uniform gray.
            // Some server packs use a broken bright-red top-face sprite.
            // First try other model faces (handled in BlockPalette). If
            // they are also unavailable, use an actual lower block texture.
            if (invalidRed(color)) {
                int replacement = 0;
                for (int depth = 1; depth <= 7; depth++) {
                    BlockPos lower = ground.below(depth);
                    if (lower.getY() < mc.level.getMinY()) break;
                    BlockState below = mc.level.getBlockState(lower);
                    if (below.isAir()) continue;
                    MapColor belowMap = below.getMapColor(mc.level, lower);
                    int belowColor = RoundMapBlockPalette.color(mc, below, lower,
                        belowMap == MapColor.NONE ? 0 :
                        (belowMap.col | 0xFF000000));
                    if (belowColor != 0 && !invalidRed(belowColor)) {
                        replacement = belowColor;
                        break;
                    }
                }
                if (replacement == 0 && mapColor != MapColor.NONE &&
                    !invalidRed(mapColor.col))
                    replacement = mapColor.col | 0xFF000000;
                // A biome-derived color is a last-resort approximation,
                // not a fake stored "mapped block". It is deliberately
                // nonuniform so missing textures cannot produce a flat sea.
                if (replacement == 0) {
                    String biomeName = mc.level.getBiome(ground).unwrapKey()
                        .map(k -> k.identifier().getPath()).orElse("");
                    int noise = (x * 73428767 ^ z * 912931) >>> 29;
                    int base = biomeName.contains("desert") ||
                        biomeName.contains("badlands") ? 0xFFAF956A :
                        biomeName.contains("snow") || biomeName.contains("frozen")
                            ? 0xFFB8C6CD : 0xFF627B65;
                    replacement = 0xFF000000 |
                        (Math.min(255, ((base >>> 16) & 255) + noise * 2) << 16) |
                        (Math.min(255, ((base >>> 8) & 255) + noise * 2) << 8) |
                        Math.min(255, (base & 255) + noise * 2);
                }
                color = replacement;
            }
            // A lightweight biome-aware material palette. Capture only on the
            // client tick; no live biome queries happen while drawing the map.
            if (state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.MOSS_BLOCK)) {
                String biome = mc.level.getBiome(ground).unwrapKey()
                    .map(k -> k.identifier().getPath()).orElse("");
                int tint = 0xFF679F3D;
                if (biome.contains("jungle")) tint = 0xFF3A963E;
                else if (biome.contains("swamp") || biome.contains("mangrove")) tint = 0xFF597C48;
                else if (biome.contains("savanna") || biome.contains("badlands")) tint = 0xFFB2AD53;
                else if (biome.contains("taiga") || biome.contains("old_growth")) tint = 0xFF648D63;
                else if (biome.contains("cherry")) tint = 0xFF93B963;
                else if (biome.contains("snow") || biome.contains("frozen")) tint = 0xFF9EBAA0;
                color = mix(color, tint, 55);
            } else if (state.is(Blocks.CHERRY_LEAVES)) color = 0xFFE4A3B9;
            else if (state.is(Blocks.SPRUCE_LEAVES)) color = 0xFF3A6950;
            else if (state.is(Blocks.BIRCH_LEAVES)) color = 0xFF7FB34E;
            else if (state.is(Blocks.OAK_LEAVES) || state.is(Blocks.JUNGLE_LEAVES)
                     || state.is(Blocks.DARK_OAK_LEAVES)) color = 0xFF397B3C;
            // Keep water blue and give shallow coasts a lighter shade.
            if (state.is(Blocks.WATER)) {
                int bed = mc.level.getHeightmapPos(Heightmap.Types.OCEAN_FLOOR, pos).getY();
                int depth = Math.max(0, ground.getY() - bed);
                color = depth < 4 ? 0xFF3D91CE : depth < 12 ? 0xFF2168BA : 0xFF16458E;
            }
            int offset = Math.max(-25, Math.min(25, (ground.getY() - 70) / 5));
            int r = Math.min(255, Math.max(0, ((color >> 16) & 255) + offset));
            int g = Math.min(255, Math.max(0, ((color >> 8) & 255) + offset));
            int b = Math.min(255, Math.max(0, (color & 255) + offset));
            color = 0xFF000000 | (r << 16) | (g << 8) | b;
            short altitude = (short)Math.max(Short.MIN_VALUE + 1,
                Math.min(Short.MAX_VALUE, ground.getY()));
            byte light = (byte)Math.max(0, Math.min(15,
                mc.level.getBrightness(LightLayer.BLOCK, ground.above())));
            if (activeStep == 16) {
                Arrays.fill(tile.pixels, color);
                Arrays.fill(tile.heights, altitude);
                Arrays.fill(tile.lights, light);
            } else if (activeStep == 4) {
                int baseX = localX - 2, baseZ = localZ - 2;
                for (int dz = 0; dz < 4; dz++)
                    for (int dx = 0; dx < 4; dx++) {
                        int cell = ((baseZ + dz) << 4) | baseX + dx;
                        tile.pixels[cell] = color;
                        tile.heights[cell] = altitude;
                        tile.lights[cell] = light;
                    }
            } else {
                int cell = (localZ << 4) | localX;
                tile.pixels[cell] = color;
                tile.heights[cell] = altitude;
                tile.lights[cell] = light;
            }
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
        for (int i = 0; i < 1800; i++) {
            int stage;
            Long next;
            // Prioritize immediate previews near the player, then quickly
            // sketch the entire visible world before refining every pixel.
            if (!nearbyCoarse.isEmpty()) {
                stage = 1; next = nearbyCoarse.pollFirst();
            } else if (!nearbyMedium.isEmpty()) {
                stage = 2; next = nearbyMedium.pollFirst();
            } else if (!coarseQueue.isEmpty()) {
                stage = 1; next = coarseQueue.pollFirst();
            } else if (!nearbyFine.isEmpty()) {
                stage = 3; next = nearbyFine.pollFirst();
            } else if (!mediumQueue.isEmpty()) {
                stage = 2; next = mediumQueue.pollFirst();
            } else {
                stage = 3; next = fineQueue.pollFirst();
            }
            if (next == null) return false;
            Tile t = tiles.get(next);
            if (t != null && t.stage >= stage) continue;
            int tx = (int)(next >> 32), tz = (int)(long)next;
            // No disk load or world generation is triggered by hasChunkAt.
            if (!mc.level.hasChunkAt(new BlockPos(tx * 16, 0, tz * 16))) continue;
            active = next;
            activeIndex = 0;
            activeStep = stage == 1 ? 16 : stage == 2 ? 4 : 1;
            return true;
        }
        return false;
    }

    private static void rebuildQueues(int centerX, int centerZ, int viewRadius) {
        nearbyCoarse.clear();
        nearbyMedium.clear();
        coarseQueue.clear();
        nearbyFine.clear();
        mediumQueue.clear();
        fineQueue.clear();
        // Don't interrupt the active chunk when the camera moves or a new
        // batch of loaded chunks arrives; that caused never-ending repeats.
        for (int radius = 0; radius <= viewRadius; radius++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    int seed = (centerX + dx) * 73428767 ^ (centerZ + dz) * 912931;
                    seed ^= seed >>> 13;
                    double outer = viewRadius - 1.0 + ((seed & 7) / 7.0) * 1.5;
                    if (dx * dx + dz * dz > outer * outer) continue;
                    long k = key(centerX + dx, centerZ + dz);
                    Tile tile = tiles.get(k);
                    int stage = tile == null ? 0 : tile.stage;
                    if (stage == 0)
                        (radius <= 2 ? nearbyCoarse : coarseQueue).addLast(k);
                    if (detailed && stage < 2)
                        (radius <= 1 ? nearbyMedium : mediumQueue).addLast(k);
                    if (detailed && stage < 3)
                        (radius <= 2 ? nearbyFine : fineQueue).addLast(k);
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
            if (e.getValue().stage > 0) snapshot.put(e.getKey(),
                new Tile(e.getValue().stage, e.getValue().pixels,
                    e.getValue().heights, e.getValue().lights));
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
                        for (short height : e.getValue().heights) out.writeShort(height);
                        out.write(e.getValue().lights);
                    }
                }
                Files.move(temp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                System.err.println("[RoundMap] Unable to save explored terrain: " + e);
            }
        });
    }

    static void shutdown() {
        saveAsync();
        IO.shutdown();
        try {
            if (!IO.awaitTermination(3, TimeUnit.SECONDS)) IO.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            IO.shutdownNow();
        }
    }

    private RoundMapAtlas() {}
}

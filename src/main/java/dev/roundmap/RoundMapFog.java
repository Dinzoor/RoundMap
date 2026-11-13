package dev.roundmap;

/**
 * Soft, world-anchored fog for explored terrain.
 * Approximation of rolling fog banks using the explored atlas, without
 * sampling Minecraft blocks or performing GPU multi-pass ray marching.
 */
final class RoundMapFog {
    private static int hash(int x, int z) {
        int h = x * 0x1F123BB5 ^ z * 0x5F356495;
        h ^= h >>> 16;
        h *= 0x7FEB352D;
        h ^= h >>> 15;
        h *= 0x846CA68B;
        return h ^ (h >>> 16);
    }

    private static double cloud(int x, int z) {
        int ix = x >> 5, iz = z >> 5;
        double fx = (x & 31) / 32.0, fz = (z & 31) / 32.0;
        fx = fx * fx * (3.0 - 2.0 * fx);
        fz = fz * fz * (3.0 - 2.0 * fz);
        double a = (hash(ix, iz) & 255) / 255.0;
        double b = (hash(ix + 1, iz) & 255) / 255.0;
        double c = (hash(ix, iz + 1) & 255) / 255.0;
        double d = (hash(ix + 1, iz + 1) & 255) / 255.0;
        return (a + (b - a) * fx) * (1.0 - fz)
             + (c + (d - c) * fx) * fz;
    }

    private static int lerp(int terrain, int fog, double amount) {
        int alpha = Math.max(0, Math.min(255, (int)Math.round(amount * 255)));
        int inverse = 255 - alpha;
        int red = (((terrain >>> 16) & 255) * inverse + ((fog >>> 16) & 255) * alpha) / 255;
        int green = (((terrain >>> 8) & 255) * inverse + ((fog >>> 8) & 255) * alpha) / 255;
        int blue = ((terrain & 255) * inverse + (fog & 255) * alpha) / 255;
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    /**
     * The mask is formed from nearby explored pixels. A broad, noisy rim
     * blends fully explored land into undiscovered fog without sharp chunk
     * squares. All atlas reads are cached memory lookups.
     */
    static int color(int worldX, int worldZ) {
        int terrain = RoundMapAtlas.shadedColorAt(worldX, worldZ);
        int base = RoundMapAtlas.colorAt(worldX, worldZ);
        int radius = 7;
        int west = RoundMapAtlas.colorAt(worldX - radius, worldZ);
        int east = RoundMapAtlas.colorAt(worldX + radius, worldZ);
        int north = RoundMapAtlas.colorAt(worldX, worldZ - radius);
        int south = RoundMapAtlas.colorAt(worldX, worldZ + radius);
        int known = (base != 0 ? 2 : 0) + (west != 0 ? 1 : 0)
            + (east != 0 ? 1 : 0) + (north != 0 ? 1 : 0) + (south != 0 ? 1 : 0);
        if (known == 6) return terrain; // Common fully explored case.
        double variation = cloud(worldX, worldZ);
        int brightness = (int)Math.round((variation - .5) * 26);
        int fog;
        if (RoundMapClient.brightFog) {
            int v = Math.max(224, Math.min(254, 243 + brightness));
            fog = 0xFF000000 | v << 16 | v << 8 | Math.min(255, v + 3);
        } else {
            int v = Math.max(20, Math.min(65, 42 + brightness));
            fog = 0xFF000000 | v << 16 | Math.min(255, v + 4) << 8 | Math.min(255, v + 11);
        }
        // A little of the surrounding land's color shows under the soft fog rim.
        if (terrain == 0) {
            terrain = west != 0 ? west : east != 0 ? east : north != 0 ? north : south;
        }
        if (terrain == 0) return fog;
        // Jagged, cloud-like banks rather than a hard binary tile boundary.
        double cover = Math.min(1.0, Math.max(0.0,
            (6 - known) / 5.0 + (variation - .5) * .26));
        if (base == 0) cover = Math.max(.76, cover);
        return lerp(terrain, fog, cover);
    }

    private RoundMapFog() {}
}

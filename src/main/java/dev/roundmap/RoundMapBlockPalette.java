package dev.roundmap;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.block.model.BlockStateModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Samples top-face textures from the active client resource packs. Custom
 * blocks implemented through block-state model overrides use these textures.
 * Entity-display based plugin decorations need a separate renderer.
 *
 * This class never opens a resource from the render loop: calls originate in
 * the atlas' bounded client-tick sampler. Model and image results are cached.
 */
final class RoundMapBlockPalette {
    // Many server resource packs select model variants using position seeds.
    // A single sprite cached per BlockState incorrectly paints entire areas
    // the same color. Retain 16 independently selected variants per state.
    private static final Map<BlockState, Identifier[]> spriteForState = new HashMap<>();
    private static final Map<Identifier, TextureSample> samples = new HashMap<>();
    private static final Identifier NO_SPRITE = Identifier.fromNamespaceAndPath("roundmap", "none");
    private static Object lastResources;

    // 4x4 colors are prepared once per texture, never per rendered pixel.
    private record TextureSample(int average, int[] grid, int[] micro) {}

    static void clear() {
        spriteForState.clear();
        samples.clear();
        lastResources = null;
    }

    static int color(Minecraft mc, BlockState state, BlockPos pos, int fallback) {
        Object resourceManager = mc.getResourceManager();
        if (lastResources != resourceManager) {
            spriteForState.clear();
            samples.clear();
            lastResources = resourceManager;
        }
        Identifier[] variants = spriteForState.computeIfAbsent(
            state, ignored -> new Identifier[16]);
        int variant = (int)(state.getSeed(pos) & 15);
        Identifier spriteName = variants[variant];
        if (spriteName == null) {
            spriteName = findSprite(mc, state, pos);
            variants[variant] = spriteName == null ? NO_SPRITE : spriteName;
        }
        if (spriteName == null || spriteName.equals(NO_SPRITE)) return fallback;
        TextureSample sample;
        if (samples.containsKey(spriteName)) sample = samples.get(spriteName);
        else {
            sample = loadSample(mc, spriteName);
            samples.put(spriteName, sample);
        }
        if (sample == null || suspiciousMissingSample(sample)) return fallback;
        int pixel = sample.grid()[((pos.getZ() & 7) << 3) | (pos.getX() & 7)];
        // Average ensures visually stable large-scale colors, while one of
        // 64 texture samples preserves local texture variation.
        return blend(sample.average(), pixel, 45);
    }

    /**
     * Precompute four-by-four genuine texture samples for a block whose
     * main color is already known. Fine scan writes these into an optional
     * in-memory cache; the map render loop never touches world/model APIs.
     * The cached average block color preserves biome tint, height shading
     * and custom server-color fallbacks.
     */
    static void fillMicro(Minecraft mc, BlockState state, BlockPos pos,
                          int blockColor, int[] destination, int offset) {
        Identifier[] variants = spriteForState.computeIfAbsent(
            state, ignored -> new Identifier[16]);
        int variant = (int)(state.getSeed(pos) & 15);
        Identifier id = variants[variant];
        if (id == null) {
            id = findSprite(mc, state, pos);
            variants[variant] = id == null ? NO_SPRITE : id;
        }
        TextureSample sample = id == null || id.equals(NO_SPRITE) ?
            null : sampleFor(mc, id);
        if (sample == null || suspiciousMissingSample(sample)) {
            java.util.Arrays.fill(destination, offset, offset + 16, blockColor);
            return;
        }
        int avg = sample.average();
        for (int i = 0; i < 16; i++) {
            int sub = sample.micro()[i];
            int red = Math.max(0, Math.min(255, ((blockColor >> 16) & 255) +
                Math.max(-48, Math.min(48, (((sub >> 16) & 255) - ((avg >> 16) & 255)) * 3 / 4))));
            int green = Math.max(0, Math.min(255, ((blockColor >> 8) & 255) +
                Math.max(-48, Math.min(48, (((sub >> 8) & 255) - ((avg >> 8) & 255)) * 3 / 4))));
            int blue = Math.max(0, Math.min(255, (blockColor & 255) +
                Math.max(-48, Math.min(48, ((sub & 255) - (avg & 255)) * 3 / 4))));
            destination[offset + i] = 0xFF000000 | red << 16 | green << 8 | blue;
        }
    }

    // Not a general filter for red blocks! Only obviously defective,
    // nearly solid red/magenta *texture assets* are treated as missing.
    // The last alpha was filtering output colors and turning most of this
    // custom server's terrain into flat gray.
    private static boolean suspiciousMissingSample(TextureSample sample) {
        int[] grid = sample.grid();
        int bad = 0;
        for (int c : grid) {
            int r = (c >>> 16) & 255, g = (c >>> 8) & 255, b = c & 255;
            if ((r >= 180 && g <= 24 && b <= 24) ||
                (r > 225 && b > 220 && g < 40)) bad++;
        }
        return bad >= 54; // At least 84% of samples are placeholder-like.
    }

    private static TextureSample sampleFor(Minecraft mc, Identifier name) {
        if (!samples.containsKey(name)) samples.put(name, loadSample(mc, name));
        return samples.get(name);
    }

    private static double topFaceArea(BakedQuad q) {
        // The 2D area of its upward-facing model quad. Selecting the
        // dominant horizontal face avoids grabbing a tiny decorative
        // overlay or a side texture from multipart server blocks.
        double x0=q.position0().x(), z0=q.position0().z();
        double x1=q.position1().x(), z1=q.position1().z();
        double x2=q.position2().x(), z2=q.position2().z();
        double x3=q.position3().x(), z3=q.position3().z();
        return Math.abs(x0*z1+x1*z2+x2*z3+x3*z0
            -z0*x1-z1*x2-z2*x3-z3*x0)*.5;
    }

    private record TopCandidate(double height, double area) {}

    private static void addTop(Map<Identifier, TopCandidate> tops, BakedQuad quad) {
        Identifier name = quad.sprite().contents().name();
        double area = topFaceArea(quad);
        if (area <= .00001) return;
        double height = (quad.position0().y() + quad.position1().y() +
            quad.position2().y() + quad.position3().y()) / 4.0;
        TopCandidate old = tops.get(name);
        if (old == null || height > old.height() ||
            (height == old.height() && area > old.area()))
            tops.put(name, new TopCandidate(height, area));
    }

    private static Identifier findSprite(Minecraft mc, BlockState state, BlockPos pos) {
        try {
            BlockStateModel model = mc.getBlockRenderer().getBlockModel(state);
            List<BlockModelPart> parts =
                model.collectParts(RandomSource.create(state.getSeed(pos)));
            Map<Identifier, TopCandidate> topFaces = new HashMap<>();
            LinkedHashSet<Identifier> fallbackFaces = new LinkedHashSet<>();
            for (BlockModelPart part : parts) {
                // CULLED UP quads are only one possible source of the
                // visible top face. Most custom top faces are UNCULLED.
                for (BakedQuad quad : part.getQuads(Direction.UP))
                    addTop(topFaces, quad);
                for (BakedQuad quad : part.getQuads(null)) {
                    Identifier sprite = quad.sprite().contents().name();
                    if (quad.direction() == Direction.UP)
                        addTop(topFaces, quad);
                    else fallbackFaces.add(sprite);
                }
            }
            // Prefer large upward faces with loadable non-placeholder
            // textures; these match the textures players see from above.
            Identifier best = null;
            double bestHeight = -Double.MAX_VALUE, bestArea = -1.0;
            for (Map.Entry<Identifier, TopCandidate> entry : topFaces.entrySet()) {
                Identifier id = entry.getKey();
                if (id.getPath().contains("missingno")) continue;
                TextureSample sample = sampleFor(mc, id);
                if (sample == null || suspiciousMissingSample(sample)) continue;
                // Topmost textured face wins; this is essential for
                // resource-pack blocks with decorative overlays above
                // the main cube. Size breaks ties among surfaces at the
                // same height and keeps small details subordinate.
                double height = entry.getValue().height();
                double area = entry.getValue().area();
                if (height > bestHeight + .001 ||
                    (Math.abs(height - bestHeight) <= .001 && area > bestArea)) {
                    bestHeight = height;
                    bestArea = area;
                    best = id;
                }
            }
            if (best != null) return best;
            TextureAtlasSprite icon = model.particleIcon();
            if (icon != null) fallbackFaces.add(icon.contents().name());
            for (Identifier id : fallbackFaces) {
                if (id.getPath().contains("missingno")) continue;
                TextureSample sample = sampleFor(mc, id);
                if (sample != null && !suspiciousMissingSample(sample)) return id;
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String diagnose(Minecraft mc, BlockState state, BlockPos pos) {
        Identifier sprite = findSprite(mc, state, pos);
        int base = state.getMapColor(mc.level, pos).col;
        int result = color(mc, state, pos, base | 0xFF000000);
        return "block=" + state + " model_texture=" + (sprite == null ? "none" : sprite) +
            " mapColor=#" + String.format("%06X", base & 0xFFFFFF) +
            " sample=#" + String.format("%06X", result & 0xFFFFFF);
    }

    private static TextureSample loadSample(Minecraft mc, Identifier spriteName) {
        Identifier file = Identifier.fromNamespaceAndPath(spriteName.getNamespace(),
            "textures/" + spriteName.getPath() + ".png");
        try (InputStream stream = mc.getResourceManager().open(file);
             NativeImage image = NativeImage.read(stream)) {
            int w = image.getWidth(), h = image.getHeight();
            if (w <= 0 || h <= 0) return null;
            // Many animated sprites are vertically stacked; use their first
            // frame and sample a small 8x8 grid.
            int frameHeight = Math.min(h, w);
            int[] grid = new int[64];
            long r = 0, g = 0, b = 0;
            int n = 0;
            for (int z = 0; z < 8; z++) for (int x = 0; x < 8; x++) {
                int px = Math.min(w - 1, (int)((x + .5) * w / 8));
                int pz = Math.min(frameHeight - 1,
                    (int)((z + .5) * frameHeight / 8));
                int argb = image.getPixel(px, pz);
                int alpha = (argb >>> 24) & 255;
                if (alpha < 32) {
                    grid[z * 8 + x] = 0;
                    continue;
                }
                grid[z * 8 + x] = 0xFF000000 | (argb & 0x00FFFFFF);
                r += (argb >> 16) & 255;
                g += (argb >> 8) & 255;
                b += argb & 255;
                n++;
            }
            if (n == 0) return null;
            int average = 0xFF000000 | ((int)(r / n) << 16) |
                ((int)(g / n) << 8) | (int)(b / n);
            for (int i = 0; i < grid.length; i++)
                if (grid[i] == 0) grid[i] = average;
            int[] micro = new int[16];
            for (int subZ = 0; subZ < 4; subZ++) {
                for (int subX = 0; subX < 4; subX++) {
                    int sr = 0, sg = 0, sb = 0;
                    for (int dz = 0; dz < 2; dz++)
                        for (int dx = 0; dx < 2; dx++) {
                            int c = grid[(subZ * 2 + dz) * 8 + subX * 2 + dx];
                            sr += (c >> 16) & 255;
                            sg += (c >> 8) & 255;
                            sb += c & 255;
                        }
                    micro[subZ * 4 + subX] = 0xFF000000 |
                        (sr / 4) << 16 | (sg / 4) << 8 | (sb / 4);
                }
            }
            return new TextureSample(average, grid, micro);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static int blend(int a, int b, int percent) {
        int inv = 100 - percent;
        int r = (((a >>> 16) & 255) * inv + ((b >>> 16) & 255) * percent) / 100;
        int g = (((a >>> 8) & 255) * inv + ((b >>> 8) & 255) * percent) / 100;
        int blue = ((a & 255) * inv + (b & 255) * percent) / 100;
        return 0xFF000000 | r << 16 | g << 8 | blue;
    }

    private RoundMapBlockPalette() {}
}

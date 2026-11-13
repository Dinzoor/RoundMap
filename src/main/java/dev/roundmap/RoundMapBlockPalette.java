package dev.roundmap;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private static final Map<BlockState, Identifier> spriteForState = new HashMap<>();
    private static final Map<Identifier, TextureSample> samples = new HashMap<>();
    private static final Identifier NO_SPRITE = Identifier.fromNamespaceAndPath("roundmap", "none");
    private static Object lastResources;

    private record TextureSample(int average, int[] grid) {}

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
        Identifier spriteName = spriteForState.get(state);
        if (spriteName == null) {
            spriteName = findSprite(mc, state, pos);
            spriteForState.put(state, spriteName == null ? NO_SPRITE : spriteName);
        }
        if (spriteName == null || spriteName.equals(NO_SPRITE)) return fallback;
        TextureSample sample;
        if (samples.containsKey(spriteName)) sample = samples.get(spriteName);
        else {
            sample = loadSample(mc, spriteName);
            samples.put(spriteName, sample);
        }
        if (sample == null) return fallback;
        int pixel = sample.grid()[((pos.getZ() & 7) << 3) | (pos.getX() & 7)];
        // Average ensures visually stable large-scale colors, while one of
        // 64 texture samples preserves local texture variation.
        return blend(sample.average(), pixel, 45);
    }

    private static Identifier findSprite(Minecraft mc, BlockState state, BlockPos pos) {
        try {
            BlockStateModel model = mc.getBlockRenderer().getBlockModel(state);
            List<BlockModelPart> parts = model.collectParts(RandomSource.create(state.getSeed(pos)));
            for (BlockModelPart part : parts) {
                List<BakedQuad> up = part.getQuads(Direction.UP);
                if (!up.isEmpty()) return up.getFirst().sprite().contents().name();
            }
            TextureAtlasSprite icon = model.particleIcon();
            return icon == null ? null : icon.contents().name();
        } catch (RuntimeException e) {
            // Vanilla MapColor is always a safe fallback for unsupported
            // dynamic or unconventional resource-pack models.
            return null;
        }
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
            return new TextureSample(average, grid);
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

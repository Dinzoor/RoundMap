package dev.roundmap;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** Two GPU-backed map images; GUI rendering is a single textured quad per map. */
final class RoundMapTextures {
    static void invalidate() { MINI.invalidate(); FULL.invalidate(); }
    private static final Image MINI = new Image("roundmap_minimap");
    private static final Image FULL = new Image("roundmap_fullmap");

    private static final class Image {
        final String name;
        NativeImage pixels;
        DynamicTexture texture;
        Identifier id;
        int width, height;
        int centerX = Integer.MIN_VALUE, centerZ, revision = -1, zoomKey = -1;
        long updatedAt;
        Image(String name) { this.name = name; }
        void allocate(int w, int h) {
            if (pixels != null && width == w && height == h) return;
            if (id != null) Minecraft.getInstance().getTextureManager().release(id);
            pixels = new NativeImage(w, h, false);
            texture = new DynamicTexture(() -> "RoundMap " + name, pixels);
            id = Identifier.fromNamespaceAndPath("roundmap", "dynamic/" + name);
            Minecraft.getInstance().getTextureManager().register(id, texture);
            width = w;
            height = h;
            centerX = Integer.MIN_VALUE;
            updatedAt = 0;
            revision = -1;
            zoomKey = -1;
        }
        void invalidate() { revision = -1; updatedAt = 0; }
        boolean needsRefresh(int x, int z, int zoom, long minIntervalNanos) {
            long time = System.nanoTime();
            boolean changed = x != centerX || z != centerZ || zoom != zoomKey ||
                RoundMapAtlas.revision() != revision;
            if (!changed || (updatedAt > 0 && time - updatedAt < minIntervalNanos)) return false;
            centerX = x; centerZ = z;
            revision = RoundMapAtlas.revision();
            zoomKey = zoom;
            updatedAt = time;
            return true;
        }
    }

    static void minimap(GuiGraphics g, int cx, int cy, int radius,
                        double zoom, int playerX, int playerZ) {
        int pixels = 160;
        MINI.allocate(pixels, pixels);
        int zoomKey = (int)Math.round(zoom * 1000);
        if (MINI.needsRefresh(playerX, playerZ, zoomKey, 240_000_000L)) {
            double scale = 48.0 / (radius > 0 ? zoom : 1.0);
            for (int py = 0; py < pixels; py++) {
                double y = (py + .5 - pixels / 2.0) / (pixels / 2.0);
                for (int px = 0; px < pixels; px++) {
                    double x = (px + .5 - pixels / 2.0) / (pixels / 2.0);
                    if (x * x + y * y > 1.0) {
                        MINI.pixels.setPixel(px, py, 0);
                    } else {
                        int wx = playerX + (int)Math.floor(x * scale);
                        int wz = playerZ + (int)Math.floor(y * scale);
                        int color = RoundMapFog.color(wx, wz);
                        int alpha = Math.max(0, Math.min(255, RoundMapClient.miniOpacity * 255 / 100));
                        MINI.pixels.setPixel(px, py, (color & 0x00FFFFFF) | (alpha << 24));
                    }
                }
            }
            MINI.texture.upload();
        }
        g.blit(RenderPipelines.GUI_TEXTURED, MINI.id,
            cx - radius, cy - radius, 0f, 0f,
            radius * 2, radius * 2, pixels, pixels, pixels, pixels);
    }

    /**
     * Keep an oversized map texture cached around a stable world-space anchor.
     * Dragging shifts the GPU quad every frame, even for sub-block movement,
     * without rebuilding a texture every time the mouse moves.
     */
    static void fullscreen(GuiGraphics g, int screenW, int screenH,
                           double worldX, double worldZ, int zoom) {
        final int padding = 112;
        int drawW = screenW + padding * 2;
        int drawH = screenH + padding * 2;
        int tw = Math.min(640, Math.max(128, drawW));
        int th = Math.min(640, Math.max(128,
            (int)Math.round(tw * (drawH / (double)drawW))));
        FULL.allocate(tw, th);
        long now = System.nanoTime();
        boolean noImage = FULL.centerX == Integer.MIN_VALUE;
        boolean zoomChanged = zoom != FULL.zoomKey;
        boolean pannedOutsideBuffer = Math.abs((worldX - FULL.centerX) * zoom) >= padding * .67
            || Math.abs((worldZ - FULL.centerZ) * zoom) >= padding * .67;
        boolean dataChanged = RoundMapAtlas.revision() != FULL.revision;
        boolean refreshTerrain = dataChanged
            && (FULL.updatedAt == 0 || now - FULL.updatedAt >= 300_000_000L);
        if (noImage || zoomChanged || pannedOutsideBuffer || refreshTerrain) {
            int anchorX = (int)Math.floor(worldX);
            int anchorZ = (int)Math.floor(worldZ);
            double blockPerTexel = drawW / (double)(tw * zoom);
            for (int py = 0; py < th; py++) {
                int wz = anchorZ + (int)Math.floor((py + .5 - th / 2.0) * blockPerTexel);
                for (int px = 0; px < tw; px++) {
                    int wx = anchorX + (int)Math.floor((px + .5 - tw / 2.0) * blockPerTexel);
                    FULL.pixels.setPixel(px, py, RoundMapFog.color(wx, wz));
                }
            }
            FULL.texture.upload();
            FULL.centerX = anchorX;
            FULL.centerZ = anchorZ;
            FULL.zoomKey = zoom;
            FULL.revision = RoundMapAtlas.revision();
            FULL.updatedAt = now;
        }
        int offsetX = (int)Math.round((FULL.centerX - worldX) * zoom);
        int offsetZ = (int)Math.round((FULL.centerZ - worldZ) * zoom);
        g.blit(RenderPipelines.GUI_TEXTURED, FULL.id,
            -padding + offsetX, -padding + offsetZ, 0f, 0f,
            drawW, drawH, tw, th, tw, th);
    }

    private RoundMapTextures() {}
}

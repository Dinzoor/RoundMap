package dev.roundmap;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** Two GPU-backed map images; GUI rendering is a single textured quad per map. */
final class RoundMapTextures {
    private static final int UNKNOWN = 0xFFF1F3F6;
    private static int fog() { return RoundMapClient.brightFog ? UNKNOWN : 0xFF27303A; }
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
        void invalidate() { revision = -1; }
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
                        int color = RoundMapAtlas.colorAt(wx, wz);
                        int alpha = Math.max(0, Math.min(255, RoundMapClient.miniOpacity * 255 / 100));
                        MINI.pixels.setPixel(px, py, ((color == 0 ? fog() : color) & 0x00FFFFFF) | (alpha << 24));
                    }
                }
            }
            MINI.texture.upload();
        }
        g.blit(RenderPipelines.GUI_TEXTURED, MINI.id,
            cx - radius, cy - radius, 0f, 0f,
            radius * 2, radius * 2, pixels, pixels, pixels, pixels);
    }

    static void fullscreen(GuiGraphics g, int screenW, int screenH,
                           int worldX, int worldZ, int zoom) {
        int tw = Math.min(640, Math.max(128, screenW));
        int th = Math.min(640, Math.max(128, (int)Math.round(tw * (screenH / (double)Math.max(1, screenW)))));
        FULL.allocate(tw, th);
        if (FULL.needsRefresh(worldX, worldZ, zoom, 200_000_000L)) {
            double blockPerTexel = screenW / (double)(tw * zoom);
            for (int py = 0; py < th; py++) {
                int wz = worldZ + (int)Math.floor((py + .5 - th / 2.0) * blockPerTexel);
                for (int px = 0; px < tw; px++) {
                    int wx = worldX + (int)Math.floor((px + .5 - tw / 2.0) * blockPerTexel);
                    int color = RoundMapAtlas.colorAt(wx, wz);
                    FULL.pixels.setPixel(px, py, color == 0 ? fog() : color);
                }
            }
            FULL.texture.upload();
        }
        g.blit(RenderPipelines.GUI_TEXTURED, FULL.id, 0, 0, 0f, 0f,
            screenW, screenH, tw, th, tw, th);
    }

    private RoundMapTextures() {}
}

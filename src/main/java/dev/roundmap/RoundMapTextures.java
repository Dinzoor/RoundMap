package dev.roundmap;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/** Two GPU-backed map images; GUI rendering is a single textured quad per map. */
final class RoundMapTextures {
    static void invalidate() {
        MINI.invalidate();
        FULL.invalidate();
        PREVIEW.invalidate();
        FULL.centerX = Integer.MIN_VALUE; // Never show terrain from the prior world.
        PREVIEW.centerX = Integer.MIN_VALUE;
        fullBuild = null;
        previewBuild = null;
    }
    private static final Image MINI = new Image("roundmap_minimap");
    private static final Image FULL = new Image("roundmap_fullmap");
    private static final Image PREVIEW = new Image("roundmap_preview");

    // Keep sampling work small enough to run between display frames.
    // While rows are updated in CPU memory the GPU continues displaying the
    // previous texture, shifted every frame by the current mouse position.
    private static FullBuild fullBuild;
    private static FullBuild previewBuild;

    private static final class FullBuild {
        final int centerX, centerZ, zoom, revision, drawW, texW, texH;
        int nextRow;
        int previousWorldZ = Integer.MIN_VALUE;
        final int[] lastRow;
        FullBuild(int centerX, int centerZ, int zoom, int revision,
                  int drawW, int texW, int texH) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.zoom = zoom;
            this.revision = revision;
            this.drawW = drawW;
            this.texW = texW;
            this.texH = texH;
            this.lastRow = new int[texW];
        }
    }

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

    private static boolean covered(Image image, double x, double z,
                                   int zoom, int padding) {
        if (image.centerX == Integer.MIN_VALUE || image.zoomKey != zoom) return false;
        return Math.abs((x - image.centerX) * zoom) < padding * .85
            && Math.abs((z - image.centerZ) * zoom) < padding * .85;
    }

    /**
     * Draw terrain into the CPU-side texture a little at a time.
     * Consecutive output pixels often represent the same block, so only
     * evaluate fog/hillshade once per unique world coordinate.
     */
    private static boolean pump(FullBuild build, Image image,
                                int maxRows, long budgetNanos) {
        long started = System.nanoTime();
        int end = Math.min(build.texH, build.nextRow + maxRows);
        for (int py = build.nextRow; py < end; py++) {
            int wz = build.centerZ + (int)Math.floor(
                (py + .5 - build.texH / 2.0)
                * build.drawW / (double)(build.texW * build.zoom));
            int lastX = Integer.MIN_VALUE, current = 0;
            for (int px = 0; px < build.texW; px++) {
                if (wz == build.previousWorldZ) {
                    current = build.lastRow[px];
                } else {
                    int wx = build.centerX + (int)Math.floor(
                        (px + .5 - build.texW / 2.0)
                        * build.drawW / (double)(build.texW * build.zoom));
                    if (wx != lastX) {
                        current = RoundMapFog.color(wx, wz);
                        lastX = wx;
                    }
                    build.lastRow[px] = current;
                }
                image.pixels.setPixel(px, py, current);
            }
            build.previousWorldZ = wz;
            build.nextRow = py + 1;
            if (System.nanoTime() - started >= budgetNanos) break;
        }
        if (build.nextRow < build.texH) return false;
        image.texture.upload();
        image.centerX = build.centerX;
        image.centerZ = build.centerZ;
        image.zoomKey = build.zoom;
        image.revision = build.revision;
        image.updatedAt = System.nanoTime();
        return true;
    }

    private static FullBuild createBuild(double x, double z,
                                         int zoom, Image image, int drawW) {
        return new FullBuild((int)Math.floor(x), (int)Math.floor(z), zoom,
            RoundMapAtlas.revision(), drawW, image.width, image.height);
    }

    private static void drawMappedImage(GuiGraphics g, Image image,
            int padding, int drawW, int drawH, double worldX, double worldZ,
            int zoom) {
        int offsetX = (int)Math.round((image.centerX - worldX) * zoom);
        int offsetZ = (int)Math.round((image.centerZ - worldZ) * zoom);
        g.blit(RenderPipelines.GUI_TEXTURED, image.id,
            -padding + offsetX, -padding + offsetZ, 0f, 0f,
            drawW, drawH, image.width, image.height, image.width, image.height);
    }

    /**
     * Fast 320-pixel terrain preview appears first. The final 1024-pixel
     * texture then bakes incrementally without blocking the map UI.
     */
    static void fullscreen(GuiGraphics g, int screenW, int screenH,
                           double worldX, double worldZ, int zoom, boolean dragging) {
        final int padding = 256;
        int drawW = screenW + padding * 2;
        int drawH = screenH + padding * 2;

        int tw = Math.min(1024, Math.max(128, drawW));
        int th = Math.min(1024, Math.max(128,
            (int)Math.round(tw * drawH / (double)drawW)));
        FULL.allocate(tw, th);

        int pw = Math.min(320, Math.max(128, drawW));
        int ph = Math.max(80, (int)Math.round(pw * drawH / (double)drawW));
        PREVIEW.allocate(pw, ph);

        boolean haveFull = covered(FULL, worldX, worldZ, zoom, padding);
        boolean havePreview = covered(PREVIEW, worldX, worldZ, zoom, padding);

        // Open a map or pan beyond the previous texture: show a low-detail
        // viewport promptly rather than waiting for the huge texture bake.
        if (!haveFull && !havePreview) {
            if (previewBuild == null || previewBuild.zoom != zoom ||
                Math.abs((worldX - previewBuild.centerX) * zoom) > padding ||
                Math.abs((worldZ - previewBuild.centerZ) * zoom) > padding) {
                previewBuild = createBuild(worldX, worldZ, zoom, PREVIEW, drawW);
            }
            if (pump(previewBuild, PREVIEW, 42, 3_500_000L)) {
                previewBuild = null;
                havePreview = true;
            }
        }

        double shiftX = FULL.centerX == Integer.MIN_VALUE ? 0 :
            (worldX - FULL.centerX) * zoom;
        double shiftZ = FULL.centerZ == Integer.MIN_VALUE ? 0 :
            (worldZ - FULL.centerZ) * zoom;
        boolean newView = FULL.centerX == Integer.MIN_VALUE || FULL.zoomKey != zoom
            || Math.abs(shiftX) > padding * .58
            || Math.abs(shiftZ) > padding * .58;
        boolean dirty = !dragging && FULL.revision != RoundMapAtlas.revision()
            && (FULL.updatedAt == 0 ||
                System.nanoTime() - FULL.updatedAt > 500_000_000L);

        // Prioritize the preview if the player has no map on screen yet.
        if ((haveFull || havePreview) && fullBuild == null && (newView || dirty)) {
            fullBuild = newView ? createBuild(worldX, worldZ, zoom, FULL, drawW)
                : new FullBuild(FULL.centerX, FULL.centerZ, zoom,
                    RoundMapAtlas.revision(), drawW, FULL.width, FULL.height);
        }
        if (fullBuild != null && (fullBuild.zoom != zoom
            || Math.abs((worldX - fullBuild.centerX) * zoom) > padding * 1.5
            || Math.abs((worldZ - fullBuild.centerZ) * zoom) > padding * 1.5)) {
            fullBuild = null;
        }
        if (fullBuild != null && pump(fullBuild, FULL, 32, 2_500_000L)) {
            fullBuild = null;
            haveFull = covered(FULL, worldX, worldZ, zoom, padding);
        }

        // Undiscovered blocks always appear as fog, never black/transparent.
        g.fill(0, 0, screenW, screenH,
            RoundMapClient.brightFog ? 0xFFF2F3F7 : 0xFF29313A);
        if (haveFull) drawMappedImage(g, FULL, padding, drawW, drawH, worldX, worldZ, zoom);
        else if (havePreview)
            drawMappedImage(g, PREVIEW, padding, drawW, drawH, worldX, worldZ, zoom);
    }

    private RoundMapTextures() {}
}

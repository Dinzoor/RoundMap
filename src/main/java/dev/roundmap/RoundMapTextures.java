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
        final int centerX, centerZ, revision, drawW, texW, texH;
        final double zoom;
        int nextRow;
        int previousWorldZ = Integer.MIN_VALUE;
        final int[] lastRow;
        FullBuild(int centerX, int centerZ, double zoom, int revision,
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

    // Keep a fractional camera anchor for the HUD map. The old code used
    // integer block coordinates and refreshed once every 240 ms, causing
    // visible jumps whenever the player walked across block boundaries.
    private static double sampledMiniX = Double.NaN, sampledMiniZ = Double.NaN;
    private static int miniPixelKey = -1, miniOpacityKey = -1;

    static void minimap(GuiGraphics g, int cx, int cy, int radius,
                        double zoom, double cameraX, double cameraZ) {
        final int pixels = 96;
        MINI.allocate(pixels, pixels);
        final int zoomKey = (int)Math.round(zoom * 1000);
        final int revision = RoundMapAtlas.revision();
        final int pixelSize = RoundMapClient.mapPixelSize;
        final int opacity = RoundMapClient.miniOpacity;
        final long now = System.nanoTime();
        final long elapsed = now - MINI.updatedAt;
        boolean settingsChanged = zoomKey != MINI.zoomKey ||
            miniPixelKey != pixelSize || miniOpacityKey != opacity;
        boolean cameraChanged = !Double.isFinite(sampledMiniX) ||
            Math.hypot(cameraX - sampledMiniX, cameraZ - sampledMiniZ) > .04;
        boolean terrainChanged = MINI.revision != revision;
        // Cap GPU uploads to ~30 Hz even on high-FPS clients. This is far
        // smoother than 240 ms without re-rendering 96x96 every game frame.
        boolean refresh = MINI.updatedAt == 0 || settingsChanged ||
            (elapsed >= 33_000_000L && cameraChanged) ||
            (elapsed >= 110_000_000L && terrainChanged);
        if (refresh) {
            double range = 48.0 / Math.max(.05, zoom);
            int step = Math.max(1, pixelSize);
            int alpha = Math.max(0, Math.min(255, opacity * 255 / 100));
            int previousWz = Integer.MIN_VALUE;
            int[] previousRow = new int[pixels];
            for (int py = 0; py < pixels; py++) {
                double fy = (py + .5 - pixels / 2.0) / (pixels / 2.0);
                int wz = (int)Math.floor(cameraZ + fy * range);
                wz = Math.floorDiv(wz, step) * step;
                boolean useCachedRow = wz == previousWz;
                previousWz = wz;
                int previousWx = Integer.MIN_VALUE;
                int terrain = 0;
                for (int px = 0; px < pixels; px++) {
                    double fx = (px + .5 - pixels / 2.0) / (pixels / 2.0);
                    int color;
                    if (useCachedRow) {
                        color = previousRow[px];
                    } else {
                        int wx = (int)Math.floor(cameraX + fx * range);
                        wx = Math.floorDiv(wx, step) * step;
                        if (wx != previousWx) {
                            terrain = RoundMapFog.color(wx, wz);
                            previousWx = wx;
                        }
                        color = terrain;
                        previousRow[px] = color;
                    }
                    if (fx * fx + fy * fy > 1.0)
                        MINI.pixels.setPixel(px, py, 0);
                    else
                        MINI.pixels.setPixel(px, py,
                            (color & 0x00FFFFFF) | (alpha << 24));
                }
            }
            MINI.texture.upload();
            sampledMiniX = cameraX;
            sampledMiniZ = cameraZ;
            MINI.zoomKey = zoomKey;
            miniPixelKey = pixelSize;
            miniOpacityKey = opacity;
            MINI.revision = revision;
            MINI.updatedAt = now;
        }
        g.blit(RenderPipelines.GUI_TEXTURED, MINI.id,
            cx - radius, cy - radius, 0f, 0f,
            radius * 2, radius * 2, pixels, pixels, pixels, pixels);
    }

    private static int colorForFullMap(int worldX, int worldZ) {
        // The surface map samples the exact stored block at high detail;
        // pixelated HUD minimap keeps its own independent grid size.
        int step = RoundMapClient.fullPixelSize;
        int wx = Math.floorDiv(worldX, step) * step;
        int wz = Math.floorDiv(worldZ, step) * step;
        return RoundMapClient.caveMode
            ? RoundMapCaveAtlas.renderColor(wx, wz)
            : RoundMapFog.color(wx, wz);
    }

    private static double bakedZoom(Image image) {
        return image.zoomKey <= 0 ? 1.0 : image.zoomKey / 1000.0;
    }

    private static boolean covers(Image image, double x, double z,
            double displayZoom, int w, int h, int drawW, int drawH) {
        if (image.centerX == Integer.MIN_VALUE || image.zoomKey <= 0) return false;
        // Width of raster expressed in world blocks; zooming is a pure GPU
        // transform until the new view actually needs additional pixels.
        double halfWorldW = drawW / (2.0 * bakedZoom(image));
        double halfWorldH = drawH / (2.0 * bakedZoom(image));
        double neededW = w / (2.0 * displayZoom);
        double neededH = h / (2.0 * displayZoom);
        return Math.abs(image.centerX - x) + neededW + 2 <= halfWorldW
            && Math.abs(image.centerZ - z) + neededH + 2 <= halfWorldH;
    }

    /**
     * Incrementally fill a CPU-side map texture, uploading it only when all
     * rows are ready. The previous GPU image remains visible in the meantime.
     */
    private static boolean pump(FullBuild build, Image image,
                                int maxRows, long budgetNanos) {
        long started = System.nanoTime();
        int end = Math.min(build.texH, build.nextRow + maxRows);
        double worldBlocksPerTexel = build.drawW / (build.texW * build.zoom);
        for (int py = build.nextRow; py < end; py++) {
            int wz = build.centerZ + (int)Math.floor(
                (py + .5 - build.texH / 2.0) * worldBlocksPerTexel);
            int lastX = Integer.MIN_VALUE, current = 0;
            for (int px = 0; px < build.texW; px++) {
                if (wz == build.previousWorldZ) {
                    current = build.lastRow[px];
                } else {
                    int wx = build.centerX + (int)Math.floor(
                        (px + .5 - build.texW / 2.0) * worldBlocksPerTexel);
                    if (wx != lastX) {
                        current = colorForFullMap(wx, wz);
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
        image.zoomKey = (int)Math.round(build.zoom * 1000.0);
        image.revision = build.revision;
        image.updatedAt = System.nanoTime();
        return true;
    }

    private static FullBuild createBuild(double x, double z,
                                         double zoom, Image image, int drawW) {
        // Quarter-step texture LOD: GUI animation uses continuous scale, but
        // terrain needs to be baked only when the user finishes scrolling.
        double baked = Math.max(0.5, Math.round(zoom * 4.0) / 4.0);
        return new FullBuild((int)Math.floor(x), (int)Math.floor(z), baked,
            RoundMapAtlas.revision(), drawW, image.width, image.height);
    }

    private static void drawMappedImage(GuiGraphics g, Image image,
            int screenW, int screenH, int drawW, int drawH,
            double worldX, double worldZ, double displayZoom) {
        if (image.centerX == Integer.MIN_VALUE || image.zoomKey <= 0) return;
        double ratio = displayZoom / bakedZoom(image);
        int outW = Math.max(1, (int)Math.round(drawW * ratio));
        int outH = Math.max(1, (int)Math.round(drawH * ratio));
        int centerX = screenW / 2 +
            (int)Math.round((image.centerX - worldX) * displayZoom);
        int centerY = screenH / 2 +
            (int)Math.round((image.centerZ - worldZ) * displayZoom);
        g.blit(RenderPipelines.GUI_TEXTURED, image.id,
            centerX - outW / 2, centerY - outH / 2,
            0f, 0f, outW, outH,
            image.width, image.height, image.width, image.height);
    }

    /**
     * Animated zoom is applied to a cached GPU quad EVERY frame, so wheel
     * zoom never freezes waiting for full texture reconstruction.
     * A detailed raster is rebuilt only once the zoom settles.
     */
    static void fullscreen(GuiGraphics g, int screenW, int screenH,
            double worldX, double worldZ, double zoom,
            boolean dragging, boolean zooming) {
        final int padding = 256;
        int drawW = screenW + padding * 2;
        int drawH = screenH + padding * 2;

        // Higher than alpha.11's 1024px texture: preserve more ground,
        // coastline and contour detail at typical GUI resolutions.
        int tw = Math.min(1536, Math.max(128, drawW));
        int th = Math.min(1536, Math.max(128,
            (int)Math.round(tw * drawH / (double)drawW)));
        FULL.allocate(tw, th);
        int pw = Math.min(400, Math.max(128, drawW));
        int ph = Math.max(80, (int)Math.round(pw * drawH / (double)drawW));
        PREVIEW.allocate(pw, ph);

        boolean fullCovers = covers(FULL, worldX, worldZ, zoom,
            screenW, screenH, drawW, drawH);
        boolean previewCovers = covers(PREVIEW, worldX, worldZ, zoom,
            screenW, screenH, drawW, drawH);
        boolean haveFull = FULL.centerX != Integer.MIN_VALUE;
        boolean havePreview = PREVIEW.centerX != Integer.MIN_VALUE;
        boolean needNewRaster = !fullCovers || FULL.zoomKey <= 0
            || Math.abs(bakedZoom(FULL) - zoom) > Math.max(0.16, zoom * 0.09);
        boolean terrainDirty = FULL.revision != RoundMapAtlas.revision() &&
            (FULL.updatedAt == 0 ||
             System.nanoTime() - FULL.updatedAt > 650_000_000L);

        // First-open or a big move: prepare cheap preview during dragging,
        // while the previous detailed texture remains continuously scalable.
        if (!haveFull && (!havePreview || !previewCovers) && !zooming) {
            if (previewBuild == null ||
                Math.abs(previewBuild.zoom - zoom) > Math.max(0.30, zoom * .12) ||
                Math.abs(previewBuild.centerX - worldX) * zoom > padding ||
                Math.abs(previewBuild.centerZ - worldZ) * zoom > padding) {
                previewBuild = createBuild(worldX, worldZ, zoom, PREVIEW, drawW);
            }
        } else if (haveFull && !fullCovers && !zooming
            && (!havePreview || !previewCovers)) {
            if (previewBuild == null ||
                Math.abs(previewBuild.centerX - worldX) * zoom > padding ||
                Math.abs(previewBuild.centerZ - worldZ) * zoom > padding)
                previewBuild = createBuild(worldX, worldZ, zoom, PREVIEW, drawW);
        }
        if (previewBuild != null && !zooming &&
            pump(previewBuild, PREVIEW, 48, 2_000_000L)) {
            previewBuild = null;
            havePreview = true;
        }

        // Do not restart a bake for each wheel tick or animation frame.
        // The old high-res texture is scaled immediately on GPU.
        if (fullBuild != null &&
            (Math.abs(fullBuild.zoom - zoom) > Math.max(0.18, zoom * 0.09) ||
             Math.abs(fullBuild.centerX - worldX) * zoom > padding * 1.5 ||
             Math.abs(fullBuild.centerZ - worldZ) * zoom > padding * 1.5))
            fullBuild = null;
        if (fullBuild == null && !zooming && !dragging
            && (needNewRaster || terrainDirty)) {
            double bakeCenterX = needNewRaster ? worldX : FULL.centerX;
            double bakeCenterZ = needNewRaster ? worldZ : FULL.centerZ;
            fullBuild = createBuild(bakeCenterX, bakeCenterZ, zoom, FULL, drawW);
        }
        if (fullBuild != null && !zooming &&
            pump(fullBuild, FULL, 34, 2_500_000L)) {
            fullBuild = null;
            haveFull = true;
        }

        g.fill(0, 0, screenW, screenH,
            RoundMapClient.brightFog ? 0xFFF2F3F7 : 0xFF29313A);
        if (haveFull)
            drawMappedImage(g, FULL, screenW, screenH, drawW, drawH,
                worldX, worldZ, zoom);
        else if (havePreview)
            drawMappedImage(g, PREVIEW, screenW, screenH, drawW, drawH,
                worldX, worldZ, zoom);
    }

    private RoundMapTextures() {}
}

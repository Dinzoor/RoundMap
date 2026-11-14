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
    private static final Image FRAME = new Image("roundmap_minimap_frame");
    private static int frameStyleKey = -1, frameRadiusKey = -1;
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

    /**
     * Five unique pixel-art frame styles plus no frame. A precomputed
     * transparent texture costs just ONE GUI quad per frame and is recreated
     * only when size/style changes; no expensive per-frame circle loops.
     */
    private static int framePixel(int style, int dx, int dy, int radius) {
        double distance = Math.sqrt((double)dx * dx + (double)dy * dy);
        double ring = distance - radius;
        if (ring < -2.6 || ring > 6.0) return 0;
        boolean topLeft = dx + dy < 0;
        int hash = (dx * 347 + dy * 907 + dx * dy * 17);
        switch (style) {
            case 1: // Classic: thin polished silver with a dark outline.
                if (ring > 3.5) return 0;
                if (ring > 2.5 || ring < -1.7) return 0xFF1B242D;
                if (ring > 1.2) return 0xFF657886;
                return topLeft ? 0xFFF5F9FB : 0xFFAEBEC8;
            case 2: // Minecraft stone: chunky mottled pixel blocks.
                if (ring > 5.1) return 0;
                if (ring > 3.8 || ring < -1.9) return 0xFF1D2229;
                if (ring > 2.6) return 0xFF56575A;
                if (((dx * 23 + dy * 7) & 15) < 2) return 0xFF32343A;
                return (hash & 7) < 3 ? 0xFF94979A : 0xFF73787A;
            case 3: // Neon: turquoise main line and transparent outer glow.
                if (ring > 4.7) return 0;
                if (ring > 2.5) return 0x434BDEFF;
                if (ring > 0.4) return topLeft ? 0xFF92FAFF : 0xFF28BAF9;
                if (ring > -1.7) return 0xFF113A56;
                return 0;
            case 4: // Gilded rim: dark trim, copper shadows and gold shine.
                if (ring > 4.5) return 0;
                if (ring > 3.4 || ring < -1.8) return 0xFF21180E;
                if (ring > 2.4) return 0xFF8A541D;
                if (ring > 0.7) return 0xFFDEA633;
                return topLeft ? 0xFFFFE48B : 0xFFBB7E22;
            case 5: // Technical: graphite band with segmented cyan ticks.
                if (ring > 5.0) return 0;
                if (ring > 3.6 || ring < -2.0) return 0xFF0B1C2B;
                double angle = Math.atan2(dy, dx);
                int sector = (int)Math.floor((angle + Math.PI) * 24 /
                    (Math.PI * 2));
                boolean ticks = Math.floorMod(sector, 3) == 0;
                if (ring > 1.3) return ticks ? 0xFF2CE6FF : 0xFF294B64;
                if (ring > -0.5) return 0xFF141B29;
                return ticks ? 0xFFB9F7FF : 0xFF697D91;
            default:
                return 0;
        }
    }

    static void drawFrame(GuiGraphics g, int cx, int cy, int radius, int style) {
        if (style <= 0) return;
        int size = (radius + 9) * 2;
        FRAME.allocate(size, size);
        if (frameStyleKey != style || frameRadiusKey != radius) {
            int middle = size / 2;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    FRAME.pixels.setPixel(x, y,
                        framePixel(style, x - middle, y - middle, radius));
                }
            }
            FRAME.texture.upload();
            frameStyleKey = style;
            frameRadiusKey = radius;
        }
        g.blit(RenderPipelines.GUI_TEXTURED, FRAME.id,
            cx - size / 2, cy - size / 2, 0f, 0f,
            size, size, size, size, size, size);
    }

    // Keep a fractional camera anchor for the HUD map. The old code used
    // integer block coordinates and refreshed once every 240 ms, causing
    // visible jumps whenever the player walked across block boundaries.
    private static double sampledMiniX = Double.NaN, sampledMiniZ = Double.NaN;
    private static int miniPixelKey = -1, miniOpacityKey = -1;

    static void minimap(GuiGraphics g, int cx, int cy, int radius,
                        double zoom, double cameraX, double cameraZ) {
        // Render at the HUD's exact pixel dimensions, without resizing a
        // fixed 96x96 image. This removes avoidable blur for 36/60px circles
        // and retains pixel-art clarity with all border styles.
        final int pixels = Math.max(2, radius * 2);
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
            Math.hypot(cameraX - sampledMiniX, cameraZ - sampledMiniZ) > .012;
        boolean terrainChanged = MINI.revision != revision;
        // Allow up to ~60 fps of minimap movement (alpha.13 used 30).
        // Skip redraws while camera and terrain are unchanged to save FPS.
        boolean refresh = MINI.updatedAt == 0 || settingsChanged ||
            (elapsed >= 16_666_667L && cameraChanged) ||
            (elapsed >= 100_000_000L && terrainChanged);
        if (refresh) {
            // Radius is measured in screen pixels. At zoom=5 every block
            // occupies exactly five pixels, regardless of circle size.
            double range = radius / Math.max(.05, zoom);
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
            pixels, pixels, pixels, pixels, pixels, pixels);
    }

    private static int colorForFullMap(int worldX, int worldZ) {
        // The surface map samples the exact stored block at high detail;
        // pixelated HUD minimap keeps its own independent grid size.
        int step = RoundMapClient.fullPixelSize;
        int wx = Math.floorDiv(worldX, step) * step;
        int wz = Math.floorDiv(worldZ, step) * step;
        return RoundMapClient.caveMode
            ? RoundMapCaveAtlas.renderColor(wx, wz)
            : RoundMapFog.fullMapColor(wx, wz);
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
        // Three quality bands:
        // zoomed-out: single optimized tile color;
        // medium: exact one-block shading with fog;
        // close-up: actual 4x4 model-texture details sampled in client ticks.
        boolean micro = image == FULL && !RoundMapClient.caveMode &&
            RoundMapClient.ultraTextureDetail &&
            RoundMapClient.fullPixelSize == 1 && build.zoom >= 2.0;
        for (int py = build.nextRow; py < end; py++) {
            double fz = build.centerZ +
                (py + .5 - build.texH / 2.0) * worldBlocksPerTexel;
            int wz = (int)Math.floor(fz);
            int subZ = micro ? Math.min(3, (int)((fz - wz) * 4.0)) : 0;
            int rowKey = micro ? (wz * 4 + subZ) : wz;
            int lastKey = Integer.MIN_VALUE, current = 0;
            for (int px = 0; px < build.texW; px++) {
                if (rowKey == build.previousWorldZ) {
                    current = build.lastRow[px];
                } else {
                    double fx = build.centerX +
                        (px + .5 - build.texW / 2.0) * worldBlocksPerTexel;
                    int wx = (int)Math.floor(fx);
                    int subX = micro ? Math.min(3, (int)((fx - wx) * 4.0)) : 0;
                    int key = micro ? wx * 4 + subX : wx;
                    if (key != lastKey) {
                        current = micro ?
                            RoundMapFog.detailedColor(wx, wz, subX, subZ) :
                            colorForFullMap(wx, wz);
                        lastKey = key;
                    }
                    build.lastRow[px] = current;
                }
                image.pixels.setPixel(px, py, current);
            }
            build.previousWorldZ = rowKey;
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
        // Keep the precise requested zoom rather than snapping to coarse
        // fractions. At far zoom these quantized levels caused softness.
        // Full image rebuilds only when the wheel animation is finished.
        double baked = Math.max(0.1, Math.min(16.0, zoom));
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
        int tw = Math.min(2048, Math.max(128, drawW));
        int th = Math.min(2048, Math.max(128,
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
        // Use a ratio instead of absolute 0.16 px/block tolerance:
        // at zoom 0.1 a 0.1 difference is a twofold loss in detail.
        boolean needNewRaster = !fullCovers || FULL.zoomKey <= 0
            || Math.abs(Math.log(zoom / bakedZoom(FULL))) > 0.075;
        boolean terrainDirty = FULL.revision != RoundMapAtlas.revision() &&
            (FULL.updatedAt == 0 ||
             System.nanoTime() - FULL.updatedAt > 650_000_000L);

        // First-open or a big move: prepare cheap preview during dragging,
        // while the previous detailed texture remains continuously scalable.
        if (!haveFull && (!havePreview || !previewCovers) && !zooming) {
            if (previewBuild == null ||
                Math.abs(Math.log(zoom / previewBuild.zoom)) > 0.14 ||
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
            (Math.abs(Math.log(zoom / fullBuild.zoom)) > 0.075 ||
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

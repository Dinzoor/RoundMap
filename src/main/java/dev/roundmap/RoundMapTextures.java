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
        FULL.centerX = Integer.MIN_VALUE; // Never show terrain from the prior world.
        fullBuild = null;
    }
    private static final Image MINI = new Image("roundmap_minimap");
    private static final Image FULL = new Image("roundmap_fullmap");

    // Keep sampling work small enough to run between display frames.
    // While rows are updated in CPU memory the GPU continues displaying the
    // previous texture, shifted every frame by the current mouse position.
    private static FullBuild fullBuild;

    private static final class FullBuild {
        final int centerX, centerZ, zoom, revision, drawW, texW, texH;
        int nextRow;
        FullBuild(int centerX, int centerZ, int zoom, int revision,
                  int drawW, int texW, int texH) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.zoom = zoom;
            this.revision = revision;
            this.drawW = drawW;
            this.texW = texW;
            this.texH = texH;
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

    /**
     * The fullscreen map is a movable GPU quad backed by a texture larger
     * than the screen. Repainting it is spread over multiple frames, so
     * terrain exploration never blocks mouse dragging for an entire atlas.
     */
    static void fullscreen(GuiGraphics g, int screenW, int screenH,
                           double worldX, double worldZ, int zoom, boolean dragging) {
        final int padding = 256;
        int drawW = screenW + padding * 2;
        int drawH = screenH + padding * 2;
        int tw = Math.min(640, Math.max(128, drawW));
        int th = Math.min(640, Math.max(128,
            (int)Math.round(tw * (drawH / (double)drawW))));
        FULL.allocate(tw, th);

        long now = System.nanoTime();
        boolean empty = FULL.centerX == Integer.MIN_VALUE;
        boolean zoomChanged = zoom != FULL.zoomKey;
        double shiftX = empty ? 0 : (worldX - FULL.centerX) * zoom;
        double shiftZ = empty ? 0 : (worldZ - FULL.centerZ) * zoom;
        boolean beyondTexture = empty || zoomChanged ||
            Math.abs(shiftX) >= padding * 0.82 ||
            Math.abs(shiftZ) >= padding * 0.82;
        boolean drifted = !dragging &&
            (Math.abs(shiftX) >= padding * 0.52 ||
             Math.abs(shiftZ) >= padding * 0.52);
        boolean dataChanged = !dragging && FULL.revision != RoundMapAtlas.revision() &&
            (FULL.updatedAt == 0 || now - FULL.updatedAt >= 1_200_000_000L);

        if (fullBuild != null) {
            // A changed screen geometry or zoom invalidates a partial build.
            if (fullBuild.texW != tw || fullBuild.texH != th || fullBuild.zoom != zoom
                || Math.abs((worldX - fullBuild.centerX) * zoom) > padding * 1.75
                || Math.abs((worldZ - fullBuild.centerZ) * zoom) > padding * 1.75) {
                fullBuild = null;
            }
        }
        if (fullBuild == null && (beyondTexture || drifted || dataChanged)) {
            // On an atlas-content update keep the same anchor: the player and
            // markers cannot jump whenever newly explored chunks arrive.
            int anchorX = beyondTexture || drifted ? (int)Math.floor(worldX) : FULL.centerX;
            int anchorZ = beyondTexture || drifted ? (int)Math.floor(worldZ) : FULL.centerZ;
            fullBuild = new FullBuild(anchorX, anchorZ, zoom,
                RoundMapAtlas.revision(), drawW, tw, th);
        }
        if (fullBuild != null) {
            FullBuild build = fullBuild;
            double blocksPerPixel = build.drawW / (double)(build.texW * build.zoom);
            long started = System.nanoTime();
            // Cap both row count and CPU time so 200+ FPS hardware doesn't
            // suffer from a single long blocking map repaint.
            int endRow = Math.min(build.texH, build.nextRow + 16);
            for (int py = build.nextRow; py < endRow; py++) {
                int wz = build.centerZ +
                    (int)Math.floor((py + .5 - build.texH / 2.0) * blocksPerPixel);
                for (int px = 0; px < build.texW; px++) {
                    int wx = build.centerX +
                        (int)Math.floor((px + .5 - build.texW / 2.0) * blocksPerPixel);
                    FULL.pixels.setPixel(px, py, RoundMapFog.color(wx, wz));
                }
                build.nextRow = py + 1;
                if (System.nanoTime() - started > 2_500_000L) break;
            }
            if (build.nextRow >= build.texH) {
                FULL.texture.upload();
                FULL.centerX = build.centerX;
                FULL.centerZ = build.centerZ;
                FULL.zoomKey = build.zoom;
                FULL.revision = build.revision;
                FULL.updatedAt = System.nanoTime();
                fullBuild = null;
            }
        }

        // Light fog in uncovered regions rather than a dark blank texture.
        g.fill(0, 0, screenW, screenH,
            RoundMapClient.brightFog ? 0xFFF1F2F6 : 0xFF27303A);
        if (FULL.centerX != Integer.MIN_VALUE && zoom == FULL.zoomKey) {
            int offsetX = (int)Math.round((FULL.centerX - worldX) * zoom);
            int offsetZ = (int)Math.round((FULL.centerZ - worldZ) * zoom);
            g.blit(RenderPipelines.GUI_TEXTURED, FULL.id,
                -padding + offsetX, -padding + offsetZ, 0f, 0f,
                drawW, drawH, tw, th, tw, th);
        }
    }

    private RoundMapTextures() {}
}

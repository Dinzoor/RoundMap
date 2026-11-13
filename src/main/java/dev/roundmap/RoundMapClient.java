package dev.roundmap;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.lwjgl.glfw.GLFW;

/** Lightweight client-only north-up map. No entity scanning. */
public final class RoundMapClient implements ClientModInitializer {
    private static final int MINI_RADIUS = 48;
    private static final int GRID = 33;
    private static final int BLOCKS_PER_CELL = 4;
    private static final int EMPTY = 0xFF303A43;
    private static final int[][] colors = new int[GRID][GRID];
    private static final List<Marker> markers = new ArrayList<>();
    private static boolean expanded = false;
    private static boolean lastM = false;
    private static boolean lastB = false;
    private static int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
    private static int scanRow = 0;
    private static Object lastWorld;

    private record Marker(int x, int z, String dimension, int number) {}

    @Override public void onInitializeClient() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath("roundmap", "minimap"),
            (graphics, tickCounter) -> draw(graphics));
    }

    private static void draw(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) {
            lastM = false;
            lastB = false;
            return;
        }

        long window = mc.getWindow().getWindow();
        boolean m = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_M) == GLFW.GLFW_PRESS;
        boolean b = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_B) == GLFW.GLFW_PRESS;
        if (m && !lastM && mc.screen == null) expanded = !expanded;
        if (b && !lastB && mc.screen == null) {
            markers.add(new Marker(mc.player.getBlockX(), mc.player.getBlockZ(),
                mc.level.dimension().toString(), markers.size() + 1));
        }
        lastM = m;
        lastB = b;

        int px = mc.player.getBlockX();
        int pz = mc.player.getBlockZ();
        if (lastWorld != mc.level || Math.abs(px - lastX) >= BLOCKS_PER_CELL ||
            Math.abs(pz - lastZ) >= BLOCKS_PER_CELL) {
            lastWorld = mc.level;
            lastX = px;
            lastZ = pz;
            scanRow = 0;
            for (int z = 0; z < GRID; z++)
                for (int x = 0; x < GRID; x++) colors[z][x] = EMPTY;
        }
        // Scan at most two rows per frame instead of thousands of blocks each frame.
        for (int n = 0; n < 2 && scanRow < GRID; n++, scanRow++) {
            int dz = scanRow - GRID / 2;
            for (int x = 0; x < GRID; x++) {
                int dx = x - GRID / 2;
                BlockPos sample = new BlockPos(lastX + dx * BLOCKS_PER_CELL, 0,
                    lastZ + dz * BLOCKS_PER_CELL);
                int color = EMPTY;
                if (mc.level.hasChunkAt(sample)) {
                    BlockPos ground = mc.level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, sample).below();
                    BlockState state = mc.level.getBlockState(ground);
                    MapColor map = state.getMapColor(mc.level, ground);
                    if (map != MapColor.NONE) color = 0xFF000000 | map.col;
                }
                colors[scanRow][x] = color;
            }
        }

        if (expanded) drawLarge(g, mc, px, pz);
        else drawMini(g, mc, px, pz);
    }

    private static void drawMini(GuiGraphics g, Minecraft mc, int px, int pz) {
        int cx = g.guiWidth() - MINI_RADIUS - 12;
        int cy = MINI_RADIUS + 17;
        int cell = 3;
        for (int z = 0; z < GRID; z++) {
            int y = (z - GRID / 2) * cell;
            for (int x = 0; x < GRID; x++) {
                int xx = (x - GRID / 2) * cell;
                if (xx * xx + y * y > (MINI_RADIUS - 2) * (MINI_RADIUS - 2)) continue;
                g.fill(cx + xx, cy + y, cx + xx + cell, cy + y + cell, colors[z][x]);
            }
        }
        circle(g, cx, cy, MINI_RADIUS);
        drawMarkers(g, mc, px, pz, cx, cy, 3, MINI_RADIUS);
        pointer(g, mc, cx, cy);
        g.drawCenteredString(mc.font, "N", cx, cy - MINI_RADIUS - 12, -1);
        g.drawCenteredString(mc.font, px + ", " + mc.player.getBlockY() + ", " + pz,
            cx, cy + MINI_RADIUS + 9, -1);
    }

    private static void drawLarge(GuiGraphics g, Minecraft mc, int px, int pz) {
        int cx = g.guiWidth() / 2;
        int cy = g.guiHeight() / 2;
        int cell = Math.max(3, Math.min(10, (Math.min(g.guiWidth(), g.guiHeight()) - 45) / GRID));
        int half = GRID * cell / 2;
        g.fill(cx - half - 6, cy - half - 22, cx + half + 6, cy + half + 24, 0xE51B202A);
        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                int xx = cx + (x - GRID / 2) * cell;
                int yy = cy + (z - GRID / 2) * cell;
                g.fill(xx, yy, xx + cell, yy + cell, colors[z][x]);
            }
        }
        drawMarkers(g, mc, px, pz, cx, cy, cell, half);
        pointer(g, mc, cx, cy);
        g.drawCenteredString(mc.font, "RoundMap  |  M: close  |  B: mark position",
            cx, cy - half - 17, -1);
        g.drawCenteredString(mc.font, "X " + px + "  Y " + mc.player.getBlockY() + "  Z " + pz,
            cx, cy + half + 8, -1);
    }

    private static void drawMarkers(GuiGraphics g, Minecraft mc, int px, int pz,
                                    int cx, int cy, int cell, int radius) {
        String dimension = mc.level.dimension().toString();
        for (Marker marker : markers) {
            if (!marker.dimension().equals(dimension)) continue;
            int dx = Math.round((marker.x() - lastX) * cell / (float)BLOCKS_PER_CELL);
            int dz = Math.round((marker.z() - lastZ) * cell / (float)BLOCKS_PER_CELL);
            if (Math.abs(dx) > radius - 5 || Math.abs(dz) > radius - 5) continue;
            if (!expanded && dx * dx + dz * dz > (radius - 5) * (radius - 5)) continue;
            g.fill(cx + dx - 2, cy + dz - 2, cx + dx + 3, cy + dz + 3, 0xFFFFD640);
            g.drawCenteredString(mc.font, Integer.toString(marker.number()),
                cx + dx, cy + dz - 13, -1);
        }
    }

    private static void pointer(GuiGraphics g, Minecraft mc, int cx, int cy) {
        g.fill(cx - 2, cy - 2, cx + 3, cy + 3, 0xFFFFFFFF);
        double yaw = Math.toRadians(mc.player.getYRot());
        int x = cx - (int)Math.round(Math.sin(yaw) * 9);
        int y = cy + (int)Math.round(Math.cos(yaw) * 9);
        g.fill(x - 2, y - 2, x + 3, y + 3, 0xFFFF5555);
    }

    private static void circle(GuiGraphics g, int cx, int cy, int radius) {
        for (int angle = 0; angle < 360; angle += 2) {
            double rad = Math.toRadians(angle);
            int x = cx + (int)Math.round(radius * Math.cos(rad));
            int y = cy + (int)Math.round(radius * Math.sin(rad));
            g.fill(x, y, x + 1, y + 1, 0xFFFFFFFF);
        }
    }
}

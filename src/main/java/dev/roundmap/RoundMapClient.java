package dev.roundmap;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.resources.ResourceLocation;

/** Small, north-up circular surface map. Never scans entities. */
public final class RoundMapClient implements ClientModInitializer {
    private static final int RADIUS = 49;
    private static final int SCALE = 2; // world blocks per pixel
    private static final int BACKGROUND = 0xD91B202A;

    @Override public void onInitializeClient() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
            ResourceLocation.fromNamespaceAndPath("roundmap", "minimap"),
            (graphics, tickCounter) -> draw(graphics));
    }

    private static void draw(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) return;
        int cx = g.guiWidth() - RADIUS - 12;
        int cy = RADIUS + 18;
        int px = mc.player.getBlockX();
        int pz = mc.player.getBlockZ();
        // Render 1px horizontal runs rather than individual pixel draw calls.
        for (int dy = -RADIUS; dy <= RADIUS; dy++) {
            int extent = (int)Math.sqrt(RADIUS * RADIUS - dy * dy);
            for (int dx = -extent; dx <= extent; dx++) {
                int color = BACKGROUND;
                int wx = px + dx * SCALE;
                int wz = pz + dy * SCALE;
                BlockPos sample = new BlockPos(wx, 0, wz);
                if (mc.level.hasChunkAt(sample)) {
                    BlockPos top = mc.level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, sample);
                    BlockPos ground = top.below();
                    BlockState state = mc.level.getBlockState(ground);
                    MapColor mapColor = state.getMapColor(mc.level, ground);
                    color = 0xFF000000 | mapColor.col;
                    if (mapColor == MapColor.NONE) color = 0xFF525B65;
                }
                g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
            }
        }
        // Circle outline, cardinal marks and player direction.
        for (int angle = 0; angle < 360; angle++) {
            double a = Math.toRadians(angle);
            int x = cx + (int)Math.round(RADIUS * Math.cos(a));
            int y = cy + (int)Math.round(RADIUS * Math.sin(a));
            g.fill(x, y, x + 1, y + 1, 0xFFF1F1F1);
        }
        g.drawCenteredString(mc.font, "N", cx, cy - RADIUS - 12, 0xFFFFFFFF);
        g.drawCenteredString(mc.font, "S", cx, cy + RADIUS + 3, 0xFFFFFFFF);
        g.drawString(mc.font, "W", cx - RADIUS - 10, cy - 4, 0xFFFFFFFF);
        g.drawString(mc.font, "E", cx + RADIUS + 3, cy - 4, 0xFFFFFFFF);
        double yaw = Math.toRadians(mc.player.getYRot());
        int tx = cx - (int)Math.round(Math.sin(yaw) * 8);
        int ty = cy + (int)Math.round(Math.cos(yaw) * 8);
        g.fill(cx - 2, cy - 2, cx + 3, cy + 3, 0xFF000000);
        g.fill(cx - 1, cy - 1, cx + 2, cy + 2, 0xFFFFFFFF);
        g.fill(tx - 2, ty - 2, tx + 3, ty + 3, 0xFFFF4444);
        String coords = px + ", " + mc.player.getBlockY() + ", " + pz;
        g.drawCenteredString(mc.font, coords, cx, cy + RADIUS + 15, 0xFFFFFFFF);
    }
}

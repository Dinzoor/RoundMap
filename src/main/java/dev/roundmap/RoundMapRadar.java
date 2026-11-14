package dev.roundmap;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;

/** Small opt-in radar markers, refreshed at most four times per second. */
final class RoundMapRadar {
    static boolean players = true, hostile = false, passive = false, neutral = false, items = false;
    static boolean onMini = true, onFull = false;
    private static final List<Dot> dots = new ArrayList<>();
    private static Object lastWorld;
    private static long nextUpdate;
    private record Dot(int x, int z, int color) {}

    private static void update(Minecraft mc) {
        if (mc.level != lastWorld) {
            lastWorld = mc.level;
            dots.clear();
            nextUpdate = 0;
        }
        if (mc.level == null || mc.player == null) return;
        long now = System.nanoTime();
        if (now < nextUpdate) return;
        nextUpdate = now + 250_000_000L;
        dots.clear();
        if (!players && !hostile && !passive && !neutral && !items) return;
        int count = 0;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == mc.player) continue;
            if (Math.abs(e.getX() - mc.player.getX()) > 256 ||
                Math.abs(e.getZ() - mc.player.getZ()) > 256) continue;
            int color = 0;
            if (e instanceof Player) { if (players) color = 0xFF00E6FF; }
            else if (e instanceof Monster) { if (hostile) color = 0xFFFF5353; }
            else if (e instanceof Animal) { if (passive) color = 0xFF65EE77; }
            else if (e instanceof Mob) { if (neutral) color = 0xFFFFD564; }
            else if (e instanceof ItemEntity) { if (items) color = 0xFFEEEEEE; }
            if (color != 0) {
                dots.add(new Dot(e.getBlockX(), e.getBlockZ(), color));
                if (++count >= 128) break;
            }
        }
    }

    static void draw(GuiGraphics g, Minecraft mc, int centerX, int centerY,
                     double worldX, double worldZ, float scale,
                     int rx, int ry, boolean circular) {
        if ((circular && !onMini) || (!circular && !onFull)) return;
        update(mc);
        for (Dot dot : dots) {
            int dx = (int)Math.round((dot.x - worldX) * scale);
            int dz = (int)Math.round((dot.z - worldZ) * scale);
            if (circular) {
                if (dx * dx + dz * dz > (rx - 3) * (rx - 3)) continue;
            } else if (Math.abs(dx) > rx - 3 || Math.abs(dz) > ry - 3) continue;
            g.fill(centerX + dx - 1, centerY + dz - 1,
                centerX + dx + 2, centerY + dz + 2, dot.color);
        }
    }

    private RoundMapRadar() {}
}

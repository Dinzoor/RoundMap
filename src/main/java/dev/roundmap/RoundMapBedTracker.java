package dev.roundmap;

import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BedBlock;

/**
 * Finds a nearby bed from already-loaded client blocks, without requesting
 * chunks or asking the server for private respawn data. Reports each bed once.
 * This identifies a nearby bed, NOT necessarily the player's spawn bed.
 */
final class RoundMapBedTracker {
    private static final int RADIUS = 5;
    private static final int SIDE = RADIUS * 2 + 1;
    private static final int LAYERS = 5;
    private static final int TOTAL = SIDE * SIDE * LAYERS;
    private static Object world;
    private static int bx, by, bz, index = TOTAL;

    static void tick(Minecraft mc, Consumer<BlockPos> found) {
        if (mc.level == null || mc.player == null) {
            world = null;
            index = TOTAL;
            return;
        }
        int x = mc.player.getBlockX(), y = mc.player.getBlockY(),
            z = mc.player.getBlockZ();
        if (world != mc.level ||
            Math.abs(x - bx) > RADIUS || Math.abs(z - bz) > RADIUS ||
            Math.abs(y - by) > 2) {
            world = mc.level;
            bx = x; by = y; bz = z;
            index = 0;
        }
        if (index >= TOTAL) return;
        for (int count = 0; count < 64 && index < TOTAL; count++, index++) {
            int layer = index / (SIDE * SIDE);
            int local = index % (SIDE * SIDE);
            BlockPos pos = new BlockPos(bx + local % SIDE - RADIUS,
                by + layer - 2, bz + local / SIDE - RADIUS);
            if (!mc.level.hasChunkAt(pos)) continue;
            if (mc.level.getBlockState(pos).getBlock() instanceof BedBlock) {
                found.accept(pos);
                index = TOTAL;
                break;
            }
        }
    }

    private RoundMapBedTracker() {}
}

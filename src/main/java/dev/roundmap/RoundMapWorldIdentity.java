package dev.roundmap;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Unique, stable storage identity. Displayed world names are NOT unique:
 * two different saves can share the same LevelName.
 */
final class RoundMapWorldIdentity {
    // Never write the old name-based atlas into a new world. Its contents may
    // already be mixed with terrain from multiple identically named saves.
    private static final String STORAGE_VERSION = "v2";
    private static final String SESSION_ID = UUID.randomUUID().toString();

    static String key(Minecraft mc) {
        if (mc.level == null) return "";
        String source;
        if (mc.getSingleplayerServer() != null) {
            Path root = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT)
                .toAbsolutePath().normalize();
            source = "single:" + root;
        } else if (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null) {
            source = "multiplayer:" + mc.getCurrentServer().ip.trim().toLowerCase(java.util.Locale.ROOT);
        } else {
            // Unknown connections stay distinct during the current session,
            // and cannot accidentally load another world's saved terrain.
            source = "unresolved:" + SESSION_ID + ":" + System.identityHashCode(mc.level);
        }
        return source + "|dimension:" + mc.level.dimension().toString();
    }

    private static String hashed(String key) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by Java", e);
        }
    }

    static Path atlasPath(String key) {
        return FabricLoader.getInstance().getConfigDir()
            .resolve("roundmap").resolve("explored").resolve(STORAGE_VERSION)
            .resolve(hashed(key) + ".dat.gz");
    }

    static Path waypointPath(String key) {
        return FabricLoader.getInstance().getConfigDir()
            .resolve("roundmap").resolve("waypoints").resolve(STORAGE_VERSION)
            .resolve(hashed(key) + ".txt");
    }

    private RoundMapWorldIdentity() {}
}

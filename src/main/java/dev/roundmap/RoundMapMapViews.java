package dev.roundmap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import net.minecraft.client.Minecraft;

/**
 * Named independent camera views over the world-specific explored atlas.
 * Views do not duplicate megabytes of map pixels.
 */
final class RoundMapMapViews {
    record View(String name, int x, int z, int zoom) {}
    private static final List<View> VIEWS = new ArrayList<>();
    private static String worldKey = "";
    private static Path file;

    static List<View> all() { return Collections.unmodifiableList(VIEWS); }

    static void sync(Minecraft mc) {
        String current = RoundMapWorldIdentity.key(mc);
        if (current.equals(worldKey)) return;
        worldKey = current;
        VIEWS.clear();
        file = current.isEmpty() ? null : RoundMapWorldIdentity.mapViewsPath(current);
        if (file == null || !Files.isRegularFile(file)) return;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String[] p = line.split(";", 4);
                if (p.length != 4) continue;
                try {
                    String name = new String(Base64.getDecoder().decode(p[0]), StandardCharsets.UTF_8);
                    int x = Integer.parseInt(p[1]), z = Integer.parseInt(p[2]);
                    int zoom = Math.max(1, Math.min(10, Integer.parseInt(p[3])));
                    if (!name.isBlank() && VIEWS.size() < 64)
                        VIEWS.add(new View(name, x, z, zoom));
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (IOException e) { System.err.println("[RoundMap] Cannot read map views: " + e); }
    }

    static void add(String name, int x, int z, int zoom) {
        if (name.isBlank() || VIEWS.size() >= 64) return;
        VIEWS.add(new View(name.trim(), x, z, zoom));
        save();
    }

    static void remove(View view) {
        VIEWS.remove(view);
        save();
    }

    private static void save() {
        if (file == null) return;
        List<String> lines = new ArrayList<>();
        for (View v : VIEWS) {
            lines.add(Base64.getEncoder().encodeToString(
                v.name().getBytes(StandardCharsets.UTF_8)) + ";" +
                v.x() + ";" + v.z() + ";" + v.zoom());
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException e) { System.err.println("[RoundMap] Cannot write map views: " + e); }
    }
    private RoundMapMapViews() {}
}

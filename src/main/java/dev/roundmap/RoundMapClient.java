package dev.roundmap;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.Base64;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import org.lwjgl.glfw.GLFW;

/** Client-only terrain map. Samples a bounded amount of terrain per frame. */
public final class RoundMapClient implements ClientModInitializer {
    private static final int GRID = 97, HALF = GRID / 2, EMPTY = 0xFF29313A;
    private static final int[][] terrain = new int[GRID][GRID];
    private static final List<Marker> markers = new ArrayList<>();
    private static int centerX, centerZ, scan = GRID * GRID;
    private static long nextSampleAt;
    private static final int SAMPLE_BATCH = 3;
    private static final long SAMPLE_INTERVAL_NS = 100_000_000L;
    private static int lastPlayerX = Integer.MIN_VALUE, lastPlayerZ = Integer.MIN_VALUE;
    private static Object lastWorld;
    private static boolean lastM, lastB, lastO;
    private static boolean showMini = true, showLabels = true;
    private static int miniSize = 48, mapZoom = 3;
    private record Marker(int x, int z, String dimension, String name) {}

    private static final Path WAYPOINT_FILE = FabricLoader.getInstance().getConfigDir().resolve("roundmap-waypoints.txt");

    private static void loadMarkers() {
        markers.clear();
        if (!Files.isRegularFile(WAYPOINT_FILE)) return;
        try {
            for (String line : Files.readAllLines(WAYPOINT_FILE, StandardCharsets.UTF_8)) {
                String[] parts = line.split(";", 4);
                if (parts.length != 4) continue;
                try {
                    markers.add(new Marker(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                        new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8),
                        new String(Base64.getDecoder().decode(parts[3]), StandardCharsets.UTF_8)));
                } catch (IllegalArgumentException ignored) { }
            }
        } catch (IOException e) { System.err.println("[RoundMap] Cannot read waypoints: " + e); }
    }

    private static void saveMarkers() {
        List<String> lines = new ArrayList<>();
        for (Marker m : markers) lines.add(m.x() + ";" + m.z() + ";" +
            Base64.getEncoder().encodeToString(m.dimension().getBytes(StandardCharsets.UTF_8)) + ";" +
            Base64.getEncoder().encodeToString(m.name().getBytes(StandardCharsets.UTF_8)));
        try {
            Files.createDirectories(WAYPOINT_FILE.getParent());
            Files.write(WAYPOINT_FILE, lines, StandardCharsets.UTF_8);
        } catch (IOException e) { System.err.println("[RoundMap] Cannot save waypoints: " + e); }
    }

    @Override public void onInitializeClient() {
        loadMarkers();
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
            Identifier.fromNamespaceAndPath("roundmap", "minimap"),
            (graphics, tickCounter) -> hud(graphics));
    }

    private static void recenter(Minecraft mc, int x, int z) {
        if (lastWorld == mc.level && Math.abs(x - centerX) < 12 && Math.abs(z - centerZ) < 12) return;
        lastWorld = mc.level;
        centerX = x;
        centerZ = z;
        scan = 0;
        nextSampleAt = 0;
        for (int row = 0; row < GRID; row++)
            for (int col = 0; col < GRID; col++) terrain[row][col] = EMPTY;
    }

    private static void sample(Minecraft mc) {
        // Terrain heightmap reads can be very expensive on modded servers.
        // Keep a tiny fixed workload per 100ms instead of stalling a render frame.
        long now = System.nanoTime();
        if (now < nextSampleAt) return;
        nextSampleAt = now + SAMPLE_INTERVAL_NS;
        for (int n = 0; n < SAMPLE_BATCH && scan < GRID * GRID; n++, scan++) {
            int row = scan / GRID, col = scan % GRID;
            int x = centerX + col - HALF, z = centerZ + row - HALF;
            BlockPos pos = new BlockPos(x, 0, z);
            if (!mc.level.hasChunkAt(pos)) continue;
            BlockPos ground = mc.level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, pos).below();
            BlockState state = mc.level.getBlockState(ground);
            MapColor map = state.getMapColor(mc.level, ground);
            if (map == MapColor.NONE) continue;
            int rgb = map.col;
            int shade = Math.max(-26, Math.min(26, (ground.getY() - 70) / 3));
            int r = Math.max(0, Math.min(255, ((rgb >> 16) & 255) + shade));
            int g = Math.max(0, Math.min(255, ((rgb >> 8) & 255) + shade));
            int b = Math.max(0, Math.min(255, (rgb & 255) + shade));
            terrain[row][col] = 0xFF000000 | r << 16 | g << 8 | b;
        }
    }

    private static void hud(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        long window = mc.getWindow().handle();
        boolean m = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_M) == GLFW.GLFW_PRESS;
        boolean b = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_B) == GLFW.GLFW_PRESS;
        boolean o = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_O) == GLFW.GLFW_PRESS;
        if (mc.screen == null) {
            if (m && !lastM) mc.setScreen(new MapScreen());
            else if (b && !lastB) mc.setScreen(new NameScreen(mc.player.getBlockX(),
                mc.player.getBlockZ(), mc.level.dimension().toString()));
            else if (o && !lastO) mc.setScreen(new OptionsScreen());
        }
        lastM = m; lastB = b; lastO = o;
        int px = mc.player.getBlockX(), pz = mc.player.getBlockZ();
        if (lastWorld != mc.level || lastPlayerX == Integer.MIN_VALUE ||
            Math.abs(px - lastPlayerX) >= 24 || Math.abs(pz - lastPlayerZ) >= 24) {
            lastPlayerX = px;
            lastPlayerZ = pz;
            recenter(mc, px, pz);
        }
        if (showMini && !mc.options.hideGui) sample(mc);
        if (!showMini || mc.options.hideGui) return;
        int cx = g.guiWidth() - miniSize - 12, cy = miniSize + 18;
        // Draw horizontal color runs instead of one GPU rectangle per pixel.
        for (int y = -miniSize; y <= miniSize; y += 2) {
            int gy = HALF + y * HALF / miniSize;
            if (gy < 0 || gy >= GRID) continue;
            int extent = (int)Math.sqrt(miniSize * miniSize - y * y);
            int runStart = -extent;
            int lastColor = Integer.MIN_VALUE;
            for (int x = -extent; x <= extent + 2; x += 2) {
                int gx = HALF + x * HALF / miniSize;
                int color = x > extent || gx < 0 || gx >= GRID ? Integer.MIN_VALUE : terrain[gy][gx];
                if (color != lastColor) {
                    if (lastColor != Integer.MIN_VALUE)
                        g.fill(cx + runStart, cy + y, cx + x, cy + y + 2, lastColor);
                    runStart = x;
                    lastColor = color;
                }
            }
        }
        outline(g, cx, cy, miniSize);
        drawMarkers(g, mc, cx, cy, miniSize / (float)HALF, miniSize, true);
        pointer(g, mc, cx, cy);
        g.drawCenteredString(mc.font, "N", cx, cy - miniSize - 11, -1);
        g.drawCenteredString(mc.font, "M: map  B: waypoint  O: options", cx, cy + miniSize + 8, -1);
    }

    private static void drawMarkers(GuiGraphics g, Minecraft mc, int cx, int cy, float scale, int radius, boolean circle) {
        for (Marker marker : markers) {
            if (!marker.dimension().equals(mc.level.dimension().toString())) continue;
            int dx = Math.round((marker.x() - centerX) * scale);
            int dz = Math.round((marker.z() - centerZ) * scale);
            if (Math.abs(dx) >= radius - 4 || Math.abs(dz) >= radius - 4) continue;
            if (circle && dx * dx + dz * dz >= (radius - 4) * (radius - 4)) continue;
            g.fill(cx + dx - 2, cy + dz - 2, cx + dx + 3, cy + dz + 3, 0xFFFFD640);
            if (showLabels) g.drawCenteredString(mc.font, marker.name(), cx + dx, cy + dz - 14, -1);
        }
    }

    private static void pointer(GuiGraphics g, Minecraft mc, int cx, int cy) {
        g.fill(cx - 2, cy - 2, cx + 3, cy + 3, -1);
        double yaw = Math.toRadians(mc.player.getYRot());
        int x = cx - (int)Math.round(Math.sin(yaw) * 9);
        int y = cy + (int)Math.round(Math.cos(yaw) * 9);
        g.fill(x - 2, y - 2, x + 3, y + 3, 0xFFFF5555);
    }

    private static void outline(GuiGraphics g, int cx, int cy, int radius) {
        for (int a = 0; a < 360; a += 2) {
            double rad = Math.toRadians(a);
            int x = cx + (int)Math.round(radius * Math.cos(rad));
            int y = cy + (int)Math.round(radius * Math.sin(rad));
            g.fill(x, y, x + 1, y + 1, -1);
        }
    }

    private static final class MapScreen extends Screen {
        private double panX, panZ, oldX, oldY;
        private boolean wasDragging, lastClose, lastPlus, lastMinus;
        private MapScreen() { super(Component.literal("RoundMap")); lastClose = true; }
        @Override protected void init() {
            addRenderableWidget(Button.builder(Component.literal("Settings"), b ->
                minecraft.setScreen(new OptionsScreen())).bounds(width - 95, 8, 85, 20).build());
        }
        @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return;
            long handle = mc.getWindow().handle();
            boolean dragging = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            if (dragging && wasDragging && oldY > 35 && mouseY > 35) {
                panX -= (mouseX - oldX) / (double)mapZoom;
                panZ -= (mouseY - oldY) / (double)mapZoom;
            }
            wasDragging = dragging;
            oldX = mouseX; oldY = mouseY;
            int targetX = mc.player.getBlockX() + (int)Math.round(panX);
            int targetZ = mc.player.getBlockZ() + (int)Math.round(panZ);
            recenter(mc, targetX, targetZ);
            sample(mc);
            g.fill(0, 0, width, height, 0xEF171C23);
            int cx = width / 2, cy = height / 2;
            int visibleX = Math.min(HALF, (width / 2) / mapZoom);
            int visibleY = Math.min(HALF, (height / 2) / mapZoom);
            for (int y = -visibleY; y <= visibleY; y++) {
                int runStart = -visibleX;
                int lastColor = Integer.MIN_VALUE;
                for (int x = -visibleX; x <= visibleX + 1; x++) {
                    int color = x > visibleX ? Integer.MIN_VALUE : terrain[HALF + y][HALF + x];
                    if (color != lastColor) {
                        if (lastColor != Integer.MIN_VALUE) {
                            int xx = cx + runStart * mapZoom;
                            int yy = cy + y * mapZoom;
                            g.fill(xx, yy, cx + x * mapZoom, yy + mapZoom, lastColor);
                        }
                        runStart = x;
                        lastColor = color;
                    }
                }
            }
            drawMarkers(g, mc, cx, cy, mapZoom, Math.min(width, height) / 2, false);
            int playerX = cx + Math.round((mc.player.getBlockX() - centerX) * mapZoom);
            int playerZ = cy + Math.round((mc.player.getBlockZ() - centerZ) * mapZoom);
            g.fill(playerX - 3, playerZ - 3, playerX + 4, playerZ + 4, 0xFFFF5555);
            g.drawCenteredString(font, "RoundMap | drag: pan | wheel or +/-: zoom | ESC: close", width / 2, 12, -1);
            g.drawCenteredString(font, "X " + targetX + "  Z " + targetZ, width / 2, height - 16, -1);
            super.render(g, mouseX, mouseY, delta);
            boolean close = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_M) == GLFW.GLFW_PRESS;
            if (close && !lastClose) minecraft.setScreen(null);
            lastClose = close;
            boolean plus = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_EQUAL) == GLFW.GLFW_PRESS;
            boolean minus = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_MINUS) == GLFW.GLFW_PRESS;
            if (plus && !lastPlus && mapZoom < 8) mapZoom++;
            if (minus && !lastMinus && mapZoom > 1) mapZoom--;
            lastPlus = plus;
            lastMinus = minus;
        }
        @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
            if (vertical > 0) mapZoom = Math.min(8, mapZoom + 1);
            if (vertical < 0) mapZoom = Math.max(1, mapZoom - 1);
            return true;
        }
    }

    private static final class OptionsScreen extends Screen {
        private OptionsScreen() { super(Component.literal("RoundMap settings")); }
        @Override protected void init() {
            int x = width / 2 - 105, y = height / 2 - 65;
            addRenderableWidget(Button.builder(Component.literal("Minimap: " + (showMini ? "ON" : "OFF")), b -> {
                showMini = !showMini; minecraft.setScreen(new OptionsScreen());
            }).bounds(x, y, 210, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Waypoint labels: " + (showLabels ? "ON" : "OFF")), b -> {
                showLabels = !showLabels; minecraft.setScreen(new OptionsScreen());
            }).bounds(x, y + 25, 210, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Minimap size: " + miniSize), b -> {
                miniSize = miniSize == 48 ? 36 : miniSize == 36 ? 60 : 48;
                minecraft.setScreen(new OptionsScreen());
            }).bounds(x, y + 50, 210, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Done"), b ->
                minecraft.setScreen(null)).bounds(x, y + 85, 210, 20).build());
        }
    }

    private static final class NameScreen extends Screen {
        private EditBox name;
        private final int x, z;
        private final String dimension;
        private NameScreen(int x, int z, String dimension) {
            super(Component.literal("New waypoint"));
            this.x = x; this.z = z; this.dimension = dimension;
        }
        @Override protected void init() {
            name = new EditBox(font, width / 2 - 100, height / 2 - 25, 200, 20,
                Component.literal("Waypoint name"));
            name.setMaxLength(40);
            name.setValue("Waypoint " + (markers.size() + 1));
            addRenderableWidget(name);
            setInitialFocus(name);
            addRenderableWidget(Button.builder(Component.literal("Save waypoint"), b -> {
                String label = name.getValue().trim();
                if (!label.isEmpty()) {
                    markers.add(new Marker(x, z, dimension, label));
                    saveMarkers();
                }
                minecraft.setScreen(null);
            }).bounds(width / 2 - 100, height / 2 + 5, 200, 20).build());
        }
    }
}

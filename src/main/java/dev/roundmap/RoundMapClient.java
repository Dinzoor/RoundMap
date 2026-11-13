package dev.roundmap;

import java.util.ArrayList;
import java.util.List;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.Base64;
import java.util.Properties;
import java.io.InputStream;
import java.io.OutputStream;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
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
    private static KeyMapping mapKey, waypointKey, optionsKey, toggleKey, zoomInKey, zoomOutKey, listKey;
    private static final KeyMapping.Category KEY_CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("roundmap", "controls"));
    private static boolean showMini = true, showLabels = true, showCoords = true, showCompass = true, showWorldMarkers = true;
    static int miniOpacity = 100;
    static boolean brightFog = true;
    private static int miniSize = 48, mapZoom = 3;
    private static double miniZoom = 1.0;
    private static double miniX = -1, miniY = -1;
    private static final Path POSITION_FILE = FabricLoader.getInstance().getConfigDir().resolve("roundmap-position.properties");

    private static void loadPosition() {
        if (!Files.isRegularFile(POSITION_FILE)) return;
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(POSITION_FILE)) {
            p.load(in);
            miniX = Double.parseDouble(p.getProperty("x", "-1"));
            miniY = Double.parseDouble(p.getProperty("y", "-1"));
            miniSize = Integer.parseInt(p.getProperty("size", "48"));
            miniZoom = Double.parseDouble(p.getProperty("zoom", "1.0"));
            if (!Double.isFinite(miniZoom)) miniZoom = 1.0;
            miniZoom = Math.max(1.0, Math.min(4.0, miniZoom));
            miniSize = Math.max(24, Math.min(120, miniSize));
            showMini = Boolean.parseBoolean(p.getProperty("visible", "true"));
            showCoords = Boolean.parseBoolean(p.getProperty("coords", "true"));
            showCompass = Boolean.parseBoolean(p.getProperty("compass", "true"));
            showLabels = Boolean.parseBoolean(p.getProperty("labels", "true"));
            showWorldMarkers = Boolean.parseBoolean(p.getProperty("worldMarkers", "true"));
            miniOpacity = Math.max(25, Math.min(100, Integer.parseInt(p.getProperty("opacity", "100"))));
            brightFog = Boolean.parseBoolean(p.getProperty("brightFog", "true"));
            RoundMapAtlas.readsPerTick = Math.max(2, Math.min(16, Integer.parseInt(p.getProperty("scanBudget", "8"))));
            RoundMapAtlas.detailed = Boolean.parseBoolean(p.getProperty("detailed", "true"));
            RoundMapRadar.players = Boolean.parseBoolean(p.getProperty("radarPlayers", "true"));
            RoundMapRadar.hostile = Boolean.parseBoolean(p.getProperty("radarHostile", "false"));
            RoundMapRadar.passive = Boolean.parseBoolean(p.getProperty("radarPassive", "false"));
            RoundMapRadar.neutral = Boolean.parseBoolean(p.getProperty("radarNeutral", "false"));
            RoundMapRadar.items = Boolean.parseBoolean(p.getProperty("radarItems", "false"));
            RoundMapRadar.onMini = Boolean.parseBoolean(p.getProperty("radarMini", "true"));
            RoundMapRadar.onFull = Boolean.parseBoolean(p.getProperty("radarFull", "false"));
            if (!Double.isFinite(miniX) || !Double.isFinite(miniY)) { miniX = -1; miniY = -1; }
        } catch (IOException | IllegalArgumentException e) { System.err.println("[RoundMap] Cannot load position: " + e); }
    }

    private static void savePosition() {
        Properties p = new Properties();
        p.setProperty("x", Double.toString(miniX));
        p.setProperty("y", Double.toString(miniY));
        p.setProperty("size", Integer.toString(miniSize));
        p.setProperty("zoom", Double.toString(miniZoom));
        p.setProperty("visible", Boolean.toString(showMini));
        p.setProperty("coords", Boolean.toString(showCoords));
        p.setProperty("compass", Boolean.toString(showCompass));
        p.setProperty("labels", Boolean.toString(showLabels));
        p.setProperty("worldMarkers", Boolean.toString(showWorldMarkers));
        p.setProperty("opacity", Integer.toString(miniOpacity));
        p.setProperty("brightFog", Boolean.toString(brightFog));
        p.setProperty("scanBudget", Integer.toString(RoundMapAtlas.readsPerTick));
        p.setProperty("detailed", Boolean.toString(RoundMapAtlas.detailed));
        p.setProperty("radarPlayers", Boolean.toString(RoundMapRadar.players));
        p.setProperty("radarHostile", Boolean.toString(RoundMapRadar.hostile));
        p.setProperty("radarPassive", Boolean.toString(RoundMapRadar.passive));
        p.setProperty("radarNeutral", Boolean.toString(RoundMapRadar.neutral));
        p.setProperty("radarItems", Boolean.toString(RoundMapRadar.items));
        p.setProperty("radarMini", Boolean.toString(RoundMapRadar.onMini));
        p.setProperty("radarFull", Boolean.toString(RoundMapRadar.onFull));
        try {
            Files.createDirectories(POSITION_FILE.getParent());
            try (OutputStream out = Files.newOutputStream(POSITION_FILE)) { p.store(out, "RoundMap minimap center, normalized to screen"); }
        } catch (IOException e) { System.err.println("[RoundMap] Cannot save position: " + e); }
    }

    private static int miniCenterX(int width) {
        int r = Math.max(0, Math.min(miniSize + 12, width / 2));
        return Math.max(r, Math.min(width - r, miniX < 0 ? width - miniSize - 12 : (int)Math.round(miniX * width)));
    }
    private static int miniCenterY(int height) {
        int r = Math.max(0, Math.min(miniSize + 18, height / 2));
        return Math.max(r, Math.min(height - r, miniY < 0 ? miniSize + 18 : (int)Math.round(miniY * height)));
    }
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
        mapKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.roundmap.full_map", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, KEY_CATEGORY));
        waypointKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.roundmap.waypoint", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, KEY_CATEGORY));
        optionsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.roundmap.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, KEY_CATEGORY));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.roundmap.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, KEY_CATEGORY));
        zoomInKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.roundmap.zoom_in", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, KEY_CATEGORY));
        zoomOutKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.roundmap.zoom_out", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, KEY_CATEGORY));
        listKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.roundmap.list", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, KEY_CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            RoundMapAtlas.tick(client);
            if (client.player == null || client.level == null || client.screen != null) return;
            if (mapKey.consumeClick()) client.setScreen(new MapScreen());
            else if (waypointKey.consumeClick()) client.setScreen(new NameScreen(client.player.getBlockX(), client.player.getBlockZ(), client.level.dimension().toString()));
            else if (optionsKey.consumeClick()) client.setScreen(new OptionsScreen());
            else if (listKey.consumeClick()) client.setScreen(new WaypointListScreen(null, 0));
            else if (toggleKey.consumeClick()) { showMini = !showMini; savePosition(); }
            else if (zoomInKey.consumeClick()) { miniZoom = Math.min(4.0, miniZoom + 0.5); savePosition(); }
            else if (zoomOutKey.consumeClick()) { miniZoom = Math.max(1.0, miniZoom - 0.5); savePosition(); }
        });
        loadMarkers();
        loadPosition();
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof net.minecraft.client.gui.screens.options.OptionsScreen) {
                Screens.getButtons(screen).add(Button.builder(Component.literal("RoundMap settings"),
                    button -> client.setScreen(new OptionsScreen(screen)))
                    .bounds(Math.max(4, scaledWidth - 130), 6, 124, 20).build());
            }
        });
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
        if (mc.options.hideGui) return;
        if (showWorldMarkers && mc.screen == null) drawWorldMarkers(g, mc);
        if (showMini) drawMini(g, mc, miniCenterX(g.guiWidth()), miniCenterY(g.guiHeight()));
    }


    // Lightweight screen-space waypoint labels: no block scanning or extra world rendering.
    private static void drawWorldMarkers(GuiGraphics g, Minecraft mc) {
        double yaw = Math.toRadians(mc.player.getYRot());
        double forwardX = -Math.sin(yaw), forwardZ = Math.cos(yaw);
        double rightX = -forwardZ, rightZ = forwardX;
        int w = g.guiWidth(), h = g.guiHeight();
        for (Marker marker : markers) {
            if (!marker.dimension().equals(mc.level.dimension().toString())) continue;
            double dx = marker.x() + 0.5 - mc.player.getX();
            double dz = marker.z() + 0.5 - mc.player.getZ();
            double distance = Math.hypot(dx, dz);
            if (distance < 3 || distance > 1500) continue;
            double forward = dx * forwardX + dz * forwardZ;
            if (forward <= 0) continue;
            double side = dx * rightX + dz * rightZ;
            double angle = Math.atan2(side, forward);
            if (Math.abs(angle) > Math.toRadians(55)) continue;
            int sx = w / 2 + (int)Math.round(angle / Math.toRadians(55) * w * 0.43);
            int sy = h / 3;
            String label = marker.name() + " - " + (int)Math.round(distance) + " m";
            int tw = mc.font.width(label);
            g.fill(sx - tw / 2 - 5, sy - 16, sx + tw / 2 + 5, sy + 4, 0x990B111A);
            g.fill(sx - 3, sy - 22, sx + 4, sy - 15, 0xFFFFD640);
            g.drawCenteredString(mc.font, label, sx, sy - 12, 0xFFFFFFFF);
        }
    }

    private static void drawMini(GuiGraphics g, Minecraft mc, int cx, int cy) {
        RoundMapTextures.minimap(g, cx, cy, miniSize, miniZoom,
            mc.player.getBlockX(), mc.player.getBlockZ());
        outline(g, cx, cy, miniSize);
        RoundMapRadar.draw(g, mc, cx, cy, mc.player.getBlockX(), mc.player.getBlockZ(),
            (float)(miniSize * miniZoom / HALF), miniSize, miniSize, true);
        drawMarkers(g, mc, cx, cy, (float)(miniSize * miniZoom / HALF), miniSize,
            true, mc.player.getBlockX(), mc.player.getBlockZ());
        pointer(g, mc, cx, cy);
        if (showCompass) g.drawCenteredString(mc.font, "N", cx, cy - miniSize - 11, -1);
        if (showCoords) g.drawCenteredString(mc.font,
            "X: " + mc.player.getBlockX() + " Z: " + mc.player.getBlockZ(),
            cx, cy + miniSize + 8, -1);
    }

    private static void drawMarkers(GuiGraphics g, Minecraft mc, int cx, int cy,
            float scale, int radius, boolean circle, int worldX, int worldZ) {
        for (Marker marker : markers) {
            if (!marker.dimension().equals(mc.level.dimension().toString())) continue;
            int dx = Math.round((marker.x() - worldX) * scale);
            int dz = Math.round((marker.z() - worldZ) * scale);
            int limitX = circle ? radius - 4 : g.guiWidth() / 2 - 4;
            int limitY = circle ? radius - 4 : g.guiHeight() / 2 - 4;
            if (Math.abs(dx) >= limitX || Math.abs(dz) >= limitY) continue;
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
        private boolean wasDragging, wasRight, lastPlus, lastMinus;
        private MapScreen() { super(Component.literal("RoundMap")); }
        private MapScreen(int x, int z) {
            this();
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                panX = x - mc.player.getBlockX();
                panZ = z - mc.player.getBlockZ();
            }
        }
        private void zoom(int amount) { mapZoom = Math.max(1, Math.min(10, mapZoom + amount)); }
        @Override protected void init() {
            int y = 8;
            addRenderableWidget(Button.builder(Component.literal("Точки"), b ->
                minecraft.setScreen(new WaypointListScreen(this, 0)))
                .bounds(width - 215, y, 96, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Настройки"), b ->
                minecraft.setScreen(new OptionsScreen(this)))
                .bounds(width - 113, y, 106, 20).build());
            int rx = width - 31, cy = height / 2;
            addRenderableWidget(Button.builder(Component.literal("+"), b -> zoom(1))
                .bounds(rx, cy - 55, 24, 24).build());
            addRenderableWidget(Button.builder(Component.literal("-"), b -> zoom(-1))
                .bounds(rx, cy - 25, 24, 24).build());
            addRenderableWidget(Button.builder(Component.literal("C"), b -> {
                panX = 0; panZ = 0;
            }).bounds(rx, cy + 5, 24, 24).build());
            addRenderableWidget(Button.builder(Component.literal("•"), b -> {
                int x = minecraft.player.getBlockX() + (int)Math.round(panX);
                int z = minecraft.player.getBlockZ() + (int)Math.round(panZ);
                minecraft.setScreen(new NameScreen(x, z, minecraft.level.dimension().toString(), this));
            }).bounds(rx, cy + 35, 24, 24).build());
        }
        @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) return;
            long handle = mc.getWindow().handle();
            boolean dragging = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            if (dragging && wasDragging && oldY > 35 && mouseY > 35 && mouseX < width - 38) {
                panX -= (mouseX - oldX) / (double)mapZoom;
                panZ -= (mouseY - oldY) / (double)mapZoom;
            }
            wasDragging = dragging;
            oldX = mouseX; oldY = mouseY;
            int targetX = mc.player.getBlockX() + (int)Math.round(panX);
            int targetZ = mc.player.getBlockZ() + (int)Math.round(panZ);
            RoundMapTextures.fullscreen(g, width, height, targetX, targetZ, mapZoom);
            int cx = width / 2, cy = height / 2;
            RoundMapRadar.draw(g, mc, cx, cy, targetX, targetZ,
                mapZoom, width / 2, height / 2, false);
            drawMarkers(g, mc, cx, cy, mapZoom, Math.max(width, height) / 2,
                false, targetX, targetZ);
            int playerX = cx + (int)Math.round((mc.player.getBlockX() - targetX) * mapZoom);
            int playerZ = cy + (int)Math.round((mc.player.getBlockZ() - targetZ) * mapZoom);
            if (playerX > 0 && playerX < width && playerZ > 34 && playerZ < height)
                g.fill(playerX - 3, playerZ - 3, playerX + 4, playerZ + 4, 0xFFFF5555);
            g.fill(0, 0, width, 35, 0xB018222C);
            g.drawString(font, "RoundMap  •  Изучено чанков: " + RoundMapAtlas.exploredChunks(),
                10, 12, 0xFFFFFFFF);
            g.fill(0, height - 25, width, height, 0xB018222C);
            int hoveredX = targetX + (int)Math.round((mouseX - cx) / (double)mapZoom);
            int hoveredZ = targetZ + (int)Math.round((mouseY - cy) / (double)mapZoom);
            g.drawString(font, "ЛКМ: сдвиг   Колесо: масштаб   ПКМ: добавить точку   M: закрыть",
                8, height - 17, 0xFFE3E6EB);
            g.drawString(font, "X " + hoveredX + " Z " + hoveredZ,
                Math.max(0, width - 140), height - 17, 0xFFFFFFFF);
            super.render(g, mouseX, mouseY, delta);
            boolean right = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
            if (right && !wasRight && mouseY > 35 && mouseY < height - 25 && mouseX < width - 38) {
                minecraft.setScreen(new NameScreen(hoveredX, hoveredZ, mc.level.dimension().toString(), this));
            }
            wasRight = right;
            boolean plus = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_EQUAL) == GLFW.GLFW_PRESS;
            boolean minus = GLFW.glfwGetKey(handle, GLFW.GLFW_KEY_MINUS) == GLFW.GLFW_PRESS;
            if (plus && !lastPlus) zoom(1);
            if (minus && !lastMinus) zoom(-1);
            lastPlus = plus; lastMinus = minus;
        }
        @Override public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
            if (mapKey.matches(event)) { minecraft.setScreen(null); return true; }
            return super.keyPressed(event);
        }
        @Override public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
            if (vertical > 0) zoom(1);
            if (vertical < 0) zoom(-1);
            return true;
        }
    }

    private static final class OptionsScreen extends Screen {
        private static final String[] TABS = {"Мини-карта", "Радар", "Оверлеи", "Точки", "Вид"};
        private final Screen parent;
        private final int tab;
        private OptionsScreen() { this(null, 0); }
        private OptionsScreen(Screen parent) { this(parent, 0); }
        private OptionsScreen(Screen parent, int tab) {
            super(Component.literal("RoundMap settings"));
            this.parent = parent;
            this.tab = tab;
        }
        private void refresh() { minecraft.setScreen(new OptionsScreen(parent, tab)); }
        private void addOption(int x, int y, int w, String label, Runnable action) {
            addRenderableWidget(Button.builder(Component.literal(label), b -> { action.run(); refresh(); })
                .bounds(x, y, w, 20).build());
        }
        @Override protected void init() {
            int tabWidth = Math.min(142, Math.max(68, (width - 20) / 5));
            int left = (width - tabWidth * 5) / 2;
            for (int i = 0; i < TABS.length; i++) {
                final int selected = i;
                addRenderableWidget(Button.builder(Component.literal((i == tab ? "• " : "") + TABS[i]),
                    b -> minecraft.setScreen(new OptionsScreen(parent, selected)))
                    .bounds(left + i * tabWidth, 12, tabWidth - 3, 20).build());
            }
            int w = Math.min(280, width - 24), x = (width - w) / 2;
            int y = Math.max(44, height / 2 - 66);
            if (tab == 0) {
                addOption(x, y, w, "Мини-карта: " + (showMini ? "Вкл" : "Выкл"), () -> { showMini = !showMini; savePosition(); });
                addOption(x, y + 25, w, "Размер мини-карты: " + miniSize, () ->
                    { miniSize = miniSize == 48 ? 36 : miniSize == 36 ? 60 : 48; savePosition(); });
                addOption(x, y + 50, w, "Координаты: " + (showCoords ? "Вкл" : "Выкл"), () -> { showCoords = !showCoords; savePosition(); });
                addOption(x, y + 75, w, "Компас: " + (showCompass ? "Вкл" : "Выкл"), () -> { showCompass = !showCompass; savePosition(); });
                addOption(x, y + 100, w, "Масштаб местности: " + (int)(miniZoom * 100) + "%", () -> { miniZoom = miniZoom >= 4.0 ? 1.0 : miniZoom + 0.5; savePosition(); });
                addOption(x, y + 125, w, "Прозрачность: " + miniOpacity + "%", () -> {
                    miniOpacity = miniOpacity <= 25 ? 100 : miniOpacity - 25;
                    RoundMapTextures.invalidate(); savePosition();
                });
                addRenderableWidget(Button.builder(Component.literal("Переместить мини-карту мышью"),
                    b -> minecraft.setScreen(new PositionScreen(new OptionsScreen(parent, tab))))
                    .bounds(x, y + 150, w, 20).build());
            } else if (tab == 1) {
                addOption(x, y, w, "На мини-карте: " + (RoundMapRadar.onMini ? "Вкл" : "Выкл"), () -> {
                    RoundMapRadar.onMini = !RoundMapRadar.onMini; savePosition(); });
                addOption(x, y + 25, w, "На большой карте: " + (RoundMapRadar.onFull ? "Вкл" : "Выкл"), () -> {
                    RoundMapRadar.onFull = !RoundMapRadar.onFull; savePosition(); });
                addOption(x, y + 50, w, "Игроки: " + (RoundMapRadar.players ? "Вкл" : "Выкл"), () -> {
                    RoundMapRadar.players = !RoundMapRadar.players; savePosition(); });
                addOption(x, y + 75, w, "Враждебные мобы: " + (RoundMapRadar.hostile ? "Вкл" : "Выкл"), () -> {
                    RoundMapRadar.hostile = !RoundMapRadar.hostile; savePosition(); });
                addOption(x, y + 100, w, "Мирные мобы: " + (RoundMapRadar.passive ? "Вкл" : "Выкл"), () -> {
                    RoundMapRadar.passive = !RoundMapRadar.passive; savePosition(); });
                addOption(x, y + 125, w, "Нейтральные мобы: " + (RoundMapRadar.neutral ? "Вкл" : "Выкл"), () -> {
                    RoundMapRadar.neutral = !RoundMapRadar.neutral; savePosition(); });
                addOption(x, y + 150, w, "Предметы: " + (RoundMapRadar.items ? "Вкл" : "Выкл"), () -> {
                    RoundMapRadar.items = !RoundMapRadar.items; savePosition(); });
            } else if (tab == 2) {
                addOption(x, y, w, "Туман войны: " + (brightFog ? "Светлый" : "Тёмный"), () -> {
                    brightFog = !brightFog; RoundMapTextures.invalidate(); savePosition(); });
            } else if (tab == 4) {
                addOption(x, y, w, "Детализация: " + (RoundMapAtlas.detailed ? "Подробно" : "Быстро"), () -> {
                    RoundMapAtlas.detailed = !RoundMapAtlas.detailed;
                    RoundMapAtlas.forceRefresh(); savePosition(); });
                addOption(x, y + 25, w, "Скорость исследования: " + RoundMapAtlas.readsPerTick + " блоков/тик", () -> {
                    RoundMapAtlas.readsPerTick = RoundMapAtlas.readsPerTick >= 16 ? 2 :
                        RoundMapAtlas.readsPerTick == 2 ? 4 : RoundMapAtlas.readsPerTick == 4 ? 8 : 16;
                    savePosition(); });
            } else if (tab == 3) {
                addOption(x, y, w, "Названия точек: " + (showLabels ? "Вкл" : "Выкл"),
                    () -> { showLabels = !showLabels; savePosition(); });
                addRenderableWidget(Button.builder(Component.literal("Создать точку у игрока"), b -> {
                    if (minecraft.player != null && minecraft.level != null)
                        minecraft.setScreen(new NameScreen(minecraft.player.getBlockX(),
                            minecraft.player.getBlockZ(), minecraft.level.dimension().toString()));
                }).bounds(x, y + 25, w, 20).build());
                addOption(x, y + 50, w, "Метки в мире: " + (showWorldMarkers ? "Вкл" : "Выкл"), () -> { showWorldMarkers = !showWorldMarkers; savePosition(); });
                addRenderableWidget(Button.builder(Component.literal("Список точек"), b ->
                    minecraft.setScreen(new WaypointListScreen(new OptionsScreen(parent, tab), 0)))
                    .bounds(x, y + 75, w, 20).build());
            }
            addRenderableWidget(Button.builder(Component.literal("Готово"), b ->
                minecraft.setScreen(parent)).bounds(width / 2 - 80, height - 30, 160, 20).build());
        }
        @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            super.render(g, mouseX, mouseY, delta);
            if (tab == 2)
                g.drawCenteredString(font, "Неисследованные территории остаются скрытыми", width / 2, height / 2, 0xFFCCCCCC);
        }
    }


    private static final class WaypointListScreen extends Screen {
        private final Screen parent;
        private final int page;
        private WaypointListScreen(Screen parent, int page) {
            super(Component.literal("RoundMap waypoints"));
            this.parent = parent;
            this.page = Math.max(0, page);
        }
        @Override protected void init() {
            int x = width / 2 - 150, y = 44;
            int perPage = Math.max(1, Math.min(8, (height - 110) / 26));
            int start = page * perPage;
            for (int i = start; i < Math.min(markers.size(), start + perPage); i++) {
                final Marker marker = markers.get(i);
                final int index = i;
                int row = i - start;
                addRenderableWidget(Button.builder(Component.literal(marker.name() + " [" + marker.x() + ", " + marker.z() + "]"),
                    b -> minecraft.setScreen(new MapScreen(marker.x(), marker.z())))
                    .bounds(x, y + row * 26, 245, 20).build());
                addRenderableWidget(Button.builder(Component.literal("X"), b -> {
                    markers.remove(marker);
                    saveMarkers();
                    minecraft.setScreen(new WaypointListScreen(parent, Math.min(page, Math.max(0, (markers.size()-1)/perPage))));
                }).bounds(x + 250, y + row * 26, 40, 20).build());
            }
            if (page > 0) addRenderableWidget(Button.builder(Component.literal("<"), b ->
                minecraft.setScreen(new WaypointListScreen(parent, page - 1)))
                .bounds(width / 2 - 105, height - 57, 90, 20).build());
            if (start + perPage < markers.size()) addRenderableWidget(Button.builder(Component.literal(">"), b ->
                minecraft.setScreen(new WaypointListScreen(parent, page + 1)))
                .bounds(width / 2 + 15, height - 57, 90, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Готово"), b ->
                minecraft.setScreen(parent)).bounds(width / 2 - 80, height - 30, 160, 20).build());
        }
        @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            super.render(g, mouseX, mouseY, delta);
            g.drawCenteredString(font, "Сохранённые точки: " + markers.size(), width / 2, 20, -1);
        }
    }

    private static final class PositionScreen extends Screen {
        private final Screen returnScreen;
        private final double originalX, originalY;
        private int centerPx, centerPy;
        private boolean dragging, wasDown;
        private PositionScreen(Screen returnScreen) {
            super(Component.literal("Move RoundMap minimap"));
            this.returnScreen = returnScreen;
            this.originalX = miniX;
            this.originalY = miniY;
        }
        @Override protected void init() {
            centerPx = miniCenterX(width);
            centerPy = miniCenterY(height);
            addRenderableWidget(Button.builder(Component.literal("Save position"), b -> {
                miniX = centerPx / (double)Math.max(1, width);
                miniY = centerPy / (double)Math.max(1, height);
                savePosition();
                minecraft.setScreen(returnScreen);
            }).bounds(width / 2 - 155, height - 30, 100, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Reset"), b -> {
                centerPx = Math.max(miniSize, width - miniSize - 12);
                centerPy = miniSize + 18;
                clamp();
            }).bounds(width / 2 - 50, height - 30, 100, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> {
                miniX = originalX; miniY = originalY;
                minecraft.setScreen(returnScreen);
            }).bounds(width / 2 + 55, height - 30, 100, 20).build());
        }
        private void clamp() {
            int marginX = Math.min(miniSize + 12, width / 2);
            int marginY = Math.min(miniSize + 18, height / 2);
            centerPx = Math.max(marginX, Math.min(width - marginX, centerPx));
            centerPy = Math.max(marginY, Math.min(height - marginY, centerPy));
        }
        @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            g.fill(0, 0, width, height, 0xC9202730);
            boolean down = GLFW.glfwGetMouseButton(minecraft.getWindow().handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
            if (down && !wasDown && Math.abs(mouseX - centerPx) <= miniSize + 8
                && Math.abs(mouseY - centerPy) <= miniSize + 8
                && (mouseX-centerPx)*(mouseX-centerPx)+(mouseY-centerPy)*(mouseY-centerPy) <= (miniSize+8)*(miniSize+8)) {
                dragging = true;
            }
            if (!down) dragging = false;
            if (dragging) { centerPx = mouseX; centerPy = mouseY; clamp(); }
            wasDown = down;
            if (minecraft.player != null && minecraft.level != null)
                drawMini(g, minecraft, centerPx, centerPy);
            else {
                g.fill(centerPx - miniSize, centerPy - miniSize, centerPx + miniSize, centerPy + miniSize, 0xFF425F77);
                outline(g, centerPx, centerPy, miniSize);
            }
            g.drawCenteredString(font, "Drag the round minimap with left mouse button", width / 2, 15, -1);
            g.drawCenteredString(font, "Save keeps the position after restarting Minecraft", width / 2, 29, 0xFFBBBBBB);
            super.render(g, mouseX, mouseY, delta);
        }
    }

    private static final class NameScreen extends Screen {
        private EditBox name;
        private final int x, z;
        private final String dimension;
        private final Screen parent;
        private NameScreen(int x, int z, String dimension) { this(x, z, dimension, null); }
        private NameScreen(int x, int z, String dimension, Screen parent) {
            super(Component.literal("New waypoint"));
            this.x = x; this.z = z; this.dimension = dimension;
            this.parent = parent;
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
                minecraft.setScreen(parent);
            }).bounds(width / 2 - 100, height / 2 + 5, 200, 20).build());
        }
    }
}

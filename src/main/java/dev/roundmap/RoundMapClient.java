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
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
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
    private static boolean showMini = true, showLabels = true, showCoords = true, showCompass = true, showWorldMarkers = true, showBiome = true;
    private static String biomeLabel = "";
    private static long biomeRefreshAt;
    static int miniOpacity = 100;
    private static int worldMarkerDistance = 1500;
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
            showBiome = Boolean.parseBoolean(p.getProperty("biome", "true"));
            showCompass = Boolean.parseBoolean(p.getProperty("compass", "true"));
            showLabels = Boolean.parseBoolean(p.getProperty("labels", "true"));
            showWorldMarkers = Boolean.parseBoolean(p.getProperty("worldMarkers", "true"));
            miniOpacity = Math.max(25, Math.min(100, Integer.parseInt(p.getProperty("opacity", "100"))));
            worldMarkerDistance = Math.max(64, Math.min(5000, Integer.parseInt(p.getProperty("worldMarkerDistance", "1500"))));
            brightFog = Boolean.parseBoolean(p.getProperty("brightFog", "true"));
            RoundMapFog.softEdges = Boolean.parseBoolean(p.getProperty("softFog", "true"));
            RoundMapAtlas.readsPerTick = Math.max(2, Math.min(16, Integer.parseInt(p.getProperty("scanBudget", "8"))));
            RoundMapAtlas.detailed = Boolean.parseBoolean(p.getProperty("detailed", "true"));
            RoundMapAtlas.contours = Boolean.parseBoolean(p.getProperty("contours", "true"));
            RoundMapAtlas.relief = Boolean.parseBoolean(p.getProperty("relief", "true"));
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
        p.setProperty("biome", Boolean.toString(showBiome));
        p.setProperty("compass", Boolean.toString(showCompass));
        p.setProperty("labels", Boolean.toString(showLabels));
        p.setProperty("worldMarkers", Boolean.toString(showWorldMarkers));
        p.setProperty("opacity", Integer.toString(miniOpacity));
        p.setProperty("worldMarkerDistance", Integer.toString(worldMarkerDistance));
        p.setProperty("brightFog", Boolean.toString(brightFog));
        p.setProperty("softFog", Boolean.toString(RoundMapFog.softEdges));
        p.setProperty("scanBudget", Integer.toString(RoundMapAtlas.readsPerTick));
        p.setProperty("detailed", Boolean.toString(RoundMapAtlas.detailed));
        p.setProperty("contours", Boolean.toString(RoundMapAtlas.contours));
        p.setProperty("relief", Boolean.toString(RoundMapAtlas.relief));
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
    private static final int[] MARKER_COLORS = {0xFFFFD640, 0xFFFF6666, 0xFF66CCFF, 0xFF77EE88, 0xFFCF88FF, 0xFFFFFFFF};
    private record Marker(int x, int y, int z, String dimension, String name, int color) {
        Marker(int x, int z, String dimension, String name) {
            this(x, 64, z, dimension, name, MARKER_COLORS[0]);
        }
    }

    // Previous global file is intentionally left untouched: it may contain
    // waypoints belonging to several different worlds.
    private static final Path LEGACY_WAYPOINT_FILE = FabricLoader.getInstance().getConfigDir()
        .resolve("roundmap-waypoints.txt");
    private static Path waypointFile;
    private static String markersWorldKey = "";

    private static void syncMarkers(Minecraft mc) {
        String newKey = RoundMapWorldIdentity.key(mc);
        if (markersWorldKey.equals(newKey)) return;
        markersWorldKey = newKey;
        markers.clear();
        waypointFile = newKey.isEmpty() ? null : RoundMapWorldIdentity.waypointPath(newKey);
        if (waypointFile != null) loadMarkers();
    }

    private static void loadMarkers() {
        markers.clear();
        if (waypointFile == null || !Files.isRegularFile(waypointFile)) return;
        try {
            for (String line : Files.readAllLines(waypointFile, StandardCharsets.UTF_8)) {
                String[] parts = line.split(";", 6);
                try {
                    if (parts.length == 4) {
                        // Legacy x;z;dimension;name records remain readable.
                        markers.add(new Marker(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                            new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8),
                            new String(Base64.getDecoder().decode(parts[3]), StandardCharsets.UTF_8)));
                    } else if (parts.length == 6) {
                        markers.add(new Marker(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                            Integer.parseInt(parts[2]),
                            new String(Base64.getDecoder().decode(parts[3]), StandardCharsets.UTF_8),
                            new String(Base64.getDecoder().decode(parts[4]), StandardCharsets.UTF_8),
                            (int)Long.parseLong(parts[5], 16)));
                    }
                } catch (IllegalArgumentException ignored) { }
            }
        } catch (IOException e) { System.err.println("[RoundMap] Cannot read waypoints: " + e); }
    }

    private static void saveMarkers() {
        if (waypointFile == null) return;
        List<String> lines = new ArrayList<>();
        for (Marker m : markers) lines.add(m.x() + ";" + m.y() + ";" + m.z() + ";" +
            Base64.getEncoder().encodeToString(m.dimension().getBytes(StandardCharsets.UTF_8)) + ";" +
            Base64.getEncoder().encodeToString(m.name().getBytes(StandardCharsets.UTF_8)) + ";" +
            Integer.toHexString(m.color()));
        try {
            Files.createDirectories(waypointFile.getParent());
            Files.write(waypointFile, lines, StandardCharsets.UTF_8);
        } catch (IOException e) { System.err.println("[RoundMap] Cannot save waypoints: " + e); }
    }

    @Override public void onInitializeClient() {
        // Read the active model textures, including server-downloaded packs.
        // Rebuild cached colors after a resource-pack refresh (F3+T).
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
            new SimpleSynchronousResourceReloadListener() {
                @Override public Identifier getFabricId() {
                    return Identifier.fromNamespaceAndPath("roundmap", "block_palette");
                }
                @Override public void reload(ResourceManager manager) {
                    RoundMapBlockPalette.clear();
                    Minecraft client = Minecraft.getInstance();
                    client.execute(() -> RoundMapAtlas.onResourcePackReload());
                }
            });
        mapKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.roundmap.full_map", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, KEY_CATEGORY));
        waypointKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.roundmap.waypoint", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, KEY_CATEGORY));
        optionsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.roundmap.settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, KEY_CATEGORY));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.roundmap.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, KEY_CATEGORY));
        zoomInKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.roundmap.zoom_in", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, KEY_CATEGORY));
        zoomOutKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.roundmap.zoom_out", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, KEY_CATEGORY));
        listKey = KeyBindingHelper.registerKeyBinding(new KeyMapping("key.roundmap.list", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, KEY_CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            syncMarkers(client);
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
        loadPosition();
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> RoundMapAtlas.shutdown());
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
        // No HUD minimap redraw behind the full-size map.
        if (mc.screen instanceof MapScreen || mc.options.hideGui) return;
        if (showWorldMarkers && mc.screen == null) drawWorldMarkers(g, mc);
        if (showMini) drawMini(g, mc, miniCenterX(g.guiWidth()), miniCenterY(g.guiHeight()));
    }


    // Lightweight screen-space waypoint labels: no block scanning or extra world rendering.
    /**
     * Project named world markers using horizontal bearing, camera pitch and
     * elevation. It is a lightweight directional overlay, not a 3-D beam.
     */
    private static void drawWorldMarkers(GuiGraphics g, Minecraft mc) {
        double yaw = Math.toRadians(mc.player.getYRot());
        double pitch = Math.toRadians(mc.player.getXRot());
        double forwardX = -Math.sin(yaw), forwardZ = Math.cos(yaw);
        double rightX = forwardZ, rightZ = -forwardX;
        int w = g.guiWidth(), h = g.guiHeight();
        for (Marker marker : markers) {
            if (!marker.dimension().equals(mc.level.dimension().toString())) continue;
            double dx = marker.x() + 0.5 - mc.player.getX();
            double dy = marker.y() + 0.5 - (mc.player.getY() + 1.62);
            double dz = marker.z() + 0.5 - mc.player.getZ();
            double distance2d = Math.hypot(dx, dz);
            double distance = Math.hypot(distance2d, dy);
            if (distance < 3 || distance > worldMarkerDistance) continue;
            double forward = dx * forwardX + dz * forwardZ;
            if (forward <= 0) continue;
            double side = dx * rightX + dz * rightZ;
            double angle = Math.atan2(side, forward);
            if (Math.abs(angle) > Math.toRadians(55)) continue;
            int sx = w / 2 + (int)Math.round(angle / Math.toRadians(55) * w * 0.43);
            double elevation = Math.atan2(dy, distance2d);
            int sy = h / 2 - (int)Math.round((elevation + pitch) / Math.toRadians(55) * h * 0.5);
            if (sy < 40 || sy > h - 38) continue;
            String label = marker.name() + " - " + (int)Math.round(distance) + " m";
            int tw = mc.font.width(label);
            g.fill(sx - tw / 2 - 5, sy - 16, sx + tw / 2 + 5, sy + 4, 0x990B111A);
            g.fill(sx - 3, sy - 22, sx + 4, sy - 15, marker.color());
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
        playerArrow(g, mc, cx, cy);
        if (showCompass) g.drawCenteredString(mc.font, "N", cx, cy - miniSize - 11, -1);
        if (showCoords) g.drawCenteredString(mc.font,
            "X: " + mc.player.getBlockX() + " Z: " + mc.player.getBlockZ(),
            cx, cy + miniSize + 8, -1);
        if (showBiome) {
            long now = System.nanoTime();
            if (now > biomeRefreshAt) {
                biomeRefreshAt = now + 1_000_000_000L;
                biomeLabel = mc.level.getBiome(mc.player.blockPosition()).unwrapKey()
                    .map(k -> k.identifier().getPath().replace('_', ' ')).orElse("unknown");
            }
            g.drawCenteredString(mc.font, "Биом: " + biomeLabel,
                cx, cy + miniSize + (showCoords ? 19 : 8), 0xFFFFFFFF);
        }
    }

    private static void drawMarkers(GuiGraphics g, Minecraft mc, int cx, int cy,
            float scale, int radius, boolean circle, double worldX, double worldZ) {
        for (Marker marker : markers) {
            if (!marker.dimension().equals(mc.level.dimension().toString())) continue;
            int dx = (int)Math.round((marker.x() - worldX) * scale);
            int dz = (int)Math.round((marker.z() - worldZ) * scale);
            int limitX = circle ? radius - 4 : g.guiWidth() / 2 - 4;
            int limitY = circle ? radius - 4 : g.guiHeight() / 2 - 4;
            if (Math.abs(dx) >= limitX || Math.abs(dz) >= limitY) continue;
            if (circle && dx * dx + dz * dz >= (radius - 4) * (radius - 4)) continue;
            g.fill(cx + dx - 2, cy + dz - 2, cx + dx + 3, cy + dz + 3, marker.color());
            if (showLabels) g.drawCenteredString(mc.font, marker.name(), cx + dx, cy + dz - 14, -1);
        }
    }

    /**
     * Draw a north-up player arrow on both map views. Minecraft yaw=0 faces
     * south (+Z); screen maps +Z downward.
     */
    /**
     * High-contrast navigator arrow inspired by the familiar white map
     * pointer: black shadow, red rim and white center with a notched tail.
     * It is drawn from our own geometry, with no external art assets.
     */
    private static void playerArrow(GuiGraphics g, Minecraft mc, int cx, int cy) {
        double yaw = Math.toRadians(mc.player.getYRot());
        double fx = -Math.sin(yaw), fy = Math.cos(yaw);
        double sx = fy, sy = -fx;
        // Balanced size: twice the tiny alpha.6 pointer, half its former size.
        arrowShape(g, cx + 1, cy + 1, fx, fy, sx, sy, 0.625, 0xFF131923);
        arrowShape(g, cx, cy, fx, fy, sx, sy, 0.57, 0xFF151619);
        arrowShape(g, cx, cy, fx, fy, sx, sy, 0.50, 0xFFFF4848);
        arrowShape(g, cx, cy, fx, fy, sx, sy, 0.365, 0xFFFFFFFF);
    }

    private static void arrowShape(GuiGraphics g, int cx, int cy,
            double fx, double fy, double sx, double sy, double scale, int color) {
        // Arrow tip, right wing, notched tail, left wing.
        double[] p = {12, -8, -3, -8};
        double[] q = {0, 7, 0, -7};
        int[] px = new int[4], py = new int[4];
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int i = 0; i < 4; i++) {
            px[i] = cx + (int)Math.round((fx * p[i] + sx * q[i]) * scale);
            py[i] = cy + (int)Math.round((fy * p[i] + sy * q[i]) * scale);
            minX = Math.min(minX, px[i]); maxX = Math.max(maxX, px[i]);
            minY = Math.min(minY, py[i]); maxY = Math.max(maxY, py[i]);
        }
        for (int y = minY; y <= maxY; y++) {
            int runStart = Integer.MIN_VALUE;
            for (int x = minX; x <= maxX + 1; x++) {
                boolean inside = false;
                if (x <= maxX) {
                    double testX = x + 0.5, testY = y + 0.5;
                    for (int i = 0, j = 3; i < 4; j = i++) {
                        if ((py[i] > testY) != (py[j] > testY)) {
                            double crossing = px[i] + (testY - py[i]) * (px[j] - px[i])
                                / (double)(py[j] - py[i]);
                            if (testX < crossing) inside = !inside;
                        }
                    }
                }
                if (inside && runStart == Integer.MIN_VALUE) runStart = x;
                if (!inside && runStart != Integer.MIN_VALUE) {
                    g.fill(runStart, y, x, y + 1, color);
                    runStart = Integer.MIN_VALUE;
                }
            }
        }
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
        // Keep client ticks running in singleplayer while the map is open so
        // nearby rendered chunks can continue to appear on the atlas.
        @Override public boolean isPauseScreen() { return false; }
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
            // Keep fractional pan positions for pixel-by-pixel smooth movement.
            double targetX = mc.player.getBlockX() + panX;
            double targetZ = mc.player.getBlockZ() + panZ;
            RoundMapTextures.fullscreen(g, width, height, targetX, targetZ, mapZoom, dragging);
            int cx = width / 2, cy = height / 2;
            RoundMapRadar.draw(g, mc, cx, cy,
                (int)Math.round(targetX), (int)Math.round(targetZ),
                mapZoom, width / 2, height / 2, false);
            drawMarkers(g, mc, cx, cy, mapZoom, Math.max(width, height) / 2,
                false, targetX, targetZ);
            int playerX = cx + (int)Math.round((mc.player.getBlockX() - targetX) * mapZoom);
            int playerZ = cy + (int)Math.round((mc.player.getBlockZ() - targetZ) * mapZoom);
            if (playerX >= 12 && playerX < width - 12 &&
                playerZ >= 47 && playerZ < height - 37)
                playerArrow(g, mc, playerX, playerZ);
            g.fill(0, 0, width, 35, 0xB018222C);
            g.drawString(font, "RoundMap  •  Изучено чанков: " + RoundMapAtlas.exploredChunks(),
                10, 12, 0xFFFFFFFF);
            g.fill(0, height - 25, width, height, 0xB018222C);
            int hoveredX = (int)Math.round(targetX + (mouseX - cx) / (double)mapZoom);
            int hoveredZ = (int)Math.round(targetZ + (mouseY - cy) / (double)mapZoom);
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
        private void option(int x, int y, int w, String label, Runnable action) {
            addRenderableWidget(Button.builder(Component.literal(label), b -> {
                action.run();
                refresh();
            }).bounds(x, y, w, 20).build());
        }
        private void link(int x, int y, int w, String label, Runnable action) {
            addRenderableWidget(Button.builder(Component.literal(label), b -> action.run())
                .bounds(x, y, w, 20).build());
        }
        @Override protected void init() {
            int tabWidth = Math.min(142, Math.max(62, (width - 20) / 5));
            int tabLeft = (width - tabWidth * 5) / 2;
            for (int i = 0; i < TABS.length; i++) {
                final int selected = i;
                addRenderableWidget(Button.builder(Component.literal((i == tab ? "• " : "") + TABS[i]),
                    b -> minecraft.setScreen(new OptionsScreen(parent, selected)))
                    .bounds(tabLeft + i * tabWidth, 9, tabWidth - 3, 20).build());
            }
            int cw = Math.min(220, Math.max(90, (width - 36) / 2));
            int left = width / 2 - cw - 5, right = width / 2 + 5;
            int top = 43, step = 26;
            if (tab == 0) {
                option(left, top, cw, "Мини-карта: " + (showMini ? "Вкл" : "Выкл"),
                    () -> { showMini = !showMini; savePosition(); });
                option(left, top + step, cw, "Координаты: " + (showCoords ? "Вкл" : "Выкл"),
                    () -> { showCoords = !showCoords; savePosition(); });
                option(left, top + 2 * step, cw, "Компас: " + (showCompass ? "Вкл" : "Выкл"),
                    () -> { showCompass = !showCompass; savePosition(); });
                option(left, top + 3 * step, cw, "Размер круга: " + miniSize,
                    () -> { miniSize = miniSize == 36 ? 48 : miniSize == 48 ? 60 : 36; savePosition(); });
                option(right, top, cw, "Масштаб: " + (int)(miniZoom * 100) + "%",
                    () -> { miniZoom = miniZoom >= 4.0 ? 1.0 : miniZoom + 0.5; savePosition(); });
                option(right, top + step, cw, "Непрозрачность: " + miniOpacity + "%",
                    () -> { miniOpacity = miniOpacity <= 25 ? 100 : miniOpacity - 25; RoundMapTextures.invalidate(); savePosition(); });
                option(right, top + 3 * step, cw, "Биом: " + (showBiome ? "Вкл" : "Выкл"),
                    () -> { showBiome = !showBiome; savePosition(); });
                link(right, top + 2 * step, cw, "Переместить мышью",
                    () -> minecraft.setScreen(new PositionScreen(new OptionsScreen(parent, tab))));
            } else if (tab == 1) {
                option(left, top, cw, "На мини-карте: " + (RoundMapRadar.onMini ? "Вкл" : "Выкл"),
                    () -> { RoundMapRadar.onMini = !RoundMapRadar.onMini; savePosition(); });
                option(left, top + step, cw, "На большой карте: " + (RoundMapRadar.onFull ? "Вкл" : "Выкл"),
                    () -> { RoundMapRadar.onFull = !RoundMapRadar.onFull; savePosition(); });
                option(left, top + 2 * step, cw, "Игроки: " + (RoundMapRadar.players ? "Вкл" : "Выкл"),
                    () -> { RoundMapRadar.players = !RoundMapRadar.players; savePosition(); });
                option(left, top + 3 * step, cw, "Враждебные: " + (RoundMapRadar.hostile ? "Вкл" : "Выкл"),
                    () -> { RoundMapRadar.hostile = !RoundMapRadar.hostile; savePosition(); });
                option(right, top, cw, "Мирные мобы: " + (RoundMapRadar.passive ? "Вкл" : "Выкл"),
                    () -> { RoundMapRadar.passive = !RoundMapRadar.passive; savePosition(); });
                option(right, top + step, cw, "Нейтральные: " + (RoundMapRadar.neutral ? "Вкл" : "Выкл"),
                    () -> { RoundMapRadar.neutral = !RoundMapRadar.neutral; savePosition(); });
                option(right, top + 2 * step, cw, "Предметы: " + (RoundMapRadar.items ? "Вкл" : "Выкл"),
                    () -> { RoundMapRadar.items = !RoundMapRadar.items; savePosition(); });
            } else if (tab == 2) {
                option(left, top, cw, "Туман войны: " + (brightFog ? "Светлый" : "Тёмный"),
                    () -> { brightFog = !brightFog; RoundMapTextures.invalidate(); savePosition(); });
                option(right, top, cw, "Края тумана: " + (RoundMapFog.softEdges ? "Мягкие" : "Простые"),
                    () -> { RoundMapFog.softEdges = !RoundMapFog.softEdges;
                        RoundMapTextures.invalidate(); savePosition(); });
                option(left, top + step, cw, "Тени рельефа: " + (RoundMapAtlas.relief ? "Вкл" : "Выкл"),
                    () -> { RoundMapAtlas.relief = !RoundMapAtlas.relief;
                        RoundMapTextures.invalidate(); savePosition(); });
                option(right, top + step, cw, "Контуры высот: " + (RoundMapAtlas.contours ? "Вкл" : "Выкл"),
                    () -> { RoundMapAtlas.contours = !RoundMapAtlas.contours;
                        RoundMapTextures.invalidate(); savePosition(); });
            } else if (tab == 3) {
                option(left, top, cw, "Названия: " + (showLabels ? "Вкл" : "Выкл"),
                    () -> { showLabels = !showLabels; savePosition(); });
                option(left, top + step, cw, "Метки в мире: " + (showWorldMarkers ? "Вкл" : "Выкл"),
                    () -> { showWorldMarkers = !showWorldMarkers; savePosition(); });
                option(left, top + 2 * step, cw, "Дальность: " + worldMarkerDistance + " м",
                    () -> { worldMarkerDistance = worldMarkerDistance >= 1500 ? 256 :
                        worldMarkerDistance == 256 ? 512 : 1500; savePosition(); });
                link(right, top, cw, "Добавить точку у игрока", () -> {
                    if (minecraft.player != null && minecraft.level != null)
                        minecraft.setScreen(new NameScreen(minecraft.player.getBlockX(),
                            minecraft.player.getBlockZ(), minecraft.level.dimension().toString(), this));
                });
                link(right, top + step, cw, "Список точек",
                    () -> minecraft.setScreen(new WaypointListScreen(this, 0)));
            } else if (tab == 4) {
                option(left, top, cw, "Качество: " + (RoundMapAtlas.detailed ? "Детально" : "Быстро"),
                    () -> { RoundMapAtlas.detailed = !RoundMapAtlas.detailed; RoundMapAtlas.forceRefresh(); savePosition(); });
                option(left, top + step, cw, "Сканирование: " + RoundMapAtlas.readsPerTick + " / тик",
                    () -> { RoundMapAtlas.readsPerTick = RoundMapAtlas.readsPerTick >= 16 ? 2 :
                        RoundMapAtlas.readsPerTick == 2 ? 4 : RoundMapAtlas.readsPerTick == 4 ? 8 : 16;
                        savePosition(); });
            }
            addRenderableWidget(Button.builder(Component.literal("Готово"), b ->
                minecraft.setScreen(parent)).bounds(width / 2 - 80, height - 30, 160, 20).build());
        }
        @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            g.fill(0, 0, width, height, 0xC0161C29);
            super.render(g, mouseX, mouseY, delta);
            if (tab == 2)
                g.drawCenteredString(font, "Белым или тёмным цветом скрыты неизученные участки",
                    width / 2, height / 2, 0xFFCCCCCC);
            if (tab == 4)
                g.drawCenteredString(font, "Для высокого FPS выбери Быстро и 2-4 блока/тик",
                    width / 2, height / 2 + 25, 0xFFCCCCCC);
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
                addRenderableWidget(Button.builder(Component.literal(marker.name() + " [" + marker.x() + ", " + marker.y() + ", " + marker.z() + "]"),
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
        private EditBox name, altitude;
        private final int x, z;
        private final String dimension;
        private final Screen parent;
        private int colorIndex = 0;
        private NameScreen(int x, int z, String dimension) { this(x, z, dimension, null); }
        private NameScreen(int x, int z, String dimension, Screen parent) {
            super(Component.literal("Новая точка"));
            this.x = x; this.z = z; this.dimension = dimension;
            this.parent = parent;
        }
        @Override protected void init() {
            int left = width / 2 - 120, top = height / 2 - 57;
            name = new EditBox(font, left, top, 240, 20, Component.literal("Название"));
            name.setMaxLength(40);
            name.setValue("Точка " + (markers.size() + 1));
            addRenderableWidget(name);
            setInitialFocus(name);
            altitude = new EditBox(font, left, top + 26, 110, 20, Component.literal("Высота Y"));
            altitude.setMaxLength(6);
            altitude.setValue(Integer.toString(minecraft.player == null ? 64 : minecraft.player.getBlockY()));
            addRenderableWidget(altitude);
            addRenderableWidget(Button.builder(Component.literal("Цвет точки"), b -> {
                colorIndex = (colorIndex + 1) % MARKER_COLORS.length;
            }).bounds(left + 120, top + 26, 120, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Сохранить точку"), b -> {
                String label = name.getValue().trim();
                if (!label.isEmpty()) {
                    int y = minecraft.player == null ? 64 : minecraft.player.getBlockY();
                    try { y = Integer.parseInt(altitude.getValue().trim()); }
                    catch (NumberFormatException ignored) { }
                    markers.add(new Marker(x, y, z, dimension, label, MARKER_COLORS[colorIndex]));
                    saveMarkers();
                }
                minecraft.setScreen(parent);
            }).bounds(left, top + 54, 240, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Отмена"), b ->
                minecraft.setScreen(parent)).bounds(left, top + 80, 240, 20).build());
        }
        @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            super.render(g, mouseX, mouseY, delta);
            int left = width / 2 - 120, top = height / 2 - 57;
            g.fill(left + 224, top + 29, left + 237, top + 43, MARKER_COLORS[colorIndex]);
            g.drawCenteredString(font, "X " + x + " Z " + z, width / 2, top - 16, 0xFFFFFFFF);
        }
    }
}

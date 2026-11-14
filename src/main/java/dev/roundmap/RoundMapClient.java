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
    // Pixel-art grid in world blocks. Larger steps produce crisp, larger
    // squares on both the circular minimap and the fullscreen map.
    static int mapPixelSize = 2;
    static boolean caveMode;
    static boolean caveScreenActive() {
        return caveMode && Minecraft.getInstance().screen instanceof MapScreen;
    }
    private static final int[] ARROW_COLORS = {0xFFFF4848, 0xFF4BC9FF, 0xFFFFD54D, 0xFFFFFFFF};
    private static final String[] ARROW_COLOR_NAMES = {"Красный", "Голубой", "Жёлтый", "Белый"};
    private static int arrowColorIndex = 0;
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
            mapPixelSize = Math.max(1, Math.min(4,
                Integer.parseInt(p.getProperty("pixelSize", "2"))));
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
            int oldBudget = Integer.parseInt(p.getProperty("scanBudget", "128"));
            RoundMapAtlas.readsPerTick = Math.max(32, Math.min(256, oldBudget));
            // Earlier alpha saves capped sampling to 2-16 reads/tick, which
            // made large maps take minutes to appear. Migrate once.
            if (!p.getProperty("samplingFormat", "0").equals("2")) {
                RoundMapAtlas.readsPerTick = 128;
            }
            RoundMapAtlas.detailed = p.getProperty("samplingFormat", "0").equals("2")
                ? Boolean.parseBoolean(p.getProperty("detailed", "true")) : true;
            RoundMapAtlas.contours = Boolean.parseBoolean(p.getProperty("contours", "true"));
            RoundMapAtlas.relief = Boolean.parseBoolean(p.getProperty("relief", "true"));
            RoundMapAtlas.lighting = Boolean.parseBoolean(p.getProperty("lighting", "true"));
            RoundMapAtlas.timeMode = Math.max(0, Math.min(2,
                Integer.parseInt(p.getProperty("timeMode", "0"))));
            arrowColorIndex = Math.max(0, Math.min(ARROW_COLORS.length - 1,
                Integer.parseInt(p.getProperty("arrowColor", "0"))));
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
        p.setProperty("pixelSize", Integer.toString(mapPixelSize));
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
        p.setProperty("samplingFormat", "2");
        p.setProperty("detailed", Boolean.toString(RoundMapAtlas.detailed));
        p.setProperty("contours", Boolean.toString(RoundMapAtlas.contours));
        p.setProperty("relief", Boolean.toString(RoundMapAtlas.relief));
        p.setProperty("lighting", Boolean.toString(RoundMapAtlas.lighting));
        p.setProperty("timeMode", Integer.toString(RoundMapAtlas.timeMode));
        p.setProperty("arrowColor", Integer.toString(arrowColorIndex));
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
    private static final int[] MARKER_COLORS = {
        0xFF51CEC6, 0xFF57CCFF, 0xFF67A0FF, 0xFF9759DC,
        0xFFCE59C1, 0xFFF08DB1, 0xFFF35A59, 0xFFFF913B,
        0xFFFFD34B, 0xFF8FCF55, 0xFF4DBA81, 0xFFFFFFFF,
        0xFF989FA5, 0xFF3D5056, 0xFF7A4A30, 0xFF22323D
    };
    private static final String[] MARKER_ICONS = {
        "W", "H", "B", "P", "!", "X", "T", "C", "M", "S", "*", "?"
    };
    private static final String[] MARKER_ICON_NAMES = {
        "Буквы", "Дом", "Кровать", "Портал", "Важное", "Крест",
        "Инструменты", "Сундук", "Череп", "Звезда", "Особое", "Другое"
    };
    private static final List<String> folders = new ArrayList<>();
    private record Marker(int x, int y, int z, String dimension, String name,
                          int color, int icon, String initials, String folder,
                          boolean visible, boolean temporary) {
        Marker(int x, int y, int z, String dimension, String name, int color) {
            this(x, y, z, dimension, name, color, 0, "", "Основная", true, false);
        }
        Marker(int x, int z, String dimension, String name) {
            this(x, 64, z, dimension, name, MARKER_COLORS[0]);
        }
        Marker withVisible(boolean shown) {
            return new Marker(x, y, z, dimension, name, color, icon,
                initials, folder, shown, temporary);
        }
    }

    private static Path waypointFile;
    private static String markersWorldKey = "";

    private static String encoded(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private static String decoded(String value) {
        return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static void syncMarkers(Minecraft mc) {
        String key = RoundMapWorldIdentity.key(mc);
        if (markersWorldKey.equals(key)) return;
        markersWorldKey = key;
        markers.clear();
        folders.clear();
        folders.add("Основная");
        waypointFile = key.isEmpty() ? null : RoundMapWorldIdentity.waypointPath(key);
        if (waypointFile != null) loadMarkers();
    }

    private static void loadMarkers() {
        markers.clear();
        if (waypointFile == null || !Files.isRegularFile(waypointFile)) return;
        try {
            for (String line : Files.readAllLines(waypointFile, StandardCharsets.UTF_8)) {
                String[] p = line.split(";", -1);
                try {
                    if (p.length == 2 && p[0].equals("F")) {
                        String folder = decoded(p[1]);
                        if (!folder.isBlank() && !folders.contains(folder)) folders.add(folder);
                    } else if (p.length == 4) {
                        markers.add(new Marker(Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                            decoded(p[2]), decoded(p[3])));
                    } else if (p.length == 6) {
                        markers.add(new Marker(Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                            Integer.parseInt(p[2]), decoded(p[3]), decoded(p[4]),
                            (int)Long.parseLong(p[5], 16)));
                    } else if (p.length == 10) {
                        String folder = decoded(p[8]);
                        if (!folders.contains(folder)) folders.add(folder);
                        markers.add(new Marker(Integer.parseInt(p[0]), Integer.parseInt(p[1]),
                            Integer.parseInt(p[2]), decoded(p[3]), decoded(p[4]),
                            (int)Long.parseLong(p[5], 16),
                            Math.floorMod(Integer.parseInt(p[6]), MARKER_ICONS.length),
                            decoded(p[7]), folder, Boolean.parseBoolean(p[9]), false));
                    }
                } catch (IllegalArgumentException ignored) {
                    System.err.println("[RoundMap] Ignored malformed waypoint entry");
                }
            }
        } catch (IOException e) { System.err.println("[RoundMap] Cannot read waypoints: " + e); }
    }

    private static void saveMarkers() {
        if (waypointFile == null) return;
        List<String> lines = new ArrayList<>();
        for (String folder : folders) lines.add("F;" + encoded(folder));
        for (Marker m : markers) {
            if (m.temporary()) continue;
            lines.add(m.x() + ";" + m.y() + ";" + m.z() + ";" +
                encoded(m.dimension()) + ";" + encoded(m.name()) + ";" +
                Integer.toHexString(m.color()) + ";" + m.icon() + ";" +
                encoded(m.initials()) + ";" + encoded(m.folder()) + ";" + m.visible());
        }
        try {
            Files.createDirectories(waypointFile.getParent());
            Files.write(waypointFile, lines, StandardCharsets.UTF_8);
        } catch (IOException e) { System.err.println("[RoundMap] Cannot save waypoints: " + e); }
    }

    private static String markerSymbol(Marker marker) {
        if (!marker.initials().isBlank()) return marker.initials();
        return MARKER_ICONS[marker.icon()];
    }

    private static void waypointPin(GuiGraphics g, Minecraft mc, int x, int y,
                                    Marker marker, boolean small) {
        int radius = small ? 6 : 9;
        int outline = 0xFF1D2931;
        // Original RoundMap pixel-art marker: circular head and a pin tail.
        for (int yy = -radius - 1; yy <= radius + 1; yy++) {
            int edge = (int)Math.sqrt(Math.max(0,
                (radius + 1) * (radius + 1) - yy * yy));
            g.fill(x - edge, y + yy, x + edge + 1, y + yy + 1, outline);
        }
        for (int yy = -radius + 1; yy <= radius - 1; yy++) {
            int edge = (int)Math.sqrt(Math.max(0,
                (radius - 1) * (radius - 1) - yy * yy));
            g.fill(x - edge, y + yy, x + edge + 1, y + yy + 1, marker.color());
        }
        int tail = small ? 4 : 5;
        for (int row = 0; row < tail; row++) {
            int edge = Math.max(1, 3 - row / 2);
            g.fill(x - edge, y + radius + row - 1, x + edge + 1,
                y + radius + row, outline);
            g.fill(x - 1, y + radius + row - 2, x + 2,
                y + radius + row - 1, marker.color());
        }
        if (!marker.initials().isEmpty()) {
            String initials = marker.initials();
            if (initials.length() > 2) initials = initials.substring(0, 2);
            g.drawCenteredString(mc.font, initials, x, y - 4, 0xFF14212B);
            return;
        }
        // Simple original icons drawn procedurally to avoid shipping
        // third-party sprite assets inside RoundMap.
        switch (marker.icon()) {
            case 1 -> { // house
                g.fill(x - 4, y, x + 5, y + 4, 0xFF203141);
                g.fill(x - 3, y - 2, x + 4, y, 0xFFF6F4EB);
                g.fill(x - 1, y + 1, x + 2, y + 4, 0xFFE9C45B);
            }
            case 2 -> { // bed
                g.fill(x - 5, y - 2, x + 5, y + 3, 0xFF1D2830);
                g.fill(x - 4, y - 1, x - 1, y + 2, 0xFFFFFFFF);
                g.fill(x - 1, y - 1, x + 4, y + 2, 0xFFFF5555);
                g.fill(x - 5, y + 3, x - 3, y + 5, 0xFF27303A);
                g.fill(x + 3, y + 3, x + 5, y + 5, 0xFF27303A);
            }
            case 3 -> { // portal
                g.fill(x - 4, y - 5, x + 4, y + 5, 0xFF142333);
                g.fill(x - 2, y - 3, x + 2, y + 3, 0xFFA34BDF);
                g.fill(x - 1, y - 2, x + 1, y + 2, 0xFF6237B5);
            }
            case 7 -> { // chest
                g.fill(x - 5, y - 3, x + 5, y + 4, 0xFF614021);
                g.fill(x - 4, y - 2, x + 4, y, 0xFFB8864C);
                g.fill(x - 1, y - 1, x + 2, y + 3, 0xFFF4D260);
            }
            case 8 -> { // skull
                g.fill(x - 4, y - 4, x + 5, y + 3, 0xFFF0EADA);
                g.fill(x - 3, y - 1, x - 1, y + 1, 0xFF20303A);
                g.fill(x + 1, y - 1, x + 3, y + 1, 0xFF20303A);
                g.fill(x - 2, y + 3, x + 3, y + 5, 0xFFDEDBCC);
            }
            default -> {
                g.drawCenteredString(mc.font, MARKER_ICONS[marker.icon()],
                    x, y - 4, 0xFF152433);
            }
        }
    }

    @Override public void onInitializeClient() {
        // Read the active model textures, including server-downloaded packs.
        // Rebuild cached colors after a resource-pack refresh (F3+T).
        ResourceManagerHelper.get(PackType.CLIENT_RESOURCES).registerReloadListener(
            new SimpleSynchronousResourceReloadListener() {
                @Override public Identifier getFabricId() {
                    return Identifier.fromNamespaceAndPath("roundmap", "block_palette");
                }
                @Override public void onResourceManagerReload(ResourceManager manager) {
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
            RoundMapMapViews.sync(client);
            RoundMapBedTracker.tick(client, position -> {
                boolean exists = false;
                for (Marker marker : markers) {
                    if (marker.name().equals("Кровать") &&
                        Math.abs(marker.x() - position.getX()) <= 1 &&
                        Math.abs(marker.z() - position.getZ()) <= 1 &&
                        marker.dimension().equals(client.level.dimension().toString())) {
                        exists = true; break;
                    }
                }
                if (!exists) {
                    markers.add(new Marker(position.getX(), position.getY(),
                        position.getZ(), client.level.dimension().toString(),
                        "Кровать", 0xFFFA4D53, 2, "", "Основная", true, false));
                    saveMarkers();
                }
            });
            RoundMapAtlas.tick(client);
            RoundMapCaveAtlas.tick(client);
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
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            RoundMapAtlas.shutdown();
            RoundMapCaveAtlas.shutdown();
        });
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
    /**
     * Pin waypoints to their saved 3D world coordinates.
     *
     * Project them from the actual game camera (not the player's block
     * position), with perspective and correct pitch. The marker is fixed
     * to the place in the world: turning or walking moves its image exactly
     * as any stationary block would move through the camera view.
     */
    private static void drawWorldMarkers(GuiGraphics g, Minecraft mc) {
        net.minecraft.client.Camera camera = mc.gameRenderer.getMainCamera();
        net.minecraft.world.phys.Vec3 eye = camera.position();
        double yaw = Math.toRadians(camera.yRot());
        double pitch = Math.toRadians(camera.xRot());
        double cosPitch = Math.cos(pitch), sinPitch = Math.sin(pitch);
        // Minecraft yaw = 0 faces +Z, positive pitch looks down.
        double horizontalX = -Math.sin(yaw), horizontalZ = Math.cos(yaw);
        // Minecraft faces south (+Z) at yaw 0: screen-right points WEST
        // (-X), not east. The previous sign mirrored waypoint positions,
        // making stationary pins appear attached to the wrong terrain.
        double rightX = -horizontalZ, rightZ = horizontalX;
        double forwardX = horizontalX * cosPitch;
        double forwardY = -sinPitch;
        double forwardZ = horizontalZ * cosPitch;
        double upX = horizontalX * sinPitch;
        double upY = cosPitch;
        double upZ = horizontalZ * sinPitch;

        int w = g.guiWidth(), h = g.guiHeight();
        double verticalFov = Math.toRadians(
            Math.max(30.0, Math.min(110.0, mc.options.fov().get())));
        double focal = (h * 0.5) / Math.tan(verticalFov * 0.5);
        for (Marker marker : markers) {
            if (!marker.visible() ||
                !marker.dimension().equals(mc.level.dimension().toString())) continue;

            // A fixed world point above the actual saved waypoint block.
            double dx = marker.x() + 0.5 - eye.x;
            double dy = marker.y() + 1.65 - eye.y;
            double dz = marker.z() + 0.5 - eye.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance > worldMarkerDistance) continue;

            double cameraDepth = dx * forwardX + dy * forwardY + dz * forwardZ;
            if (cameraDepth <= 0.08) continue;
            double cameraRight = dx * rightX + dz * rightZ;
            double cameraUp = dx * upX + dy * upY + dz * upZ;
            double sxDouble = w * 0.5 + focal * cameraRight / cameraDepth;
            double syDouble = h * 0.5 - focal * cameraUp / cameraDepth;
            if (!Double.isFinite(sxDouble) || !Double.isFinite(syDouble)
                || sxDouble < -30 || sxDouble > w + 30
                || syDouble < 52 || syDouble > h - 42) continue;

            int sx = (int)Math.round(sxDouble), sy = (int)Math.round(syDouble);
            String label = marker.name() + " · " + Math.round(distance) + " м";
            int tw = mc.font.width(label);
            // Keep all parts of the marker at one projected world location.
            g.fill(sx - tw / 2 - 5, sy + 12, sx + tw / 2 + 5, sy + 26, 0xB20B111A);
            waypointPin(g, mc, sx, sy, marker, false);
            g.drawCenteredString(mc.font, label, sx, sy + 15, 0xFFFFFFFF);
        }
    }

    private static void drawMini(GuiGraphics g, Minecraft mc, int cx, int cy) {
        RoundMapTextures.minimap(g, cx, cy, miniSize, miniZoom,
            mc.player.getBlockX(), mc.player.getBlockZ());
        outline(g, cx, cy, miniSize);
        RoundMapRadar.draw(g, mc, cx, cy, mc.player.getBlockX(), mc.player.getBlockZ(),
            (float)(miniSize * miniZoom / HALF), miniSize, miniSize, true);
        drawMiniWaypoints(g, mc, cx, cy);
        playerArrow(g, mc, cx, cy);
        if (showCompass) {
            // North-up map: N and S are at the top/bottom, E/W on the sides.
            g.drawCenteredString(mc.font, "N", cx, cy - miniSize - 12, 0xFFFFFFFF);
            g.drawCenteredString(mc.font, "S", cx, cy + miniSize + 5, 0xFFFFFFFF);
            g.drawCenteredString(mc.font, "E", cx + miniSize + 11, cy - 4, 0xFFFFFFFF);
            g.drawCenteredString(mc.font, "W", cx - miniSize - 11, cy - 4, 0xFFFFFFFF);
        }
        int bottomLabel = miniSize + (showCompass ? 18 : 8);
        if (showCoords) {
            g.drawCenteredString(mc.font,
                "X: " + mc.player.getBlockX() + " Z: " + mc.player.getBlockZ(),
                cx, cy + bottomLabel, 0xFFFFFFFF);
            bottomLabel += 11;
        }
        if (showBiome) {
            long now = System.nanoTime();
            if (now > biomeRefreshAt) {
                biomeRefreshAt = now + 1_000_000_000L;
                biomeLabel = mc.level.getBiome(mc.player.blockPosition()).unwrapKey()
                    .map(k -> k.identifier().getPath().replace('_', ' ')).orElse("unknown");
            }
            g.drawCenteredString(mc.font, "Биом: " + biomeLabel,
                cx, cy + bottomLabel, 0xFFFFFFFF);
        }
    }

    /**
     * Show every relevant destination even when it is beyond the visible
     * terrain circle. Off-map pins are clamped to the rim and keep their
     * actual horizontal distance in meters. The closest three labels are
     * shown to avoid covering the entire HUD.
     */
    private static void drawMiniWaypoints(GuiGraphics g, Minecraft mc, int cx, int cy) {
        List<Marker> visible = new ArrayList<>();
        String dimension = mc.level.dimension().toString();
        for (Marker marker : markers) {
            if (marker.visible() && marker.dimension().equals(dimension))
                visible.add(marker);
        }
        visible.sort(java.util.Comparator.comparingDouble(m ->
            Math.hypot(m.x() - mc.player.getX(), m.z() - mc.player.getZ())));
        double scale = miniSize * miniZoom / (double)HALF;
        int edge = Math.max(8, miniSize - 11);
        int maxLabels = Math.min(3, visible.size());
        int[] labelY = new int[maxLabels];
        for (int i = 0; i < visible.size(); i++) {
            Marker marker = visible.get(i);
            double dx = (marker.x() + 0.5 - mc.player.getX()) * scale;
            double dz = (marker.z() + 0.5 - mc.player.getZ()) * scale;
            double length = Math.hypot(dx, dz);
            // Clamp distant waypoints to the minimap border; their world
            // coordinates do not change, only their HUD indicator moves.
            if (length > edge) {
                dx *= edge / length;
                dz *= edge / length;
            }
            int px = cx + (int)Math.round(dx);
            int py = cy + (int)Math.round(dz);
            waypointPin(g, mc, px, py, marker, true);
            if (!showLabels || i >= maxLabels) continue;
            int distance = (int)Math.round(Math.hypot(
                marker.x() + 0.5 - mc.player.getX(),
                marker.z() + 0.5 - mc.player.getZ()));
            String label = marker.name() + " · " + distance + " м";
            if (label.length() > 24) label = label.substring(0, 21) + "...";
            int textWidth = mc.font.width(label);
            int lx = Math.max(textWidth / 2 + 3, Math.min(
                g.guiWidth() - textWidth / 2 - 3, px));
            int ly = py - 19;
            if (ly < cy - miniSize + 10) ly = py + 13;
            // Separate almost overlapping labels for neighboring pins.
            for (int j = 0; j < i; j++) {
                if (Math.abs(ly - labelY[j]) < 12) ly += 12;
            }
            labelY[i] = ly;
            g.fill(lx - textWidth / 2 - 3, ly - 2,
                lx + textWidth / 2 + 4, ly + 10, 0xB3141D27);
            g.drawCenteredString(mc.font, label, lx, ly, 0xFFFFFFFF);
        }
    }

    private static void drawMarkers(GuiGraphics g, Minecraft mc, int cx, int cy,
            float scale, int radius, boolean circle, double worldX, double worldZ) {
        for (Marker marker : markers) {
            if (!marker.visible() || !marker.dimension().equals(mc.level.dimension().toString())) continue;
            int dx = (int)Math.round((marker.x() - worldX) * scale);
            int dz = (int)Math.round((marker.z() - worldZ) * scale);
            int limitX = circle ? radius - 4 : g.guiWidth() / 2 - 4;
            int limitY = circle ? radius - 4 : g.guiHeight() / 2 - 4;
            if (Math.abs(dx) >= limitX || Math.abs(dz) >= limitY) continue;
            if (circle && dx * dx + dz * dz >= (radius - 4) * (radius - 4)) continue;
            waypointPin(g, mc, cx + dx, cy + dz, marker, circle);
            if (showLabels) g.drawCenteredString(mc.font, marker.name(), cx + dx, cy + dz - (circle ? 15 : 19), -1);
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
        arrowShape(g, cx, cy, fx, fy, sx, sy, 0.50, ARROW_COLORS[arrowColorIndex]);
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
        private boolean contextOpen;
        private int contextX, contextY, clickedX, clickedY, clickedZ;
        private MapScreen() { super(Component.literal("RoundMap")); }
        // Keep client ticks running in singleplayer while the map is open so
        // nearby rendered chunks can continue to appear on the atlas.
        @Override public boolean isPauseScreen() { return false; }
        private MapScreen(int x, int z) {
            this(x, z, mapZoom);
        }
        private MapScreen(int x, int z, int zoom) {
            this();
            mapZoom = Math.max(1, Math.min(10, zoom));
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                panX = x - mc.player.getBlockX();
                panZ = z - mc.player.getBlockZ();
            }
        }
        private void zoom(int amount) { mapZoom = Math.max(1, Math.min(10, mapZoom + amount)); }
        private void rebuild() {
            clearWidgets();
            init();
        }
        @Override protected void init() {
            int y = 8;
            addRenderableWidget(Button.builder(Component.literal(
                caveMode ? "Поверхность" : "• Поверхность"), b -> {
                caveMode = false;
                RoundMapTextures.invalidate();
                rebuild();
            }).bounds(6, y, 99, 20).build());
            addRenderableWidget(Button.builder(Component.literal(
                caveMode ? "• Пещеры" : "Пещеры"), b -> {
                caveMode = true;
                RoundMapTextures.invalidate();
                rebuild();
            }).bounds(109, y, 81, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Карты"), b ->
                minecraft.setScreen(new MapLibraryScreen(this)))
                .bounds(width - 322, y, 101, 20).build());
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
            if (contextOpen) {
                int x = contextX, top = contextY;
                addRenderableWidget(Button.builder(Component.literal("Добавить точку"), b ->
                    minecraft.setScreen(new NameScreen(clickedX, clickedZ,
                        minecraft.level.dimension().toString(), this)))
                    .bounds(x, top + 25, 190, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Временная точка"), b -> {
                    markers.add(new Marker(clickedX, clickedY, clickedZ,
                        minecraft.level.dimension().toString(), "Временная",
                        MARKER_COLORS[1], 0, "!", "Основная", true, true));
                    contextOpen = false; rebuild();
                }).bounds(x, top + 48, 190, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Копировать координаты"), b -> {
                    GLFW.glfwSetClipboardString(minecraft.getWindow().handle(),
                        clickedX + " " + clickedY + " " + clickedZ);
                    contextOpen = false; rebuild();
                }).bounds(x, top + 71, 190, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Поделиться (копировать)"), b -> {
                    GLFW.glfwSetClipboardString(minecraft.getWindow().handle(),
                        "X: " + clickedX + ", Y: " + clickedY + ", Z: " + clickedZ);
                    contextOpen = false; rebuild();
                }).bounds(x, top + 94, 190, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Закрыть"), b -> {
                    contextOpen = false; rebuild();
                }).bounds(x, top + 117, 190, 20).build());
            }
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
            String count = caveMode
                ? "Пещеры • слой Y " + (RoundMapCaveAtlas.band() * 16) +
                  "  •  чанков: " + RoundMapCaveAtlas.chunks()
                : "Поверхность • чанков: " + RoundMapAtlas.exploredChunks() +
                  "  •  подробно: " + RoundMapAtlas.detailedChunks();
            g.drawString(font, count, 197, 12, 0xFFFFFFFF);
            g.fill(0, height - 25, width, height, 0xB018222C);
            int hoveredX = (int)Math.round(targetX + (mouseX - cx) / (double)mapZoom);
            int hoveredZ = (int)Math.round(targetZ + (mouseY - cy) / (double)mapZoom);
            int hoveredY = RoundMapAtlas.heightAt(hoveredX, hoveredZ);
            if (hoveredY == Short.MIN_VALUE) hoveredY = mc.player.getBlockY();
            g.drawString(font, "ЛКМ: сдвиг   Колесо: масштаб   ПКМ: меню   M: закрыть",
                8, height - 17, 0xFFE3E6EB);
            int meters = (int)Math.round(Math.hypot(hoveredX - mc.player.getBlockX(),
                hoveredZ - mc.player.getBlockZ()));
            g.drawString(font, "X " + hoveredX + " Y " + hoveredY +
                " Z " + hoveredZ + "  • " + meters + " м",
                Math.max(0, width - 210), height - 17, 0xFFFFFFFF);
            if (contextOpen) {
                g.fill(contextX - 3, contextY - 4,
                    contextX + 193, contextY + 142, 0xED10181F);
                g.drawString(font, clickedX + ", " + clickedY + ", " +
                    clickedZ + "  •  ПКМ", contextX + 5, contextY + 7, 0xFFCBD5DF);
            }
            super.render(g, mouseX, mouseY, delta);
            boolean right = GLFW.glfwGetMouseButton(handle, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
            if (right && !wasRight && mouseY > 35 && mouseY < height - 25
                && mouseX < width - 38) {
                contextOpen = true;
                contextX = Math.max(4, Math.min(width - 198, mouseX));
                contextY = Math.max(40, Math.min(height - 169, mouseY));
                clickedX = hoveredX; clickedY = hoveredY; clickedZ = hoveredZ;
                rebuild();
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

    private static final class MapLibraryScreen extends Screen {
        private final Screen parent;
        private MapLibraryScreen(Screen parent) {
            super(Component.literal("Карты"));
            this.parent = parent;
        }
        @Override protected void init() {
            int left = width / 2 - 176, y = 58;
            addRenderableWidget(Button.builder(Component.literal("Основная — текущий мир"), b ->
                minecraft.setScreen(new MapScreen())).bounds(left, y, 351, 20).build());
            List<RoundMapMapViews.View> maps = RoundMapMapViews.all();
            int max = Math.min(maps.size(), Math.max(1, (height - 160) / 29));
            for (int i = 0; i < max; i++) {
                RoundMapMapViews.View view = maps.get(i);
                int row = y + 28 + i * 29;
                addRenderableWidget(Button.builder(Component.literal(
                    view.name() + " • X " + view.x() + " Z " + view.z()), b ->
                    minecraft.setScreen(new MapScreen(view.x(), view.z(), view.zoom())))
                    .bounds(left, row, 298, 21).build());
                addRenderableWidget(Button.builder(Component.literal("X"), b -> {
                    RoundMapMapViews.remove(view);
                    minecraft.setScreen(new MapLibraryScreen(parent));
                }).bounds(left + 305, row, 46, 21).build());
            }
            addRenderableWidget(Button.builder(Component.literal("Новая карта"), b ->
                minecraft.setScreen(new MapViewNameScreen(this, parent)))
                .bounds(width / 2 - 175, height - 61, 169, 22).build());
            addRenderableWidget(Button.builder(Component.literal("Готово"), b ->
                minecraft.setScreen(parent)).bounds(width / 2 + 7, height - 61, 169, 22).build());
        }
        @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            g.fill(0, 0, width, height, 0xCB162029);
            super.render(g, mouseX, mouseY, delta);
            g.drawCenteredString(font, "Карты — сохранённые виды местности",
                width / 2, 24, 0xFFFFFFFF);
            g.drawCenteredString(font,
                "Все виды используют общую исследованную карту своего мира",
                width / 2, 40, 0xFFB7C4CB);
        }
    }

    private static final class MapViewNameScreen extends Screen {
        private final Screen library, source;
        private EditBox nameInput;
        private MapViewNameScreen(Screen library, Screen source) {
            super(Component.literal("Новая карта"));
            this.library = library; this.source = source;
        }
        @Override protected void init() {
            int x = width / 2 - 125, y = height / 2 - 22;
            nameInput = new EditBox(font, x, y, 250, 20, Component.literal("Название карты"));
            nameInput.setMaxLength(40);
            nameInput.setValue("Карта " + (RoundMapMapViews.all().size() + 1));
            addRenderableWidget(nameInput); setInitialFocus(nameInput);
            addRenderableWidget(Button.builder(Component.literal("Сохранить"), b -> {
                if (!nameInput.getValue().isBlank() && minecraft.player != null) {
                    int x0 = minecraft.player.getBlockX(), z0 = minecraft.player.getBlockZ();
                    if (source instanceof MapScreen map) {
                        x0 += (int)Math.round(map.panX);
                        z0 += (int)Math.round(map.panZ);
                    }
                    RoundMapMapViews.add(nameInput.getValue(), x0, z0, mapZoom);
                }
                minecraft.setScreen(new MapLibraryScreen(source));
            }).bounds(x, y + 29, 120, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Отмена"), b ->
                minecraft.setScreen(library))
                .bounds(x + 130, y + 29, 120, 20).build());
        }
        @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            g.fill(0, 0, width, height, 0xC5162029);
            super.render(g, mouseX, mouseY, delta);
            g.drawCenteredString(font, "Сохранить вид карты с текущим центром и масштабом",
                width / 2, height / 2 - 45, 0xFFFFFFFF);
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
                    () -> { RoundMapAtlas.readsPerTick = RoundMapAtlas.readsPerTick >= 256 ? 32 :
                        RoundMapAtlas.readsPerTick < 64 ? 64 :
                        RoundMapAtlas.readsPerTick < 128 ? 128 : 256;
                        savePosition(); });
                String timeLabel = RoundMapAtlas.timeMode == 0 ? "По миру" :
                    RoundMapAtlas.timeMode == 1 ? "День" : "Ночь";
                option(right, top, cw, "Время на карте: " + timeLabel, () -> {
                    RoundMapAtlas.timeMode = (RoundMapAtlas.timeMode + 1) % 3;
                    RoundMapTextures.invalidate(); savePosition();
                });
                option(right, top + step, cw, "Освещение: " + (RoundMapAtlas.lighting ? "Вкл" : "Выкл"),
                    () -> { RoundMapAtlas.lighting = !RoundMapAtlas.lighting;
                        RoundMapTextures.invalidate(); savePosition(); });
                option(left, top + step * 2, cw,
                    "Стрелка: " + ARROW_COLOR_NAMES[arrowColorIndex], () -> {
                    arrowColorIndex = (arrowColorIndex + 1) % ARROW_COLORS.length;
                    savePosition();
                });
                option(right, top + step * 2, cw,
                    "Пикселизация: " + mapPixelSize + "×" + mapPixelSize, () -> {
                    mapPixelSize = mapPixelSize == 4 ? 1 : mapPixelSize + 1;
                    RoundMapTextures.invalidate();
                    savePosition();
                });
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
                g.drawCenteredString(font, "Сканирование ограничено 4 мс за тик для защиты FPS",
                    width / 2, height / 2 + 25, 0xFFCCCCCC);
        }
    }

    private static final class WaypointListScreen extends Screen {
        private final Screen parent;
        private final int page;
        private final String search, folder;
        private EditBox filterInput;
        private boolean ready;

        private WaypointListScreen(Screen parent, int page) {
            this(parent, page, "", "Все папки");
        }
        private WaypointListScreen(Screen parent, int page, String search, String folder) {
            super(Component.literal("Точки RoundMap"));
            this.parent = parent;
            this.page = Math.max(0, page);
            this.search = search; this.folder = folder;
        }
        private List<Marker> filtered() {
            List<Marker> results = new ArrayList<>();
            for (Marker marker : markers) {
                if (!folder.equals("Все папки") && !folder.equals(marker.folder())) continue;
                String text = search.toLowerCase(java.util.Locale.ROOT).trim();
                if (!text.isEmpty() &&
                    !marker.name().toLowerCase(java.util.Locale.ROOT).contains(text) &&
                    !marker.folder().toLowerCase(java.util.Locale.ROOT).contains(text))
                    continue;
                results.add(marker);
            }
            return results;
        }
        @Override protected void init() {
            int left = Math.max(6, width / 2 - 210), total = Math.min(width - left - 6, 420);
            int upper = 43;
            filterInput = new EditBox(font, left, upper, Math.max(65, total / 2 - 5),
                20, Component.literal("Поиск точек"));
            filterInput.setMaxLength(40);
            filterInput.setValue(search);
            addRenderableWidget(filterInput);
            filterInput.setResponder(value -> {
                if (ready && !value.equals(search))
                    minecraft.setScreen(new WaypointListScreen(parent, 0, value, folder));
            });
            ready = true;
            addRenderableWidget(Button.builder(Component.literal("Папка: " + folder), b -> {
                int at = folder.equals("Все папки") ? -1 : folders.indexOf(folder);
                String next = at + 1 >= folders.size() ? "Все папки" : folders.get(at + 1);
                minecraft.setScreen(new WaypointListScreen(parent, 0, search, next));
            }).bounds(left + total / 2, upper, total - total / 2, 20).build());
            List<Marker> matches = filtered();
            int perPage = Math.max(1, Math.min(8, (height - 180) / 27));
            int start = page * perPage;
            int y = upper + 31;
            for (int i = start; i < Math.min(matches.size(), start + perPage); i++) {
                Marker marker = matches.get(i);
                int yRow = y + (i - start) * 27;
                int actionWidth = 40, nameWidth = Math.max(80, total - 3 * actionWidth - 12);
                addRenderableWidget(Button.builder(Component.literal(
                    MARKER_ICONS[marker.icon()] + " " + marker.name() +
                    " [" + marker.x() + "," + marker.y() + "," + marker.z() + "]"), b ->
                    minecraft.setScreen(new MapScreen(marker.x(), marker.z())))
                    .bounds(left, yRow, nameWidth, 20).build());
                addRenderableWidget(Button.builder(Component.literal("Изм"), b ->
                    minecraft.setScreen(new NameScreen(marker, this)))
                    .bounds(left + nameWidth + 4, yRow, actionWidth, 20).build());
                addRenderableWidget(Button.builder(Component.literal(
                    marker.visible() ? "Вкл" : "Вык"), b -> {
                    int at = markers.indexOf(marker);
                    if (at >= 0) markers.set(at, marker.withVisible(!marker.visible()));
                    saveMarkers();
                    minecraft.setScreen(new WaypointListScreen(parent, page, search, folder));
                }).bounds(left + nameWidth + actionWidth + 8, yRow, actionWidth, 20).build());
                addRenderableWidget(Button.builder(Component.literal("X"), b -> {
                    markers.remove(marker); saveMarkers();
                    minecraft.setScreen(new WaypointListScreen(parent, Math.min(page,
                        Math.max(0, (filtered().size() - 1) / perPage)), search, folder));
                }).bounds(left + nameWidth + actionWidth * 2 + 12, yRow, actionWidth, 20).build());
            }
            int controls = height - 79;
            if (page > 0) addRenderableWidget(Button.builder(Component.literal("<"), b ->
                minecraft.setScreen(new WaypointListScreen(parent, page - 1, search, folder)))
                .bounds(left, controls, 42, 20).build());
            if (start + perPage < matches.size())
                addRenderableWidget(Button.builder(Component.literal(">"), b ->
                    minecraft.setScreen(new WaypointListScreen(parent, page + 1, search, folder)))
                    .bounds(left + 46, controls, 42, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Новая папка"), b ->
                minecraft.setScreen(new FolderQuickScreen(this)))
                .bounds(left + total - 201, controls, 98, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Новая точка"), b -> {
                if (minecraft.player != null && minecraft.level != null)
                    minecraft.setScreen(new NameScreen(minecraft.player.getBlockX(),
                        minecraft.player.getBlockZ(), minecraft.level.dimension().toString(), this));
            }).bounds(left + total - 99, controls, 99, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Готово"), b ->
                minecraft.setScreen(parent)).bounds(width / 2 - 85, height - 38, 170, 20).build());
            setInitialFocus(filterInput);
        }
        @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            g.fill(0, 0, width, height, 0xC51B232D);
            super.render(g, mouseX, mouseY, delta);
            g.drawCenteredString(font, "Точки  •  " + filtered().size() +
                "  •  папок: " + folders.size(), width / 2, 19, 0xFFFFFFFF);
        }
    }

    private static final class FolderQuickScreen extends Screen {
        private final Screen parent;
        private EditBox nameInput;
        private FolderQuickScreen(Screen parent) {
            super(Component.literal("Новая папка"));
            this.parent = parent;
        }
        @Override protected void init() {
            int x = width / 2 - 125, y = height / 2 - 20;
            nameInput = new EditBox(font, x, y, 250, 20, Component.literal("Название папки"));
            nameInput.setMaxLength(32);
            addRenderableWidget(nameInput); setInitialFocus(nameInput);
            addRenderableWidget(Button.builder(Component.literal("Создать"), b -> {
                String name = nameInput.getValue().trim();
                if (!name.isEmpty() && !folders.contains(name)) {
                    folders.add(name); saveMarkers();
                }
                minecraft.setScreen(parent);
            }).bounds(x, y + 30, 122, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Отмена"), b ->
                minecraft.setScreen(parent)).bounds(x + 128, y + 30, 122, 20).build());
        }
        @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            g.fill(0, 0, width, height, 0xC51B232D);
            super.render(g, mouseX, mouseY, delta);
            g.drawCenteredString(font, "Создать папку для точек", width / 2,
                height / 2 - 43, 0xFFFFFFFF);
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

    private static final class WaypointDraft {
        int x, y, z, color, icon;
        String name, initials, folder, dimension;
        boolean visible = true, temporary;
        Marker existing;
        WaypointDraft(int x, int y, int z, String dimension) {
            this.x = x; this.y = y; this.z = z; this.dimension = dimension;
            this.name = "Точка " + (markers.size() + 1);
            this.color = MARKER_COLORS[0]; this.initials = "";
            this.folder = "Основная"; this.icon = 0;
        }
        WaypointDraft(Marker marker) {
            this(marker.x(), marker.y(), marker.z(), marker.dimension());
            name = marker.name(); color = marker.color(); icon = marker.icon();
            initials = marker.initials(); folder = marker.folder();
            visible = marker.visible(); temporary = marker.temporary();
            existing = marker;
        }
    }

    private static final class NameScreen extends Screen {
        private final WaypointDraft draft;
        private final Screen parent;
        private EditBox nameInput, initialsInput, xInput, yInput, zInput;
        private String error = "";

        private NameScreen(int x, int z, String dimension) {
            this(x, z, dimension, null);
        }
        private NameScreen(int x, int z, String dimension, Screen parent) {
            this(new WaypointDraft(x,
                Minecraft.getInstance().player == null ? 64 :
                    Minecraft.getInstance().player.getBlockY(),
                z, dimension), parent);
            int elevation = RoundMapAtlas.heightAt(x, z);
            if (elevation != Short.MIN_VALUE) draft.y = elevation;
        }
        private NameScreen(Marker marker, Screen parent) {
            this(new WaypointDraft(marker), parent);
        }
        private NameScreen(WaypointDraft draft, Screen parent) {
            super(Component.literal("Редактор точки"));
            this.draft = draft;
            this.parent = parent;
        }
        private int left() { return width / 2 - 168; }
        private int top() { return Math.max(19, height / 2 - 148); }
        private EditBox field(int x, int y, int w, String label, String value, int length) {
            EditBox input = new EditBox(font, x, y, w, 20, Component.literal(label));
            input.setMaxLength(length);
            input.setValue(value);
            addRenderableWidget(input);
            return input;
        }
        private void capture() {
            if (nameInput == null) return;
            draft.name = nameInput.getValue();
            draft.initials = initialsInput.getValue();
            try { draft.x = Integer.parseInt(xInput.getValue().trim()); } catch (NumberFormatException ignored) { }
            try { draft.y = Integer.parseInt(yInput.getValue().trim()); } catch (NumberFormatException ignored) { }
            try { draft.z = Integer.parseInt(zInput.getValue().trim()); } catch (NumberFormatException ignored) { }
        }
        private void save() {
            if (nameInput.getValue().trim().isEmpty()) {
                error = "Укажи название точки";
                return;
            }
            try {
                draft.x = Integer.parseInt(xInput.getValue().trim());
                draft.y = Integer.parseInt(yInput.getValue().trim());
                draft.z = Integer.parseInt(zInput.getValue().trim());
            } catch (NumberFormatException e) {
                error = "Координаты должны быть целыми числами";
                return;
            }
            draft.name = nameInput.getValue().trim();
            draft.initials = initialsInput.getValue().trim();
            if (!folders.contains(draft.folder)) folders.add(draft.folder);
            Marker marker = new Marker(draft.x, draft.y, draft.z, draft.dimension,
                draft.name, draft.color, draft.icon, draft.initials,
                draft.folder, draft.visible, draft.temporary);
            int index = markers.indexOf(draft.existing);
            if (draft.existing != null && index >= 0) markers.set(index, marker);
            else markers.add(marker);
            saveMarkers();
            minecraft.setScreen(parent);
        }
        @Override protected void init() {
            int x = left(), y = top();
            nameInput = field(x, y + 17, 235, "Название точки", draft.name, 48);
            initialsInput = field(x + 244, y + 17, 92,
                "Инициалы", draft.initials, 2);
            xInput = field(x, y + 61, 106, "X", Integer.toString(draft.x), 12);
            yInput = field(x + 115, y + 61, 106, "Y", Integer.toString(draft.y), 12);
            zInput = field(x + 230, y + 61, 106, "Z", Integer.toString(draft.z), 12);
            addRenderableWidget(Button.builder(Component.literal(
                "Иконка: " + MARKER_ICONS[draft.icon]), b -> {
                capture();
                minecraft.setScreen(new IconPickerScreen(draft, parent));
            }).bounds(x, y + 89, 106, 20).build());
            addRenderableWidget(Button.builder(Component.literal(
                "Папка: " + draft.folder), b -> {
                capture();
                int index = folders.indexOf(draft.folder);
                draft.folder = folders.get((index + 1) % folders.size());
                minecraft.setScreen(new NameScreen(draft, parent));
            }).bounds(x + 115, y + 89, 166, 20).build());
            addRenderableWidget(Button.builder(Component.literal("+"), b -> {
                capture();
                minecraft.setScreen(new FolderEditorScreen(draft, parent));
            }).bounds(x + 291, y + 89, 45, 20).build());
            for (int i = 0; i < MARKER_COLORS.length; i++) {
                int slot = i, bx = x + (i % 8) * 42, by = y + 132 + (i / 8) * 28;
                addRenderableWidget(Button.builder(Component.literal(" "), b -> {
                    capture(); draft.color = MARKER_COLORS[slot];
                }).bounds(bx, by, 36, 23).build());
            }
            addRenderableWidget(Button.builder(Component.literal(
                "На карте и в мире: " + (draft.visible ? "Да" : "Нет")), b -> {
                capture(); draft.visible = !draft.visible;
                minecraft.setScreen(new NameScreen(draft, parent));
            }).bounds(x, y + 194, 225, 20).build());
            addRenderableWidget(Button.builder(Component.literal(
                draft.temporary ? "Временная" : "Постоянная"), b -> {
                capture(); draft.temporary = !draft.temporary;
                minecraft.setScreen(new NameScreen(draft, parent));
            }).bounds(x + 233, y + 194, 103, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Сохранить"),
                b -> save()).bounds(x, y + 226, 163, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Отмена"),
                b -> minecraft.setScreen(parent)).bounds(x + 174, y + 226, 162, 20).build());
            setInitialFocus(nameInput);
        }
        @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            g.fill(0, 0, width, height, 0xBD15202B);
            super.render(g, mouseX, mouseY, delta);
            int x = left(), y = top();
            g.drawString(font, draft.existing == null ? "Новая точка" : "Изменить точку",
                x, y - 13, 0xFFFFFFFF);
            g.drawString(font, "Название", x, y + 5, 0xFFDDDDDD);
            g.drawString(font, "Инициалы", x + 244, y + 5, 0xFFDDDDDD);
            g.drawString(font, "Координаты X, Y, Z", x, y + 49, 0xFFDDDDDD);
            g.drawString(font, "Цвет точки", x, y + 118, 0xFFDDDDDD);
            for (int i = 0; i < MARKER_COLORS.length; i++) {
                int bx = x + (i % 8) * 42, by = y + 132 + (i / 8) * 28;
                g.fill(bx + 7, by + 4, bx + 29, by + 19, MARKER_COLORS[i]);
                if (draft.color == MARKER_COLORS[i]) {
                    g.fill(bx + 2, by + 1, bx + 33, by + 2, 0xFFFFFFFF);
                }
            }
            Marker example = new Marker(draft.x, draft.y, draft.z, draft.dimension,
                draft.name, draft.color, draft.icon, draft.initials, draft.folder,
                draft.visible, draft.temporary);
            waypointPin(g, minecraft, x + 326, y - 10, example, false);
            if (!error.isEmpty())
                g.drawCenteredString(font, error, width / 2, y + 251, 0xFFFF7777);
        }
    }

    private static final class IconPickerScreen extends Screen {
        private final WaypointDraft draft;
        private final Screen parent;
        private IconPickerScreen(WaypointDraft draft, Screen parent) {
            super(Component.literal("Выбери иконку"));
            this.draft = draft; this.parent = parent;
        }
        @Override protected void init() {
            int w = 48, startX = width / 2 - 149, y = height / 2 - 55;
            for (int i = 0; i < MARKER_ICONS.length; i++) {
                int iconIndex = i;
                addRenderableWidget(Button.builder(Component.literal(MARKER_ICONS[i]), b -> {
                    draft.icon = iconIndex;
                    minecraft.setScreen(new NameScreen(draft, parent));
                }).bounds(startX + (i % 6) * 50,
                    y + (i / 6) * 42, w, 34).build());
            }
            addRenderableWidget(Button.builder(Component.literal("Назад"), b ->
                minecraft.setScreen(new NameScreen(draft, parent)))
                .bounds(width / 2 - 90, y + 96, 180, 20).build());
        }
        @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            g.fill(0, 0, width, height, 0xCD152029);
            super.render(g, mouseX, mouseY, delta);
            int startX = width / 2 - 149, y = height / 2 - 55;
            for (int i = 0; i < MARKER_ICONS.length; i++) {
                int x = startX + (i % 6) * 50 + 24;
                int yy = y + (i / 6) * 42 + 13;
                Marker preview = new Marker(0, 64, 0, draft.dimension,
                    "Образец", MARKER_COLORS[0], i, "", "Основная", true, false);
                waypointPin(g, minecraft, x, yy, preview, true);
                if (draft.icon == i) {
                    g.fill(x - 21, yy - 19, x + 22, yy - 18, 0xFFFFFFFF);
                }
            }
            int col = (mouseX - startX) / 50, row = (mouseY - y) / 42;
            int selection = row * 6 + col;
            if (mouseX >= startX && col >= 0 && col < 6 &&
                row >= 0 && row < 2 && selection < MARKER_ICON_NAMES.length) {
                g.drawCenteredString(font, MARKER_ICON_NAMES[selection],
                    width / 2, y - 21, 0xFFFFFFFF);
            } else {
                g.drawCenteredString(font, "Выберите иконку точки",
                    width / 2, y - 21, 0xFFFFFFFF);
            }
        }
    }

    private static final class FolderEditorScreen extends Screen {
        private final WaypointDraft draft;
        private final Screen parent;
        private EditBox input;
        private FolderEditorScreen(WaypointDraft draft, Screen parent) {
            super(Component.literal("Новая папка"));
            this.draft = draft; this.parent = parent;
        }
        @Override protected void init() {
            int x = width / 2 - 120, y = height / 2 - 25;
            input = new EditBox(font, x, y, 240, 20, Component.literal("Название папки"));
            input.setMaxLength(36); addRenderableWidget(input); setInitialFocus(input);
            addRenderableWidget(Button.builder(Component.literal("Создать"), b -> {
                String name = input.getValue().trim();
                if (!name.isEmpty() && !folders.contains(name)) folders.add(name);
                if (!name.isEmpty()) draft.folder = name;
                saveMarkers();
                minecraft.setScreen(new NameScreen(draft, parent));
            }).bounds(x, y + 28, 115, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Отмена"), b ->
                minecraft.setScreen(new NameScreen(draft, parent)))
                .bounds(x + 125, y + 28, 115, 20).build());
        }
        @Override public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            g.fill(0, 0, width, height, 0xD0152029);
            super.render(g, mouseX, mouseY, delta);
            g.drawCenteredString(font, "Новая папка точек", width / 2, height / 2 - 46, -1);
        }
    }
}

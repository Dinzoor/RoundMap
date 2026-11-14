# RoundMap — Fabric Minecraft 1.21.11 (0.2.0-alpha.9)

RoundMap is an experimental client-side world map for Minecraft Java 1.21.11 and Fabric. Source, interface and map graphics are original to RoundMap; Diorama was reviewed as a feature reference.

## Map and minimap

- Circular, movable HUD minimap: freely position it with the mouse in the editor. Adjust size, terrain zoom, opacity, compass, coordinates and biome.
- Fullscreen map: `M` by default, smooth left-button drag, wheel zoom, recenter button, surface/cave map tabs, cursor coordinates and distance.
- Surface map: incremental scanning of already loaded chunks, world/dimension-specific persistent explorer atlas and soft fog of war. Terrain model texture sampling includes active server resource packs when compatible.
- Caves (alpha): a **separate** cached and persisted 2D map of cave floors near the player's vertical 16-block layer. Does not reveal unseen/unloaded chunks. Cave mode currently has less detail than Diorama.
- Nighttime lighting: block-light values captured with the terrain. Choose world time, forced day or night in **Вид** settings, and enable/disable night shading.
- Map library (**Карты**): save named camera views (center and zoom), reopen them, or remove a view. Views share the explored terrain for the same world/dimension and do **not** duplicate the world save.
- Optional entity radar: players, hostile/passive/neutral mobs and dropped items. Only client-observable entities are used.

## Waypoints and folders

- Right-click on the fullscreen map opens a context menu: create a named waypoint, add a temporary waypoint, copy XYZ coordinates or copy a shareable position.
- Waypoint editor supports name, X/Y/Z, initials, 12 original pixel-art icon styles, 16 colors, folders, display toggle and temporary/permanent status.
- Waypoint list supports search, folder filtering, creating folders, editing existing entries, toggling visibility and deleting.
- Permanent waypoints save in isolated world/dimension files; older waypoint formats still load.
- Waypoint pins are drawn on the minimap, full map and world overlay. Nearby beds found in already-loaded chunks can be added automatically as red bed markers (not necessarily the player's respawn bed).
- Settings under Minecraft's Options menu. Rebind keys in Minecraft's Controls menu.

## Configuration and files

- Minecraft 1.21.11, Java 21, Fabric Loader, matching Fabric API.
- Install exactly one RoundMap JAR in `.minecraft/mods`.
- Mod settings: `config/roundmap-position.properties`.
- Waypoints/folders: `config/roundmap/waypoints/v2/<hash>.txt`.
- Explored surface: `config/roundmap/explored/v3/<hash>.dat.gz` (RMA5 format).
- Cave maps: `config/roundmap/caves/v1/<hash>.dat.gz`.
- Saved map views: `config/roundmap/map_views/v1/<hash>.txt`.
- All map and waypoint data are separated by world/connection and dimension. Cave data additionally uses a separate vertical layer.

## Performance

Surface scan has three levels: rapid per-chunk overview, medium 4×4 preview and final 16×16 block sampling. Block reads are capped by both an adjustable sample count and a short main-thread work budget. Fullscreen textures use 320px preview followed by a cached 1024px detailed texture. Disk compression runs off the render thread.

Use **Вид → Сканирование** to tune the budget (32, 64, 128 or 256 samples/tick). Map quality can be toggled between detailed and faster. Shader/Iris/Sodium compatibility and FPS must be tested in game.

## Important limitations

This is an alpha and is **not** a graphical clone of Diorama: volumetric block baking, identical night shaders, full 3D cave geometry, display-entity based server decorations, true 3D waypoint beams, independent map datasets and all Diorama controls are not yet implemented. Server-side custom blocks are detectable only where their client block states and resource-pack models are available; decorations based solely on display entities are not automatically mapped.

RoundMap is an independent project, not affiliated with Diorama.

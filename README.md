# RoundMap — Fabric Minecraft 1.21.11 (0.2.0-alpha.24)

RoundMap is an experimental client-side world map for Minecraft Java 1.21.11 and Fabric. Source, interface and map graphics are original to RoundMap; Diorama was reviewed as a feature reference.

## Map and minimap

- Circular, movable HUD minimap: freely position it with the mouse in the editor. Adjust size, terrain zoom, opacity, compass, coordinates and biome.
- Pixel-art minimap frames (alpha.24): **6 styles** (no frame, Classic, Stone, Neon, Gold, Technical). In **Настройки → Мини-карта**, use **LMB for previous / RMB for next** on the style button; the selection is saved in `roundmap-position.properties`. A small style preview is shown when screen space allows.
- Fullscreen map: `M` by default, smooth left-button drag, wheel zoom, recenter button, surface/cave map tabs, cursor coordinates and distance.
- Surface map: incremental scanning of already loaded chunks, world/dimension-specific persistent explorer atlas and soft fog of war. Terrain model texture sampling includes active server resource packs when compatible.
- Caves (alpha): a **separate** cached and persisted 2D map of cave floors near the player's vertical 16-block layer. Does not reveal unseen/unloaded chunks. Cave mode currently has less detail than Diorama.
- Maze wall contrast (alpha.23): fullscreen surface maps outline the boundaries where an explored floor is at least two blocks below an adjacent wall/ledge. This makes corridor dead ends easier to recognize without revealing unexplored areas. Toggle **Настройки → Оверлеи → Границы стен**; the small HUD minimap is unchanged.
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

The persistent surface atlas scans in three stages: one fast preview per chunk, a medium 4×4 sample grid, and finally one sample **per block**. The fullscreen renderer chooses an adaptive level of detail: overview colors when far away, block colors at medium zoom, and **4×4 actual model-texture subpixels within individual blocks** at zoom ≥2, where those textures are available.

The optional high-zoom texture cache is populated only by bounded client-tick scanning close to the player. It is an in-memory LRU capped at 256 chunks (about 4 MiB), **not persisted to disk**; the normal one-color-per-block atlas is saved as before. The map never asks for unloaded chunks and does not reveal undiscovered terrain. Fullscreen images use a fast 400px preview followed by a capped 2048px detailed GPU texture, built incrementally rather than in one expensive frame. The HUD minimap has its own dynamically sized texture: **one texture pixel per on-screen GUI pixel** (72/96/120px for the three common radii), avoiding avoidable resampling blur. Its pixel-art border is cached as a single separate GPU quad and regenerated only when style or size changes. The fullscreen map now rebuilds close to the precise zoom value after wheel animations, using relative rather than absolute thresholds at low zoom to maintain clarity when zooming far out.

**Settings → Вид:** "Качество: Детально" for per-block scanning; "Детализация большой карты: Максимальная" for one full-map cell per block; "Текстуры внутри блока: 4×4" for high-zoom details (turn off for performance). "Сканирование" controls the sample cap (32/64/128/256), with an independent ~4ms scan-time limit. Behavior and FPS on Iris/Sodium and server resource packs still need in-game verification.

## Important limitations

This is an alpha and is **not** a graphical clone of Diorama: volumetric block baking, identical night shaders, full 3D cave geometry, display-entity based server decorations, true 3D waypoint beams, independent map datasets and all Diorama controls are not yet implemented. Server-side custom blocks are detectable only where their client block states and resource-pack models are available; decorations based solely on display entities are not automatically mapped.

RoundMap is an independent project, not affiliated with Diorama.

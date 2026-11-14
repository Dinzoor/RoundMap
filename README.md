# RoundMap — Fabric 1.21.11 (0.2.0 alpha.7)

Experimental client-side world map, built for Minecraft Java 1.21.11 and Fabric API.

## Map features
- Round HUD minimap with drag-to-position editor, terrain zoom, size and opacity.
- Large fullscreen map (M by default), mouse drag, wheel zoom, recenter, right-click waypoint creation.
- Persistent explored surface atlas and fog of war per world/server/dimension.
- Map renders from GPU textures instead of per-pixel GUI rectangles.
- Surface is captured incrementally on the client tick, never scanned during GUI rendering.
- Fine details are progressively refined across all visited chunks, rather than just 4 chunks around the player. Fullscreen texture resolution is up to 1024px.
- Uses block model top-face sprites from active resource packs (including downloaded server resource packs) to approximate custom block colors. Detects resource-pack reloads and incrementally resamples visited chunks.
- Waypoints visible on both maps and as directional labels in the game world.
- Waypoint list with coordinates and deletion.
- Optional player, hostile, passive, neutral and dropped-item radar.
- Five tabs: Мини-карта, Радар, Оверлеи, Точки, Вид. UI supports Russian.
- Keys can be rebound in Minecraft's standard Controls menu.

## Setup
Use Java 21, Fabric Loader and Fabric API for Minecraft 1.21.11.
Build with `gradle build`; install the JAR from `build/libs/` without `-sources`.
GitHub Actions also builds the JAR; see **Actions → Build Fabric JAR → Artifacts**.

## Data and performance
- Position, UI toggles, zoom and radar settings: `config/roundmap-position.properties`
- Named waypoints: `config/roundmap/waypoints/v2/<sha256>.txt` (separate per save/server/dimension)
- Explored atlas: `config/roundmap/explored/v3/<sha256>.dat.gz` (separate per save/server/dimension).
- Older terrain caches remain on disk but v3 is used to avoid legacy dark placeholder tiles. Waypoints remain in `v2` and are not reset.
- Lower **Вид → Сканирование** to 2 or 4 blocks/tick for lower overhead.
- Switch **Вид → Качество** to **Быстро** for coarse but faster exploration.

## Limitations
Work in progress. Terrain uses sampled block colors and active resource-pack model textures, not a full block geometry renderer. Cave layers, polished 3D waypoint projections, complete Diorama-grade volumetric terrain, and exhaustive radar options are **not yet implemented**. Server-side decorations constructed from display entities or plugins without client-side block models cannot be mapped accurately from a resource pack alone. Fullscreen rendering and storage have been rewritten but still require in-game testing for visual correctness and performance. Radar only uses nearby entities already sent to the client; it does not discover unseen entities.

RoundMap is an independent fan project, not affiliated with Diorama.

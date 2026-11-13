# Technical reference: Diorama 0.10.0 for Minecraft 1.21.11

Reviewed the user-provided `diorama-0.10.0+1.21.11.jar` as a reference for RoundMap.
Original author: **Dara Adedeji**. Mod metadata identifies the source:
https://github.com/SunkenInTime/minecraft-orthogonal-map
Diorama is released under the **MIT License** (copyright 2026 Dara Adedeji).
The source and assets are **not** included here. If we ever copy or adapt
substantial original code or resources, retain its copyright and MIT notice.

## What the JAR actually contains

The package/asset inventory and Java class signatures establish these
subsystems (the details below describe architecture, not source code):
- `data/ChunkSnapshot`, `SavedChunks`, `SeenBlocks`: snapshots of voxel
  data and tracking of explored blocks rather than sampling one top block
  per map pixel.
- `bake/CraftBaker`, `TileBaker`, `render/gpu/GpuTileBaker`:
  tile-based rendering with CPU/GPU approaches and bounded background work.
- `render/MapLevel`, `MapMemory`, `MapViewport`,
  `data/TileStore`, `render/FogTextures`: world-specific cached map
  tiles, zoom levels, persistent terrain, view masking and fog of war.
- `waypoint/WaypointStore`, `WaypointOverlay`, `WaypointBeams`,
  `WorldProjection`: world-specific, colored, 3D waypoints and overlays.
- `SettingsScreen`, `Settings`, `RadarCategories`, `render/Radar`:
  extensive configuration and categorized entity radar.
- Assets include cave/fog/baking shaders, map frames, icons and translated
  settings. These are Diorama's assets and may not be relabeled as original
  RoundMap art.

## RoundMap's existing or in-progress equivalents

| System | RoundMap 0.2 alpha | Difference from Diorama |
|---|---|---|
| Circle HUD map | Dynamic GPU texture | Still plain surface MapColor |
| Full-screen map | Pan/zoom + cursor waypoints | No orientation/cave/map layers |
| Explored terrain | Per-world/per-dimension compressed chunk tiles | Coarse colors, no surface lighting or contour detail |
| Fog | Unknown map pixels masked | Lacks irregular softened fog boundary |
| Waypoints | World labels, coordinate and color storage | No 3D beam renderer or icon/folder support |
| Radar | Simple nearby category-colored dots | No mob heads or custom categories |
| Settings | 5 functional tabs | Fewer controls and presets |
| FPS | Bounded client tick reads + cached GUI textures | No asynchronous chunk snapshot processing or multilevel tile baking |

## Recommended implementation sequence

1. **Stability baseline:** compile, run in-game with Sodium/Iris,
   profile FPS and chunk loads. Fix errors before adding dependencies.
2. **Chunk snapshots + worker tiles:** take small snapshots on client
   thread; do expensive map-color/terrain shading on a worker; upload
   finished tiles on render thread; cap queues and memory.
3. **Level of detail:** chunk tile pyramid, viewport-based visible tiles,
   cache far-region overviews to disk; separate surface/cave layers.
4. **Quality:** smooth biome color gradients, water color and depth,
   height-edge lighting and outlines; do not merely increase scan rate.
5. **3D waypoints:** camera-matrix projection, height-aware X/Y/Z position,
   selectable icon/color, per-world folders, list/edit/delete.
6. **UX:** surface/cave tabs, world/dimension navigation, location and
   terrain tooltips, scalable controls, configurable fog/render quality.
7. **Performance:** bound CPU time per tick, split IO from rendering,
   coalesce dirty tile uploads, benchmark on dense modded landscapes.

## Non-goal

Do not ship Diorama's whole JAR rebranded as RoundMap. Keep attribution
when reusing MIT-covered source, and verify compatibility before release.

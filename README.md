# RoundMap — Minecraft Java 1.21.11 / Fabric

Experimental **source project** for a north-up circular minimap. Displays terrain surface colors, heading marker, cardinal directions and coordinates. Does not show players or mobs. No waypoints or full-screen map yet.

## Build a JAR

Requirements: Java 21, Gradle 9.2.1, internet access. From this directory:

```bash
gradle build
```

Find the mod in `build/libs/roundmap-0.1.0.jar` (do not use `-sources.jar`). Install Fabric Loader and Fabric API for Minecraft 1.21.11, then place the JAR in `.minecraft/mods`.

### Build without installing Gradle locally

Upload the project contents to a GitHub repository, open **Actions → Build Fabric JAR → Run workflow**. Download the `roundmap-jar` artifact after a successful run. This repository includes `.github/workflows/build.yml`.

## Limitations

This is an untested prototype, **not a verified compiled JAR**. Some Minecraft/Fabric API symbols or dependency versions may need adjustment when building. Rendering samples many world positions every frame and can lower FPS; a production version should cache terrain samples, throttle updates and use GPU textures. Unloaded chunks use a dark background. Server rules may forbid minimaps even if they are client-side.

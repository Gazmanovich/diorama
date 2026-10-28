# Building and testing Diorama

Needs JDK 25. Gradle downloads everything else.

## One source tree, many versions

One source tree builds a jar for each supported Minecraft version, using [Stonecutter](https://codeberg.org/stonecutter/stonecutter). The source is written for the newest version, 26.3. Names that only changed between versions are translated by the replacements in `stonecutter.gradle.kts`. Code that differs in other ways sits in `//? if >=26.1 { … //?} else { /* … *///?}` blocks, with the older version commented out. Keep the active version at 26.3: the replacements only translate in one direction.

```sh
./gradlew :26.3:build
./gradlew :26.2:build
./gradlew :26.1:build   # also runs on 26.1.1 and 26.1.2
./gradlew :1.21.11:build
./gradlew :1.21.1-neoforge:build   # NeoForge 21.1.178 or later; every NeoForge version is <minecraft>-neoforge
```

Each jar lands in `versions/<version>/build/libs/`. 1.21.11 ships obfuscated, so its build remaps the mod to the names a normal install uses. `./gradlew --continue compileJava` compiles every version; run it before merging anything that touches code shared across versions.

## Fabric and NeoForge

Versions named `<minecraft>-neoforge` build for NeoForge; the rest build for Fabric. NeoForge gets a jar for each Minecraft version it has a stable release for: 1.21, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.8, 1.21.10, 1.21.11, 26.1.2, 26.2 and 26.3.

From 1.21.6 the map draws through parts of the game that are private. Fabric API's access wideners open them for the Fabric jars, and a per-version access transformer, written by `build.neoforge.gradle.kts`, opens them for the NeoForge jars.

Everything but the entrypoints is shared: `dev.diorama.fabric` hooks the mod up to Fabric API and `dev.diorama.neoforge` to NeoForge's events. Each jar contains only its own package and metadata (`fabric.mod.json` or `META-INF/neoforge.mods.toml`). Other differences between the loaders sit in `//? if fabric` and `//? if neoforge` blocks.

## Game tests

`./gradlew :26.3:runClientGameTest` (or `:1.21.11:runClientGameTest`) launches the game, creates a world and runs the default test scenarios. Each scenario asserts what it can measure and saves screenshots to `versions/<version>/build/run/clientGameTest/screenshots/` for the rest.

Set `DIORAMA_TESTS` to a comma-separated list to run only some of them:

| Scenario | What it covers |
| --- | --- |
| `tour` | The minimap, the fullscreen map at three zooms, a resource reload and a cave slice |
| `radar` | Players and mobs on the radar |
| `xaero-icons` | Radar icons from Xaero's Minimap icon files ([docs](radar-icons.md)) |
| `model-faces` | Faces read off mob models |
| `waypoints` | Adding, editing, sharing and showing waypoints |
| `icons` | Waypoint icons: each one ships, sleeping in a bed keeps a Bed pin there, an icon is saved with its waypoint |
| `xaero-import` | Importing waypoints from Xaero's Minimap |
| `fog-of-war` | Cave slices under a server's fair-play rule |
| `settings` | The settings screen |
| `overlays` | Chunk grid, slime chunks and spawn spots |
| `screens` | The minimap while other screens are open |
| `dimensions` | The Nether and the End |
| `saved-map` | The map surviving a restart |
| `recreated-world` | A new world under a deleted world's name |
| `multiplayer` | Two worlds on one dedicated server |
| `server-id`, `tracked-players`, `realms` | Telling server worlds apart, players tracked by Xaero's server plugin, Realms |

Some scenarios only run when you name them: `styles`, `bench`, `gpu-bake`, `waystones` (needs the Waystones mod), `ftb-claims` (needs FTB Chunks, FTB Teams and FTB Library, plus Architectury before 26.1), `showcase` and `icon-waypoints` (the showcase castle with icon pins, for pictures; `DIORAMA_GIF=1` adds the frames of picking an icon), and `waypoint-clutter` (120 waypoints in the world, on the minimap and on the map, with and without the waypoint distance limit).

`DIORAMA_GIF=1` also saves frames for GIFs, and `DIORAMA_DEBUG=1` adds extra logging. `DIORAMA_TESTS=showcase` isn't part of the default run: it builds a castle beside a village and takes the screenshots for the store pages and this repo's README.

`./gradlew :1.21.1-neoforge:runClientGameTest` runs the same scenarios on NeoForge, all but the dedicated server ones. NeoForge has no client game test API, so they run on `src/gameshim`, the test-only stand-in for Fabric's that the Fabric jars before 1.21.4 use too.

`./gradlew :1.21.11:runProductionClientGameTest` runs the same tests against the remapped release jar in a normal install, with Fabric API as published. In development 1.21.11 uses Mojang's names, but a normal install uses intermediary ones, so anything that looks the game up by name only fails there.

## Translations

The game's text is in `src/main/resources/assets/diorama/lang/`. To add a language, copy `en_us.json` to your language's code (like `de_de.json`) and translate the values.

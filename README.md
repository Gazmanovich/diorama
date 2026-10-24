<img src="assets/icon.png" width="64" height="64" alt="">

# Diorama

Diorama is a client-side mod for Minecraft, on Fabric for 1.21 to 26.3 and on NeoForge for 1.21 to 26.3, that draws your world as an angled voxel map, like a baked diorama, in a minimap and a fullscreen map you can pan and zoom.

The map is ray traced from the chunk data your client already has. The camera looks north and down at 50°, so you see block tops and their south faces. Each pixel's ray walks the real blocks, so overhangs, bridges and tree canopies come out right. A second ray toward a low sun casts long shadows, more rays measure how much sky each spot can see so the ground under trees and overhangs darkens, corners and creases where blocks meet are shaded, and higher ground is a little brighter, so hills still read as hills when you zoom far out. Water gets darker with depth. Tiles bake on background threads and only re-bake the parts an edit can affect.

Places the map has no data for sit under animated fog. Underground, the map switches to a cave slice that shows the first open space below a cut a few blocks above your head, lit by the game's own block light. If a server sends Xaero's fair-play chat code (`§f§a§i§r§x§a§e§r§o`), cave slices only show blocks you've had line of sight to.

## What it does

- **Minimap** in a corner of your choice, with a compass, coordinates and biome. `=` and `-` zoom it.
- **Fullscreen map** you can pan and zoom, from close-up blocks out to about 10,000 blocks across.
- **Radar** for other players and mobs, each shown as its own head in pixel art, outlined red, yellow or green for hostile, neutral or passive, with markers for whether they're above or below you. Framed faces or plain dots instead, the icons' size and where player names show are settings. Mobs without a face of their own show as dots, unless a mod or resource pack has [Xaero's Minimap icon files](#radar-icons-from-xaeros-minimap-files) for them.
- **Waypoints** on the map and as labels in the world, plus a marker wherever you die. Share one in chat from its edit screen; shared waypoints, including Xaero's, get an [Add] link in chat. Teleport to one if you're allowed to use `/tp`. Coming from Xaero's Minimap? The waypoint list offers to import its waypoints for the world you're in.
- **Saved map.** Everything your client loads is saved per world and dimension, so the map is still there next time, and you can zoom out over everywhere you've been.
- **The Nether** from above: the sky view starts under the bedrock roof and shows everything below it, lit from above as if the roof were lifted off, with each biome's fog thickening the deeper the ground lies. Unexplored places sit under smoke instead of clouds.
- **Overlays** on the fullscreen map: chunk grid, slime chunks (singleplayer) and the spots where monsters can spawn.
- **Waypoint folders**: every dimension has its own, and you can make your own (say, "Overworld secret"), hide a whole folder from the maps, rename and delete them.
- **Waypoint colours**: each new waypoint gets a random colour; the editor offers Diorama's palette and the 16 dye colours, hue and shade bars, or any colour by hex.
- **Settings** for all of the above, including the minimap's frame, your arrow's colour and what waypoint labels in the world show, from a button on the fullscreen map, from Mod Menu's Configure button on Fabric, or from the mod list's Config button on NeoForge.

Saved maps and waypoints live in `.minecraft/diorama/`, one folder per world. On a server, each world behind its address gets its own folder, told apart by the world seed the server sends, so a network's lobby and survival worlds, or a map reset, don't mix.

## Radar icons from Xaero's Minimap files

Diorama reads the radar icon files made for Xaero's Minimap, so icons a mod or resource pack already ships for Xaero's show on Diorama's radar too. You don't need Xaero's Minimap installed. Diorama looks for the file for a mob `<namespace>:<name>` at:

```
assets/xaerominimap/entity/icon/definition/<namespace>/<name>.json
```

in mod jars and resource packs, and reads them again when resource packs reload (F3+T). The top resource pack's file wins. A file maps variant IDs, or `default`, to an icon:

```json
{"variants": {"default": "outlined_sprite:my_mod/my_mob.png"}}
```

| Icon | On Diorama's radar |
| --- | --- |
| `normal_sprite:<path>` | The image at `assets/xaerominimap/entity/icon/sprite/<path>`, cropped to its visible pixels and drawn as pixel art about the size of a mob's head, with a drop shadow. Images much bigger than a head are shrunk by a whole factor (to a half, a third...), so pixel art stays crisp. |
| `outlined_sprite:<path>` | The same, with a one-pixel outline around the image's shape. The outline is red, yellow or green for hostile, neutral or passive with the radar's "Colour by mob type" setting on (the default), black with it off. |
| `sprite:<path>` | Xaero's older form of `normal_sprite`. Xaero's Minimap draws its image upside down, and so does Diorama. |
| `item:<item id>` | That item, in a head-sized slot outlined like the heads, or the dot if there's no such item. Items don't fade with height as the heads do. |
| `dot` | The coloured dot. |
| `model`, `model:<n>`, `item` | The mob's own head from Diorama, or a dot if Diorama has none. Xaero's model settings (`modelConfigs`) don't apply. |

In the framed radar style, images and items sit in the coloured frame; in the dots style, every mob is a dot.

Variants: Diorama matches a key that is the mob's texture, such as `"minecraft:textures/entity/fox/fox.png"`, which is the variant ID Xaero's Minimap uses for most mobs. Diorama can't build the longer IDs Xaero's gives some vanilla mobs (`...iron_golem.png%MEDIUM`) or the ones a mod makes with its own `variantMethod`, so those mobs get the `default` icon. Other keys in the file (`_comment`, `modelConfigs`, `variantMethod`) are ignored. An entry Diorama can't read is skipped, as Xaero's skips it, and a file that isn't valid JSON is skipped whole; either way the log gets one line for the file.

## Keys

- `M` opens the fullscreen map. Drag to pan, scroll to zoom, right-click to add a waypoint, click a waypoint to edit it.
- In the fullscreen map, `C` toggles the cave slice, `PgUp`/`PgDn` move the slice by 4 blocks, `U` lists waypoints, and `Space` recenters on you.
- `B` adds a waypoint where you stand, and `U` opens the waypoint list.
- `=` and `-` zoom the minimap. Map settings and hiding the minimap have keys too, unbound by default.

## Building

Needs JDK 25. Gradle downloads everything else.

One source tree builds a jar for each supported Minecraft version, using [Stonecutter](https://codeberg.org/stonecutter/stonecutter). The source is written for the newest version, 26.3. Names that only changed between versions are translated by the replacements in `stonecutter.gradle.kts`. Code that differs in other ways sits in `//? if >=26.1 { … //?} else { /* … *///?}` blocks, with the older version commented out. Keep the active version at 26.3: the replacements only translate in one direction.

```sh
./gradlew :26.3:build
./gradlew :26.2:build
./gradlew :26.1:build   # also runs on 26.1.1 and 26.1.2
./gradlew :1.21.11:build
./gradlew :1.21.1-neoforge:build   # NeoForge 21.1.178 or later; every NeoForge version is <minecraft>-neoforge
```

Each jar lands in `versions/<version>/build/libs/`. 1.21.11 ships obfuscated, so its build remaps the mod to the names a normal install uses.

Versions named `<minecraft>-neoforge` build for NeoForge; the rest build for Fabric. NeoForge gets a jar for each Minecraft version it has a stable release for: 1.21, 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.8, 1.21.10, 1.21.11, 26.1.2, 26.2 and 26.3. From 1.21.6 the map draws through parts of the game that are private; Fabric API's access wideners open them for the Fabric jars, and a per-version access transformer, written by `build.neoforge.gradle.kts`, opens them for the NeoForge jars. Everything but the entrypoints is shared: `dev.diorama.fabric` hooks the mod up to Fabric API and `dev.diorama.neoforge` to NeoForge's events, and each jar contains only its own package and metadata (`fabric.mod.json` or `META-INF/neoforge.mods.toml`). Other differences between the loaders sit in `//? if fabric` and `//? if neoforge` blocks.

`./gradlew :26.3:runClientGameTest` (or `:1.21.11:runClientGameTest`) launches the game, creates a world and runs every test scenario: the map views, radar, waypoints, importing waypoints from Xaero's Minimap, fog of war, settings, overlays, the Nether and the End, saving the map across a restart, and two worlds on one dedicated server. Each scenario asserts what it can measure and saves screenshots to `versions/<version>/build/run/clientGameTest/screenshots/` for the rest. Set `DIORAMA_TESTS` to a comma-separated list (`tour`, `radar`, `waypoints`, `xaero-import`, `fog-of-war`, `settings`, `overlays`, `dimensions`, `saved-map`, `multiplayer`) to run only some, `DIORAMA_GIF=1` to also save frames for GIFs, and `DIORAMA_DEBUG=1` for extra logging. `DIORAMA_TESTS=showcase` isn't part of the default run: it builds a castle beside a village and takes the screenshots for the store page.

`./gradlew :1.21.1-neoforge:runClientGameTest` runs the same scenarios on NeoForge, all but the dedicated server one. NeoForge has no client game test API, so they run on `src/gameshim`, the test-only stand-in for Fabric's that the Fabric jars before 1.21.4 use too.

`./gradlew :1.21.11:runProductionClientGameTest` runs the same tests against the remapped release jar in a normal install, with Fabric API as published. In development 1.21.11 uses Mojang's names, but a normal install uses intermediary ones, so anything that looks the game up by name only fails there.

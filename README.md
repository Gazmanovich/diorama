<img src="assets/icon.svg" width="64" height="64" alt="">

# minecraft-orthogonal-map

A client-side Fabric mod for Minecraft 26.3 that draws your world as an angled voxel map, like a baked diorama, in a minimap and a fullscreen map you can pan and zoom.

The map is ray traced from the chunk data your client already has. The camera looks north and down at 60°, so you see block tops and their south faces. Each pixel's ray walks the real blocks, so overhangs, bridges and tree canopies come out right. Shading comes from a second ray toward the sun for shadows, corner ambient occlusion, and water depth. Tiles bake on background threads and only re-bake the parts an edit can affect.

Places the map has no data for sit under animated fog. Underground, the map switches to a cave slice that shows the first open space below a cut a few blocks above your head, lit by the game's own block light. If a server sends Xaero's fair-play chat code (`§f§a§i§r§x§a§e§r§o`), cave slices only show blocks you've had line of sight to.

## What it does

- **Minimap** in a corner of your choice, with a compass, coordinates and biome. `=` and `-` zoom it.
- **Fullscreen map** you can pan and zoom, from close-up blocks out to about 10,000 blocks across.
- **Radar** for other players (with their faces) and hostile, neutral and passive mobs, with markers for whether they're above or below you.
- **Waypoints** on the map and as labels in the world, plus a marker wherever you die.
- **Saved map.** Everything your client loads is saved per world and dimension, so the map is still there next time, and you can zoom out over everywhere you've been.
- **Overlays** on the fullscreen map: chunk grid, slime chunks (singleplayer) and the spots where monsters can spawn.
- **Settings** for all of the above, from a button on the fullscreen map.

Saved maps and waypoints live in `.minecraft/orthomap/`, one folder per world.

## Keys

- `M` opens the fullscreen map. Drag to pan, scroll to zoom, right-click to add a waypoint, click a waypoint to edit it.
- In the fullscreen map, `C` toggles the cave slice, `PgUp`/`PgDn` move the slice by 4 blocks, `U` lists waypoints, and `Space` recenters on you.
- `B` adds a waypoint where you stand, and `U` opens the waypoint list.
- `=` and `-` zoom the minimap. Map settings and hiding the minimap have keys too, unbound by default.

## Building

Needs JDK 25. Gradle downloads everything else.

```sh
./gradlew build
```

The mod jar lands in `build/libs/`.

`./gradlew runClientGameTest` launches the game, creates a world and runs every test scenario: the map views, radar, waypoints, fog of war, settings, overlays, and saving the map across a restart. Each scenario asserts what it can measure and saves screenshots to `build/run/clientGameTest/screenshots/` for the rest. Set `ORTHOMAP_TESTS` to a comma-separated list (`tour`, `radar`, `waypoints`, `fog-of-war`, `settings`, `overlays`, `saved-map`) to run only some, `ORTHOMAP_GIF=1` to also save frames for GIFs, and `ORTHOMAP_DEBUG=1` for extra logging.

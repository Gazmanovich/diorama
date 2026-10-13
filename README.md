<img src="assets/icon.svg" width="64" height="64" alt="">

# minecraft-orthogonal-map

A client-side Fabric mod for Minecraft 26.3 that draws your world as an angled voxel map, like a baked diorama, in a minimap and a fullscreen map you can pan and zoom.

The map is ray traced from the chunk data your client already has. The camera looks north and down at 60°, so you see block tops and their south faces. Each pixel's ray walks the real blocks, so overhangs, bridges and tree canopies come out right. Shading comes from a second ray toward the sun for shadows, corner ambient occlusion, and water depth. Tiles bake on background threads and only re-bake the parts an edit can affect.

Places the map has no data for sit under animated fog. Underground, the map switches to a cave slice that shows the first open space below a cut a few blocks above your head, lit by the game's own block light. If a server sends Xaero's fair-play chat code (`§f§a§i§r§x§a§e§r§o`), cave slices only show blocks you've had line of sight to.

## Keys

- `M` opens the fullscreen map. Drag to pan, scroll to zoom.
- In the fullscreen map, `C` toggles the cave slice, `PgUp`/`PgDn` move the slice by 4 blocks, and `Space` recenters on you.

## Building

Needs JDK 25. Gradle downloads everything else.

```sh
./gradlew build
```

The mod jar lands in `build/libs/`.

`./gradlew runClientGameTest` launches the game, creates a world, walks through the map views and saves screenshots to `build/run/clientGameTest/screenshots/`. It doesn't assert anything; the screenshots are what you look at. Set `ORTHOMAP_GIF=1` to also save frames for a panning GIF, and `ORTHOMAP_DEBUG=1` for extra logging.

## Not done yet

- The map forgets everything when you leave the world. Nothing is saved to disk.
- Zooming out stops at about 0.5 screen pixels per block. Seeing a whole explored world needs a saved tile pyramid.
- No waypoints, other players, or settings screen.

# Your map on a second screen

Diorama can show your map in a web browser while you play: on a second monitor, a tablet or phone beside you, or the other screen of a handheld. It's your own map, as the game has it (what you've explored, in your resource pack), with your arrow moving on it. It works in singleplayer and on servers, and the server needs nothing.

It's off until you turn it on.

## Turn it on

1. Open Diorama's settings, the Overlays tab, and set **Web map** to:
   - **This computer** to open it in a browser on the computer the game runs on, such as on a second monitor. Nothing else can reach it.
   - **My network** to open it from a phone, a tablet or another computer on the same network. Anyone on that network can then see your map and where you are, so leave this off on networks you don't trust. The first time, Windows may ask whether Java may be reached on your network: allow it for private networks.
2. Chat shows the address, and again each time you join a world. Click it to open the page on this computer, or type it into the browser on the other device.

The address looks like `http://localhost:8100/` for this computer, or `http://192.168.1.20:8100/` for your network, where the numbers before the colon are your computer's address there.

## The page

- Your arrow shows where you are and which way you look. **Follow** keeps the map centred on you; moving the map turns it off, and the button turns it back on.
- Drag to move, scroll or pinch to zoom, or use the + and − buttons.
- The map fills in a few seconds after the one in the game does. It shows the map of the dimension you're in, and switches when you go through a portal.
- Close the game, leave the world, or go to a dimension where the map is off, and the page waits for you to come back.

## The port

The page is served on port 8100. If another program has it, Diorama takes the next free one up to 8109, and chat shows the one it took. To use another port, set `webMapPort` in `diorama/config.json` in your game folder.

## A server's own web map

A server with Diorama can also draw a map of its whole world for anyone to open: see [The web map](web-map.md). That one is the server owner's to turn on, and doesn't show where players are.

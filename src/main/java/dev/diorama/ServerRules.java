package dev.diorama;

import dev.diorama.share.ServerLocks;
import net.minecraft.network.chat.Component;

/**
 * What the server allows minimaps to show. Servers use the chat codes Xaero's Minimap defined,
 * which players and server owners already know, sent in a system message:
 * <ul>
 *   <li>{@link #FAIR_PLAY}: no cave maps of unseen caves and no entity radar. Diorama turns cave
 *       slices into fog of war instead of switching them off, and hides the radar.</li>
 *   <li>{@link #NETHER_ALLOWED}: cave maps are fine in the Nether despite fair play.</li>
 *   <li>{@link #NO_MINIMAP}: no minimap at all.</li>
 *   <li>{@link #RESET}: clears all of the above.</li>
 * </ul>
 * The player can also turn the map off in dimensions they pick ({@link Settings#mapOffDimensions}),
 * and so can the server's file, for a dimension that's about finding the way without one.
 * A server with Diorama can also lock settings in its {@code diorama-server.properties} (see
 * {@link ServerLocks}), which it sends as the player joins. Either way the player's own settings
 * stay as they saved them, and the server's rules end when they leave.
 */
public final class ServerRules {
	static final String FAIR_PLAY = "§f§a§i§r§x§a§e§r§o";
	static final String RESET = "§r§e§s§e§t§x§a§e§r§o";
	static final String NETHER_ALLOWED = "§x§a§e§r§o§m§m§n§e§t§h§e§r§i§s§f§a§i§r";
	static final String NO_MINIMAP = "§n§o§m§i§n§i§m§a§p";

	private static boolean fairPlay;
	private static boolean netherAllowed;
	private static boolean noMinimap;
	private static ServerLocks locks = ServerLocks.NONE;
	private static String lastMapOffMessage = "";
	private static int mapOffMessages;
	/** Player setting: fog of war everywhere, even where the server allows full cave maps. */
	private static boolean alwaysFogOfWar = Boolean.getBoolean("diorama.fogOfWar");

	private ServerRules() {
	}

	public static void onServerMessage(Component message) {
		String text = message.getString();
		if (text.contains(RESET)) {
			fairPlay = false;
			netherAllowed = false;
			noMinimap = false;
		}
		if (text.contains(FAIR_PLAY)) {
			fairPlay = true;
			Diorama.LOGGER.info("Server asked for fair play: cave slices show only what you've seen, and the radar is off");
		}
		if (text.contains(NETHER_ALLOWED)) {
			netherAllowed = true;
		}
		if (text.contains(NO_MINIMAP)) {
			noMinimap = true;
			Diorama.LOGGER.info("Server disabled minimaps");
		}
	}

	/** The settings the server's Diorama locks, as the player joins. */
	public static void onLocks(ServerLocks server) {
		locks = server;
		Diorama.LOGGER.info("The server locks map settings: {}", server);
	}

	public static void onDisconnect() {
		fairPlay = false;
		netherAllowed = false;
		noMinimap = false;
		locks = ServerLocks.NONE;
	}

	/** Whether cave slices in this dimension should only show blocks the player has seen. */
	public static boolean fogOfWar(boolean nether) {
		return fogOfWar(nether, nether);
	}

	/**
	 * Whether this dimension's cave slices, or its view under a roof ({@code roofed}, as in the
	 * Nether), should only show blocks the player has seen. With cave maps locked off, the view
	 * under a roof is the only map of such a dimension, so it stays, as far as the player has seen.
	 */
	public static boolean fogOfWar(boolean nether, boolean roofed) {
		return alwaysFogOfWar || locks.caveFogOfWar() || fairPlay && !(nether && netherAllowed) || roofed && !locks.caveMapsAllowed();
	}

	/** Whether the server makes cave slices fog of war everywhere, so the player's own setting doesn't count. */
	public static boolean fogOfWarForced() {
		return locks.caveFogOfWar() || fairPlay && !netherAllowed;
	}

	/** Whether the map may show cave slices at all. */
	public static boolean caveMapsAllowed() {
		return locks.caveMapsAllowed();
	}

	/** Whether the map may show other players and mobs. */
	public static boolean radarAllowed() {
		return !fairPlay && locks.radarAllowed();
	}

	/** Whether there's a minimap and a map where the player is: the server allows them, and the dimension isn't one with the map off. */
	public static boolean minimapAllowed() {
		return !noMinimap && !mapOffHere();
	}

	/** Whether the map is off in this dimension, by the player's settings or the server's. */
	public static boolean mapOff(String dimension) {
		return mapOffByServer(dimension) || Settings.get().mapOffDimensions.contains(dimension);
	}

	/** Whether the server's settings, or in singleplayer a modpack's file, turn the map off in this dimension. */
	public static boolean mapOffByServer(String dimension) {
		return locks.mapOffDimensions().contains(dimension);
	}

	/** Whether the map is off in the dimension the player is in. */
	public static boolean mapOffHere() {
		net.minecraft.client.multiplayer.ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
		return level != null && mapOff(level.dimension().identifier().toString());
	}

	/**
	 * Tells the player, over the hotbar, why a map key did nothing in this dimension and where that's
	 * set. True if the map is off here.
	 */
	public static boolean sayMapOffHere(net.minecraft.client.Minecraft mc) {
		if (mc.level == null || !mapOffHere()) {
			return false;
		}
		String here = mc.level.dimension().identifier().toString();
		Component message = Component.translatable(!mapOffByServer(here) ? "diorama.map_off.here"
			: mc.hasSingleplayerServer() ? "diorama.map_off.here_file" : "diorama.map_off.here_server");
		lastMapOffMessage = message.getString();
		mapOffMessages++;
		//? if >=26.1 {
		mc.gui.hud.setOverlayMessage(message, false);
		//?} else {
		/*mc.gui.setOverlayMessage(message, false);
		*///?}
		return true;
	}

	/** For tests: what {@link #sayMapOffHere} last told the player, after how many times it has. */
	public static String lastMapOffMessage() {
		return mapOffMessages + ": " + lastMapOffMessage;
	}

	public static void setAlwaysFogOfWar(boolean value) {
		alwaysFogOfWar = value;
	}

	public static boolean alwaysFogOfWar() {
		return alwaysFogOfWar;
	}
}

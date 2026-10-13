package dev.orthomap;

import net.minecraft.network.chat.Component;

/**
 * What the server allows for cave maps. Servers use the chat codes Xaero's Minimap defined, which
 * players and server owners already know: a system message containing {@link #FAIR_PLAY} asks
 * minimaps not to show caves the player hasn't seen. Orthomap honours it by switching cave slices
 * to fog of war instead of turning them off.
 */
public final class CaveRules {
	static final String FAIR_PLAY = "§f§a§i§r§x§a§e§r§o";
	static final String RESET = "§r§e§s§e§t§x§a§e§r§o";
	static final String NETHER_ALLOWED = "§x§a§e§r§o§m§m§n§e§t§h§e§r§i§s§f§a§i§r";

	private static boolean fairPlay;
	private static boolean netherAllowed;
	/** Player setting: fog of war everywhere, even where the server allows full cave maps. */
	private static boolean alwaysFogOfWar = Boolean.getBoolean("orthomap.fogOfWar");

	private CaveRules() {
	}

	public static void onServerMessage(Component message) {
		String text = message.getString();
		if (text.contains(RESET)) {
			fairPlay = false;
			netherAllowed = false;
		}
		if (text.contains(FAIR_PLAY)) {
			fairPlay = true;
			Orthomap.LOGGER.info("Server asked for fair-play cave maps; cave slices now show only what you've seen");
		}
		if (text.contains(NETHER_ALLOWED)) {
			netherAllowed = true;
		}
	}

	public static void onDisconnect() {
		fairPlay = false;
		netherAllowed = false;
	}

	/** Whether cave slices in this dimension should only show blocks the player has seen. */
	public static boolean fogOfWar(boolean nether) {
		return alwaysFogOfWar || fairPlay && !(nether && netherAllowed);
	}

	public static void setAlwaysFogOfWar(boolean value) {
		alwaysFogOfWar = value;
	}

	public static boolean alwaysFogOfWar() {
		return alwaysFogOfWar;
	}
}

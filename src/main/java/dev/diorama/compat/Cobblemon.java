package dev.diorama.compat;

import dev.diorama.Diorama;
import java.lang.reflect.Method;
//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?} else {
/*import net.neoforged.fml.ModList;
*///?}

/**
 * Whether the player is in a Cobblemon battle. Its battle screen fills the bottom of the screen,
 * and the battlers' tiles sit in both top corners for the whole battle, also once Esc has closed
 * the screen to look around, so the minimap stays out of the way until the battle ends (#147).
 *
 * <p>Diorama doesn't build against Cobblemon. {@code CobblemonClient.INSTANCE.getBattle()} is the
 * client's battle, spectated ones included, or null; it's the same from Cobblemon 1.5 (1.20.1) to
 * 1.8 (1.21.1). If it isn't there, this logs once and stays off.
 */
public final class Cobblemon {
	private static boolean started;
	private static Object client;
	private static Method battle;

	private Cobblemon() {
	}

	/** Called on the render thread, where Cobblemon starts and ends battles. */
	public static boolean inBattle() {
		if (!started) {
			started = true;
			if (installed()) {
				try {
					Class<?> type = Class.forName("com.cobblemon.mod.common.client.CobblemonClient");
					client = type.getField("INSTANCE").get(null);
					battle = type.getMethod("getBattle");
				} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
					Diorama.LOGGER.warn("Cobblemon is installed, but its battles couldn't be found; the minimap won't step aside for them", e);
					battle = null;
				}
			}
		}
		if (battle == null) {
			return false;
		}
		try {
			return battle.invoke(client) != null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't read Cobblemon's battle; the minimap won't step aside for battles", e);
			battle = null;
			return false;
		}
	}

	private static boolean installed() {
		//? if fabric {
		return FabricLoader.getInstance().isModLoaded("cobblemon");
		//?} else {
		/*return ModList.get().isLoaded("cobblemon");
		*///?}
	}
}

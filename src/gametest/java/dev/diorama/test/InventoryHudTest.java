package dev.diorama.test;

import dev.diorama.Settings;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * With the real Inventory HUD+ installed (opt-in: {@code inventory-hud}): an effect of each kind,
 * first in the game's own icons in the top-right corner, where the minimap moves below them, then
 * with Inventory HUD+ drawing them on the left instead, where the minimap stays in the corner.
 */
final class InventoryHudTest {
	private InventoryHudTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		context.runOnClient(mc -> {
			Settings s = Settings.get();
			s.minimapCorner = Settings.Corner.TOP_RIGHT;
			s.minimapSize = 96;
			s.minimapPixelsPerBlock = 2;
			s.save();
		});
		context.runOnClient(mc -> potionHud(false));
		world.getServer().runCommand("effect give @a speed 60 0");
		world.getServer().runCommand("effect give @a weakness 60 0");
		context.waitTicks(20);
		context.takeScreenshot("inventory-hud-01-game-icons");
		context.runOnClient(mc -> potionHud(true));
		context.waitTicks(20);
		context.takeScreenshot("inventory-hud-02-icons-moved");
		context.runOnClient(mc -> potionHud(false));
		world.getServer().runCommand("effect clear @a");
	}

	/** Inventory HUD+'s own potion HUD, which stops the game drawing its icons while it's on. */
	private static void potionHud(boolean on) {
		try {
			// As its settings screen does it: the saved setting, and the switch its HUD reads.
			Class<?> common = Class.forName("dlovin.inventoryhud.InventoryHUDCommon");
			Object config = common.getMethod("getConfig").invoke(null);
			Class.forName("dlovin.inventoryhud.platform.ConfigAccess").getMethod("setPotions", boolean.class).invoke(config, on);
			common.getField("potionHUD").setBoolean(null, on);
		} catch (ClassNotFoundException e) {
			throw new AssertionError("inventory-hud needs Inventory HUD+ installed", e);
		} catch (ReflectiveOperationException e) {
			throw new AssertionError("Inventory HUD+ has no potion HUD switch where this test looks", e);
		}
	}
}

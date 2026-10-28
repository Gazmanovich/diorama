package dev.diorama.gameshim.forge;

import java.util.ArrayList;
import java.util.List;

import dev.diorama.gameshim.GameShim;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.forgespi.language.IModInfo;

/**
 * The shim's entrypoint on Forge, which has no entrypoints like Fabric's: a test mod lists its
 * test classes in a {@code fabric-client-gametest} mod property in its mods.toml, as on NeoForge.
 */
@Mod("diorama_gameshim")
public final class ForgeShim {
	public ForgeShim() {
		if (!GameShim.enabled()) {
			return;
		}
		GameShim.LOGGER.info("Diorama game shim: running the {} tests once the game has loaded", GameShim.ENTRYPOINT);
		MinecraftForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, TickEvent.ClientTickEvent.class, event -> {
			if (event.phase == TickEvent.Phase.END) {
				GameShim.onTickEnd(Minecraft.getInstance(), ForgeShim::tests);
			}
		});
	}

	private static List<GameShim.Test> tests() {
		List<GameShim.Test> tests = new ArrayList<>();
		for (IModInfo mod : ModList.get().getMods()) {
			if (!(mod.getModProperties().get(GameShim.ENTRYPOINT) instanceof List<?> classes)) {
				continue;
			}
			for (Object className : classes) {
				try {
					Class<?> type = Class.forName(className.toString(), true, ForgeShim.class.getClassLoader());
					FabricClientGameTest test = (FabricClientGameTest) type.getDeclaredConstructor().newInstance();
					tests.add(new GameShim.Test(mod.getModId() + " " + type.getName(), test));
				} catch (ReflectiveOperationException e) {
					throw new IllegalStateException("Can't create client game test " + className + " of " + mod.getModId(), e);
				}
			}
		}
		return tests;
	}
}

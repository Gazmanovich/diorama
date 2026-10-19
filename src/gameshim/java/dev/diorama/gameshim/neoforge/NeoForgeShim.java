package dev.diorama.gameshim.neoforge;

import java.util.ArrayList;
import java.util.List;

import dev.diorama.gameshim.GameShim;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforgespi.language.IModInfo;

/**
 * The shim's entrypoint on NeoForge, which has no entrypoints like Fabric's: a test mod lists its
 * test classes in a {@code fabric-client-gametest} mod property in its neoforge.mods.toml.
 */
@Mod(value = "diorama_gameshim", dist = Dist.CLIENT)
public final class NeoForgeShim {
	public NeoForgeShim() {
		if (!GameShim.enabled()) {
			return;
		}
		GameShim.LOGGER.info("Diorama game shim: running the {} tests once the game has loaded", GameShim.ENTRYPOINT);
		NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> GameShim.onTickEnd(Minecraft.getInstance(), NeoForgeShim::tests));
	}

	private static List<GameShim.Test> tests() {
		List<GameShim.Test> tests = new ArrayList<>();
		for (IModInfo mod : ModList.get().getMods()) {
			if (!(mod.getModProperties().get(GameShim.ENTRYPOINT) instanceof List<?> classes)) {
				continue;
			}
			for (Object className : classes) {
				try {
					Class<?> type = Class.forName(className.toString(), true, NeoForgeShim.class.getClassLoader());
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

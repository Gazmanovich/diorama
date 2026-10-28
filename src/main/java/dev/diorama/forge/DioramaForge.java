package dev.diorama.forge;

import dev.diorama.Diorama;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Diorama's entrypoint on Forge. Only the Forge jars contain this package.
 */
@Mod(Diorama.MOD_ID)
public final class DioramaForge {
	public DioramaForge() {
		// A client mod: on a dedicated server it does nothing. Forge's @Mod has no side of its own,
		// and Forge reads this class's methods before it calls this constructor, so everything that
		// names client classes lives in DioramaForgeClient, which only loads on the client.
		if (FMLEnvironment.dist == Dist.CLIENT) {
			DioramaForgeClient.init();
		}
	}
}

//? if <1.21.6 {
/*package dev.diorama.compat;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;

// Before 1.21.6 Fabric API had no HUD element interface; the HUD draws from HudRenderCallback
// instead (DioramaFabric), or from GUI layers on NeoForge (DioramaNeoForge). This stands in for
// Fabric's HudElement, which the renames point at.
public interface HudElement {
	void render(GuiGraphics graphics, DeltaTracker deltaTracker);
}
*///?}

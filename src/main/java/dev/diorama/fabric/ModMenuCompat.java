package dev.diorama.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.diorama.SettingsScreen;

/** Mod Menu's "Configure" button opens the map settings. Only loaded when Mod Menu is installed. */
public final class ModMenuCompat implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return SettingsScreen::new;
	}
}

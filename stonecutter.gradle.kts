plugins {
	id("dev.kikugie.stonecutter")
	id("net.fabricmc.fabric-loom") version "1.18-SNAPSHOT" apply false
	id("net.fabricmc.fabric-loom-remap") version "1.18-SNAPSHOT" apply false
}

// The version the source files are written for. Keep it at the newest version: the renames below
// only translate newest names to older ones, so the source can't be switched to an older version
// and back. Version-specific code that isn't a rename goes in //? if blocks.
stonecutter active "26.3"

stonecutter parameters {
	// Names that changed without changing behaviour, as regexes from the 26.x spelling to the
	// 1.21.11 one. One-way: the reverse pattern never matches.
	val before26 = listOf(
		// The GUI drawing context and its text methods.
		"\\bGuiGraphicsExtractor\\b" to "GuiGraphics",
		"\\bgraphics\\.text\\(" to "graphics.drawString(",
		"\\bgraphics\\.centeredText\\(" to "graphics.drawCenteredString(",
		// Screens and HUD elements render instead of extracting render state.
		"\\bextractRenderState\\(" to "render(",
		"\\bextractBackground\\(" to "renderBackground(",
		"import net\\.minecraft\\.client\\.renderer\\.state\\.gui\\.GuiElementRenderState;" to "import net.minecraft.client.gui.render.state.GuiElementRenderState;",
		"\\.addGuiElement\\(" to ".submitGuiElement(",
		// The open screen and the chat lived on Minecraft and Gui directly.
		"\\.gui\\.setScreen\\(" to ".setScreen(",
		"\\.gui\\.screen\\(\\)" to ".screen",
		"\\.gui\\.hud\\." to ".gui.",
		// ChunkPos was a class with fields.
		"getPos\\(\\)\\.([xz])\\(\\)" to "getPos().$1",
		// Entity type constants lived on EntityType.
		"\\bEntityTypes\\.([A-Z][A-Z0-9_]*)" to "EntityType.$1",
		"import net\\.minecraft\\.world\\.entity\\.EntityTypes;" to "import net.minecraft.world.entity.EntityType;",
		// Fabric API's key binding and client command helpers.
		"import net\\.fabricmc\\.fabric\\.api\\.client\\.keymapping\\.v1\\.KeyMappingHelper;" to "import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;",
		"\\bKeyMappingHelper\\.registerKeyMapping\\(" to "KeyBindingHelper.registerKeyBinding(",
		"\\bClientCommands\\b" to "ClientCommandManager",
		// The client game test API: a dedicated server's connection had no type of its own.
		"\\bTestDedicatedServerConnection\\b" to "TestServerConnection",
	)
	for ((pattern, older) in before26) {
		replacements.regex(current.parsed < "26.1") {
			replace(pattern, older, "(?!)", "never")
		}
	}
}

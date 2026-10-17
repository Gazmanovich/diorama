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
	// Names that changed without changing behaviour, as regexes from the newer spelling to the
	// older one, applied to every version before the rename. One-way: the reverse pattern never
	// matches. Renamed in 26.1:
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
		// ChunkPos was a class with fields.
		"getPos\\(\\)\\.([xz])\\(\\)" to "getPos().$1",
		// Fabric API's key binding and client command helpers.
		"import net\\.fabricmc\\.fabric\\.api\\.client\\.keymapping\\.v1\\.KeyMappingHelper;" to "import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;",
		"\\bKeyMappingHelper\\.registerKeyMapping\\(" to "KeyBindingHelper.registerKeyBinding(",
		"\\bClientCommands\\b" to "ClientCommandManager",
		// Chat lines moved into the multiplayer chat package.
		"net\\.minecraft\\.client\\.multiplayer\\.chat\\.GuiMessage\\b" to "net.minecraft.client.GuiMessage",
	)
	// Renamed in 26.2.
	val before262 = listOf(
		// The open screen and the chat lived on Minecraft and Gui directly.
		"\\.gui\\.setScreen\\(" to ".setScreen(",
		"\\.gui\\.screen\\(\\)" to ".screen",
		"\\.gui\\.hud\\." to ".gui.",
		// Entity type constants lived on EntityType.
		"\\bEntityTypes\\.([A-Z][A-Z0-9_]*)" to "EntityType.$1",
		"import net\\.minecraft\\.world\\.entity\\.EntityTypes;" to "import net.minecraft.world.entity.EntityType;",
		// The camera had a getter.
		"\\.mainCamera\\(\\)" to ".getMainCamera()",
		// The client game test API: a dedicated server's connection had no type of its own.
		"\\bTestDedicatedServerConnection\\b" to "TestServerConnection",
	)
	// Renamed in 26.3: the GPU API moved from com.mojang.blaze3d to com.mojang.renderpearl.api.
	val before263 = listOf(
		"\\bcom\\.mojang\\.renderpearl\\.api\\.GpuFormat\\b" to "com.mojang.blaze3d.GpuFormat",
		"\\bcom\\.mojang\\.renderpearl\\.api\\.device\\.GpuDevice\\b" to "com.mojang.blaze3d.systems.GpuDevice",
		"\\bcom\\.mojang\\.renderpearl\\.api\\.pipeline\\.PrimitiveTopology\\b" to "com.mojang.blaze3d.PrimitiveTopology",
		"\\bcom\\.mojang\\.renderpearl\\.api\\.pipeline\\." to "com.mojang.blaze3d.pipeline.",
		"\\bcom\\.mojang\\.renderpearl\\.api\\.textures\\." to "com.mojang.blaze3d.textures.",
	)
	for ((renamedIn, renames) in listOf("26.1" to before26, "26.2" to before262, "26.3" to before263)) {
		for ((pattern, older) in renames) {
			replacements.regex(current.parsed < renamedIn) {
				replace(pattern, older, "(?!)", "never")
			}
		}
	}

	// From the 1.21.11 spelling to the 1.21.10 one. Versions older than 1.21.11 get both lists, so
	// keep the patterns of one list from matching the other's output.
	val before1_21_11 = listOf(
		// Resource ids were ResourceLocations, and a ResourceKey's id was its location.
		"\\bIdentifier\\b" to "ResourceLocation",
		"\\.identifier\\(\\)" to ".location()",
		// Cows had no package of their own.
		"net\\.minecraft\\.world\\.entity\\.animal\\.cow\\." to "net.minecraft.world.entity.animal.",
	)
	for ((pattern, older) in before1_21_11) {
		replacements.regex(current.parsed < "1.21.11") {
			replace(pattern, older, "(?!)", "never")
		}
	}
}

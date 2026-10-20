plugins {
	id("dev.kikugie.stonecutter")
	id("net.fabricmc.fabric-loom") version "1.18-SNAPSHOT" apply false
	id("net.fabricmc.fabric-loom-remap") version "1.18-SNAPSHOT" apply false
	id("net.neoforged.moddev") version "2.0.148" apply false
}

// The version the source files are written for. Keep it at the newest version: the renames below
// only translate newest names to older ones, so the source can't be switched to an older version
// and back. Version-specific code that isn't a rename goes in //? if blocks.
stonecutter active "26.3"

stonecutter parameters {
	// The mod loader the jar is for, as //? if fabric and //? if neoforge conditions. Versions named
	// <minecraft>-neoforge are NeoForge (settings.gradle.kts); the rest are Fabric.
	constants.match(if (current.project.endsWith("-neoforge")) "neoforge" else "fabric", "fabric", "neoforge")

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
		// Fabric API's key binding helper.
		"import net\\.fabricmc\\.fabric\\.api\\.client\\.keymapping\\.v1\\.KeyMappingHelper;" to "import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;",
		"\\bKeyMappingHelper\\.registerKeyMapping\\(" to "KeyBindingHelper.registerKeyBinding(",
		// Fabric API's payload type registries.
		"\\bPayloadTypeRegistry\\.clientboundPlay\\(" to "PayloadTypeRegistry.playS2C(",
		"\\bPayloadTypeRegistry\\.serverboundPlay\\(" to "PayloadTypeRegistry.playC2S(",
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
		// Game rules had camel-case names (the tests set them by command).
		"\\bgamerule show_death_messages\\b" to "gamerule showDeathMessages",
		"\\bgamerule immediate_respawn\\b" to "gamerule doImmediateRespawn",
		"\\bgamerule random_tick_speed\\b" to "gamerule randomTickSpeed",
	)
	for ((pattern, older) in before1_21_11) {
		replacements.regex(current.parsed < "1.21.11") {
			replace(pattern, older, "(?!)", "never")
		}
	}

	// Up to 1.21.8 the game asks only for OpenGL 3.2, so the fog shaders must be GLSL 1.50. This
	// can't be a //? block: #version has to be the first thing in the file.
	replacements.regex(current.parsed < "1.21.9") {
		replace("^#version 330$", "#version 150", "(?!)", "never")
	}

	// From the 1.21.6 spelling to the 1.21.5 one. Each call is a whole statement on one line.
	val before1_21_6 = listOf(
		// The GUI pose was a 3D PoseStack instead of a 2D matrix stack.
		"\\bgraphics\\.pose\\(\\)\\.pushMatrix\\(\\);" to "graphics.pose().pushPose();",
		"\\bgraphics\\.pose\\(\\)\\.popMatrix\\(\\);" to "graphics.pose().popPose();",
		"\\bgraphics\\.pose\\(\\)\\.translate\\((.*)\\);" to "graphics.pose().translate($1, 0);",
		"\\bgraphics\\.pose\\(\\)\\.scale\\((.*)\\);" to "graphics.pose().scale($1, $1, 1);",
		// A screen's tooltip was set on the screen, which draws it after everything else.
		"\\bgraphics\\.setTooltipForNextFrame\\(this\\.font, (.*), mouseX, mouseY\\);" to "this.setTooltipForNextRenderPass($1);",
	)
	for ((pattern, older) in before1_21_6) {
		replacements.regex(current.parsed < "1.21.6") {
			replace(pattern, older, "(?!)", "never")
		}
	}

	// HUD elements implement Fabric API's interface, or the mod's own where Fabric API has none
	// (before 1.21.6, where they draw from a callback) or isn't there at all (NeoForge, GUI layers).
	replacements.regex(current.parsed < "1.21.6" || current.project.endsWith("-neoforge")) {
		replace("import net\\.fabricmc\\.fabric\\.api\\.client\\.rendering\\.v1\\.hud\\.HudElement;", "import dev.diorama.compat.HudElement;", "(?!)", "never")
	}

	// From the 1.21.5 spelling to the 1.21.4 one.
	val before1_21_5 = listOf(
		// Baked quads and models had getters.
		"\\bquad\\.sprite\\(\\)" to "quad.getSprite()",
		"\\bquad\\.tintIndex\\(\\)" to "quad.getTintIndex()",
		"\\bquad\\.direction\\(\\)" to "quad.getDirection()",
		"\\bmodel\\.particleIcon\\(\\)" to "model.getParticleIcon()",
		"\\.getMissingBlockStateModel\\(\\)" to ".getMissingModel()",
		// Chat click and hover events were one class each, with an action.
		"\\bnew ClickEvent\\.RunCommand\\(" to "new ClickEvent(ClickEvent.Action.RUN_COMMAND, ",
		"\\bnew HoverEvent\\.ShowText\\(" to "new HoverEvent(HoverEvent.Action.SHOW_TEXT, ",
		// Placing an entity (the tests do) was moving it.
		"\\.snapTo\\(" to ".moveTo(",
	)
	for ((pattern, older) in before1_21_5) {
		replacements.regex(current.parsed < "1.21.5") {
			replace(pattern, older, "(?!)", "never")
		}
	}

	// From the 1.21.2 spelling to the 1.21.1 one.
	val before1_21_2 = listOf(
		// Colour helpers lived in FastColor.
		"import net\\.minecraft\\.util\\.ARGB;" to "import net.minecraft.util.FastColor;",
		"\\bARGB\\.(red|green|blue|color)\\(" to "FastColor.ARGB32.$1(",
		// World heights: the bottom had another name, and the top was exclusive.
		"\\.getMinY\\(\\)" to ".getMinBuildHeight()",
		"\\.getMinSectionY\\(\\)" to ".getMinSection()",
		"([\\w.]+)\\.getMaxY\\(\\)" to "($1.getMaxBuildHeight() - 1)",
		// Running a task on the client thread later was telling the client.
		"\\bmc\\.schedule\\(" to "mc.tell(",
	)
	for ((pattern, older) in before1_21_2) {
		replacements.regex(current.parsed < "1.21.2") {
			replace(pattern, older, "(?!)", "never")
		}
	}
}

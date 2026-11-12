package dev.diorama.web;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /diorama-web status} and {@code /diorama-web redraw}, for operators: what the web map is
 * doing, and drawing every tile again. Not under {@code /diorama}, which the client's own commands
 * take (waypoint sharing): an operator with Diorama typing a server command there would reach the
 * client's.
 */
public final class WebMapCommand {
	private WebMapCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("diorama-web")
			//? if >=1.21.11 {
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			//?} else {
			/*.requires(source -> source.hasPermission(2))
			*///?}
			.then(Commands.literal("status").executes(context -> {
					WebMap map = WebMap.get();
					context.getSource().sendSuccess(() -> Component.literal(map == null
						? "The web map is off (web-map in config/" + WebSettings.FILE + ")"
						: "Web map: " + map.status()), false);
					return 1;
				}))
				.then(Commands.literal("redraw").executes(context -> {
					WebMap map = WebMap.get();
					if (map == null) {
						context.getSource().sendFailure(Component.literal("The web map is off (web-map in config/" + WebSettings.FILE + ")"));
						return 0;
					}
					map.redrawAll();
					context.getSource().sendSuccess(() -> Component.literal("Drawing the whole web map again"), true);
					return 1;
				})));
	}
}

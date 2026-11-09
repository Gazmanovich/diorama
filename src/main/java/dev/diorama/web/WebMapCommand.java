package dev.diorama.web;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * {@code /diorama webmap status} and {@code /diorama webmap redraw}, for operators: what the web
 * map is doing, and drawing every tile again (after changing which land it shows, say).
 */
public final class WebMapCommand {
	private WebMapCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("diorama")
			//? if >=1.21.11 {
			.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
			//?} else {
			/*.requires(source -> source.hasPermission(2))
			*///?}
			.then(Commands.literal("webmap")
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
				}))));
	}
}

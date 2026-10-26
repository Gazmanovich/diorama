package dev.diorama.waypoint;

import dev.diorama.Settings;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/** Making waypoints (where the player stands, at a picked spot, where they die) and going to them. */
public final class Waypoints {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

	private Waypoints() {
	}

	public static @Nullable String dimension() {
		Minecraft mc = Minecraft.getInstance();
		return mc.level == null ? null : mc.level.dimension().identifier().toString();
	}

	/** A new, unsaved waypoint where the player stands. */
	public static @Nullable Waypoint atPlayer() {
		LocalPlayer player = Minecraft.getInstance().player;
		return player == null ? null : at(player.blockPosition());
	}

	/** A new, unsaved waypoint at a block position, named and coloured so the next one differs. */
	public static @Nullable Waypoint at(BlockPos pos) {
		String dimension = dimension();
		WaypointStore store = WaypointStore.get();
		if (dimension == null || store == null) {
			return null;
		}
		int count = store.all().size() + 1;
		// A fresh random colour every time, never close to the last waypoint's.
		List<Waypoint> all = store.all();
		int color = WaypointColours.random(all.isEmpty() ? 0 : all.get(all.size() - 1).color);
		return new Waypoint("Waypoint " + count, pos.getX(), pos.getY(), pos.getZ(), dimension, color);
	}

	/** A dimension's name for its folder: Overworld, Nether, End, or the id's path for a modded one. */
	public static net.minecraft.network.chat.Component dimensionName(String dimension) {
		return switch (dimension) {
			case "minecraft:overworld" -> net.minecraft.network.chat.Component.translatable("diorama.folder.overworld");
			case "minecraft:the_nether" -> net.minecraft.network.chat.Component.translatable("diorama.folder.nether");
			case "minecraft:the_end" -> net.minecraft.network.chat.Component.translatable("diorama.folder.end");
			default -> {
				String path = dimension.substring(dimension.indexOf(':') + 1).replace('_', ' ').replace('/', ' ');
				yield net.minecraft.network.chat.Component.literal(path.isEmpty() ? dimension : Character.toUpperCase(path.charAt(0)) + path.substring(1));
			}
		};
	}

	/** Adds a temporary waypoint at a block position: no editor, and it's never saved. */
	public static void addTemporary(BlockPos pos) {
		String dimension = dimension();
		WaypointStore store = WaypointStore.get();
		if (dimension == null || store == null) {
			return;
		}
		String name = net.minecraft.network.chat.Component.translatable("diorama.waypoint.temporary").getString();
		Waypoint temporary = new Waypoint(name, pos.getX(), pos.getY(), pos.getZ(), dimension, 0xFFFFFFFF);
		temporary.kind = Waypoint.Kind.TEMPORARY;
		store.add(temporary);
	}

	/**
	 * The server only sends the commands a player may use, so teleporting is offered when it sent
	 * the teleport command's first word.
	 */
	public static boolean canTeleport() {
		var connection = Minecraft.getInstance().getConnection();
		String command = Settings.get().teleportCommand;
		String name = command.split(" ", 2)[0];
		return connection != null && connection.getCommands().getRoot().getChild(name) != null;
	}

	/** Sends the player's teleport command, {@code /tp} unless they changed it, to a block position. */
	public static void teleport(int x, int y, int z) {
		LocalPlayer player = Minecraft.getInstance().player;
		String command = Settings.get().teleportCommand
			.replace("{x}", Integer.toString(x))
			.replace("{y}", Integer.toString(y))
			.replace("{z}", Integer.toString(z));
		// The command packet holds 256 characters; a longer one throws instead of sending.
		if (player != null && command.length() <= 256) {
			player.connection.sendCommand(command);
		}
	}

	/** Adds a death marker where the player stands. Called when the server reports their death. */
	public static void died(LocalPlayer player) {
		if (!Settings.get().deathMarkers) {
			return;
		}
		WaypointStore store = WaypointStore.get();
		String dimension = dimension();
		if (store == null || dimension == null) {
			return;
		}
		BlockPos pos = player.blockPosition();
		Waypoint death = new Waypoint("Death " + LocalTime.now().format(TIME), pos.getX(), pos.getY(), pos.getZ(), dimension, 0xFF60646C);
		death.kind = Waypoint.Kind.DEATH;
		store.add(death);
	}
}

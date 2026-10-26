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

	/** The server only sends the commands a player may use, so {@code tp} being there means it's allowed. */
	public static boolean canTeleport() {
		var connection = Minecraft.getInstance().getConnection();
		return connection != null && connection.getCommands().getRoot().getChild("tp") != null;
	}

	/** Sends {@code /tp} to a block position. */
	public static void teleport(int x, int y, int z) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			player.connection.sendCommand("tp @s " + x + " " + y + " " + z);
		}
	}

	private static boolean wasSleeping;

	/**
	 * Keeps a bed waypoint where the player last lay down to sleep, one per dimension, moved when
	 * they sleep somewhere else. Lying down is what the client sees of the server setting their
	 * respawn point. Called every tick.
	 */
	public static void watchSleep(Minecraft mc) {
		LocalPlayer player = mc.player;
		boolean sleeping = player != null && player.isSleeping();
		if (sleeping && !wasSleeping) {
			player.getSleepingPos().ifPresent(Waypoints::slept);
		}
		wasSleeping = sleeping;
	}

	private static void slept(BlockPos pos) {
		WaypointStore store = WaypointStore.get();
		String dimension = dimension();
		if (store == null || dimension == null) {
			return;
		}
		for (Waypoint waypoint : store.in(dimension)) {
			if (waypoint.kind == Waypoint.Kind.BED) {
				waypoint.x = pos.getX();
				waypoint.y = pos.getY();
				waypoint.z = pos.getZ();
				store.changed();
				return;
			}
		}
		Waypoint bed = new Waypoint("Bed", pos.getX(), pos.getY(), pos.getZ(), dimension, 0xFFE5484D);
		bed.kind = Waypoint.Kind.BED;
		bed.icon = "diorama:bed";
		store.add(bed);
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

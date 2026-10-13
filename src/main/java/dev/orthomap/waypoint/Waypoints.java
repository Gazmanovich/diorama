package dev.orthomap.waypoint;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/** Making waypoints: where the player stands, at a picked spot, and where they die. */
public final class Waypoints {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");
	private static boolean wasDead;

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
		int color = Waypoint.PALETTE[ThreadLocalRandom.current().nextInt(9)];
		return new Waypoint("Waypoint " + count, pos.getX(), pos.getY(), pos.getZ(), dimension, color);
	}

	/** Adds a death marker the moment the player dies. Call every client tick. */
	public static void tick() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null) {
			wasDead = false;
			return;
		}
		boolean dead = player.isDeadOrDying();
		if (dead && !wasDead) {
			WaypointStore store = WaypointStore.get();
			String dimension = dimension();
			if (store != null && dimension != null) {
				BlockPos pos = player.blockPosition();
				Waypoint death = new Waypoint("Death " + LocalTime.now().format(TIME), pos.getX(), pos.getY(), pos.getZ(), dimension, 0xFF60646C);
				death.kind = Waypoint.Kind.DEATH;
				store.add(death);
			}
		}
		wasDead = dead;
	}
}

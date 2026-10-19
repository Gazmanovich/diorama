package dev.diorama;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.UUIDUtil;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.GameType;
import org.jspecify.annotations.Nullable;

/**
 * Players the server reports wherever they are, through Xaero's player tracker: Xaero's mods on
 * the server (for party members, or everyone when the server allows it) and plugins such as
 * XaeroTracker send their positions every quarter second or so as they move. The radar shows the
 * ones the client hasn't loaded, so friends show on the map from anywhere. Client thread only.
 */
public final class TrackedPlayers {
	private record Tracked(double x, double y, double z, String dimension) {
	}

	private static final Map<UUID, Tracked> PLAYERS = new HashMap<>();
	/**
	 * What the radar draws for each tracked player: a player the level doesn't hold, built from
	 * their tab list entry, so they look like any other player, skin and name included.
	 */
	private static final Map<UUID, RemotePlayer> STAND_INS = new HashMap<>();
	private static @Nullable ClientLevel standInLevel;

	private TrackedPlayers() {
	}

	/**
	 * One player's update: {@code r} true if they're no longer tracked, else their position
	 * {@code x}, {@code y}, {@code z} and dimension {@code d}. {@code i} is their UUID as four ints,
	 * or two longs from older servers.
	 */
	static void update(CompoundTag tag) {
		UUID id = uuid(tag);
		if (id == null) {
			return;
		}
		//? if >=1.21.5 {
		boolean removed = tag.getBooleanOr("r", false);
		//?} else {
		/*// Before 1.21.5 a missing value read as false, 0 or "".
		boolean removed = tag.getBoolean("r");
		*///?}
		if (removed) {
			PLAYERS.remove(id);
			STAND_INS.remove(id);
		} else {
			//? if >=1.21.5 {
			PLAYERS.put(id, new Tracked(tag.getDoubleOr("x", 0), tag.getDoubleOr("y", 0), tag.getDoubleOr("z", 0), tag.getStringOr("d", "")));
			//?} else {
			/*PLAYERS.put(id, new Tracked(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"), tag.getString("d")));
			*///?}
		}
	}

	private static @Nullable UUID uuid(CompoundTag tag) {
		//? if >=1.21.5 {
		int[] ints = tag.getIntArray("i").orElse(new int[0]);
		long[] longs = tag.getLongArray("i").orElse(new long[0]);
		//?} else {
		/*// Before 1.21.5 a missing or other-typed array read as empty.
		int[] ints = tag.getIntArray("i");
		long[] longs = tag.getLongArray("i");
		*///?}
		if (ints.length == 4) {
			return UUIDUtil.uuidFromIntArray(ints);
		}
		if (longs.length == 2) {
			return new UUID(longs[0], longs[1]);
		}
		return null;
	}

	/** The server is about to send everyone again, or the player left it. */
	public static void clear() {
		PLAYERS.clear();
		STAND_INS.clear();
		standInLevel = null;
	}

	/**
	 * The tracked players to draw in this level: in its dimension, not loaded by the client
	 * (those are drawn as they are), and on the tab list, which has their name and skin.
	 * Spectators are left off unless the player is one too, as the game hides them.
	 */
	public static List<RemotePlayer> unloaded(ClientLevel level) {
		Minecraft mc = Minecraft.getInstance();
		ClientPacketListener connection = mc.getConnection();
		if (PLAYERS.isEmpty() || connection == null || mc.player == null) {
			return List.of();
		}
		if (standInLevel != level) {
			STAND_INS.clear();
			standInLevel = level;
		}
		String dimension = level.dimension().identifier().toString();
		List<RemotePlayer> out = new ArrayList<>();
		for (Map.Entry<UUID, Tracked> e : PLAYERS.entrySet()) {
			UUID id = e.getKey();
			Tracked tracked = e.getValue();
			if (id.equals(mc.player.getUUID()) || !tracked.dimension().equals(dimension) || level.getPlayerByUUID(id) != null) {
				continue;
			}
			PlayerInfo info = connection.getPlayerInfo(id);
			if (info == null || info.getGameMode() == GameType.SPECTATOR && !mc.player.isSpectator()) {
				continue;
			}
			RemotePlayer standIn = STAND_INS.computeIfAbsent(id, uuid -> new RemotePlayer(level, info.getProfile()));
			//? if >=1.21.5 {
			standIn.snapTo(tracked.x(), tracked.y(), tracked.z());
			//?} else {
			/*// Sets the previous position too, as snapTo does, so the face doesn't slide from the old spot.
			standIn.moveTo(tracked.x(), tracked.y(), tracked.z());
			*///?}
			out.add(standIn);
		}
		return out;
	}
}

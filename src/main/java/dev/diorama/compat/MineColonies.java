package dev.diorama.compat;

import dev.diorama.Claims;
import dev.diorama.Diorama;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?} else {
/*import net.neoforged.fml.ModList;
*///?}
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Reads the land MineColonies colonies claim, for {@link Claims}: each claimed chunk goes to the
 * colony that owns it, in the colony's team colour and under its name, as MineColonies' own
 * JourneyMap borders show it. A colony's land shows once the server has sent the colony itself
 * (on walking into it, and for the player's own colonies on joining), and only while the colony
 * lets the player see its border on maps, its "map border" permission.
 *
 * <p>MineColonies has no call that says a chunk changed hands, so this looks once a second, in the
 * player's dimension, and hands over what changed. Where the land comes from depends on the version:
 * <ul>
 * <li>1.21.1: the colony manager's list of every claimed chunk, which the server sends with each colony.
 * <li>1.20.1: the owner each loaded chunk carries. A chunk keeps the owner last seen after it
 * unloads, until its colony is gone.
 * </ul>
 *
 * <p>Diorama doesn't build against MineColonies. The names below are its public API. If one isn't
 * where this class expects it, it logs once and stays off.
 */
public final class MineColonies {
	private static final String API = "com.minecolonies.api.";
	/** Ticks between looks. */
	private static final int LOOK_TICKS = 20;
	/** The colony id MineColonies gives a chunk no colony owns. */
	private static final int NO_COLONY = 0;

	private static boolean started;
	private static boolean failed;
	private static Method managerInstance;
	private static Method colonyView;
	/** 1.21.1: every claimed chunk in a dimension, and a claim's colony. Null on 1.20.1. */
	private static @Nullable Method claimData;
	private static @Nullable Method claimColony;
	/** 1.20.1: a loaded chunk's colony. */
	private static @Nullable Method chunkColony;
	private static Method colonyName;
	private static Method colonyColour;
	private static Method colonyPermissions;
	private static Method hasPermission;
	private static Object mapBorder;

	/** Looks in a row that failed inside MineColonies or on what it gave: this many, and the class stays off. */
	private static final int FAILED_LOOKS = 30;

	private static int ticks;
	private static int failedLooks;
	/** The colony each chunk was last seen to belong to and shown for, by dimension, then chunk. */
	private static final Map<ResourceKey<Level>, Map<Long, Integer>> handed = new HashMap<>();

	private MineColonies() {
	}

	public static boolean installed() {
		//? if fabric {
		return FabricLoader.getInstance().isModLoaded("minecolonies");
		//?} else {
		/*return ModList.get().isLoaded("minecolonies");
		*///?}
	}

	/** Whether colonies' land can be read: MineColonies is installed and everything was found. */
	public static boolean available() {
		if (!started) {
			started = true;
			if (installed()) {
				try {
					lookUp();
					Diorama.LOGGER.info("MineColonies is installed: showing its colonies' land on the map");
				} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
					fail(e);
				}
			} else {
				failed = true;
			}
		}
		return !failed;
	}

	private static void lookUp() throws ReflectiveOperationException {
		Class<?> manager = Class.forName(API + "colony.IColonyManager");
		managerInstance = manager.getMethod("getInstance");
		colonyView = manager.getMethod("getColonyView", int.class, ResourceKey.class);
		try {
			claimData = manager.getMethod("getClaimData", ResourceKey.class);
			claimColony = Class.forName(API + "colony.claim.IChunkClaimData").getMethod("getOwningColony");
		} catch (NoSuchMethodException | ClassNotFoundException e) {
			claimData = null;
			claimColony = null;
			chunkColony = Class.forName(API + "util.ColonyUtils").getMethod("getOwningColony", LevelChunk.class);
		}
		Class<?> colony = Class.forName(API + "colony.IColony");
		colonyName = colony.getMethod("getName");
		colonyColour = colony.getMethod("getTeamColonyColor");
		colonyPermissions = colony.getMethod("getPermissions");
		Class<?> action = Class.forName(API + "colony.permissions.Action");
		hasPermission = Class.forName(API + "colony.permissions.IPermissions").getMethod("hasPermission", Player.class, action);
		mapBorder = action.getField("MAP_BORDER").get(null);
	}

	/** Diorama forgot every claim: they're all handed over again on the next tick. */
	public static void cleared() {
		handed.clear();
		ticks = 0;
	}

	/** Called every client tick, before {@link Claims} makes its snapshot: once a second, hands over the chunks that changed owner. */
	public static void tick(Minecraft mc) {
		if (mc.level == null || mc.player == null || ticks++ % LOOK_TICKS != 0 || !available()) {
			return;
		}
		try {
			look(mc);
			failedLooks = 0;
		} catch (InvocationTargetException | RuntimeException e) {
			// MineColonies threw, or its list did while this went through it: in singleplayer the
			// list is the server's own, and the server can change it under this read. The next
			// look reads it again; only a look that keeps failing is a MineColonies this can't read.
			Diorama.LOGGER.debug("MineColonies couldn't give its colonies' land", e);
			if (++failedLooks >= FAILED_LOOKS) {
				fail(e);
			}
		} catch (ReflectiveOperationException | LinkageError e) {
			fail(e);
		}
	}

	private static void look(Minecraft mc) throws ReflectiveOperationException {
		ResourceKey<Level> dimension = mc.level.dimension();
		Object manager = managerInstance.invoke(null);
		Map<Long, Integer> known = handed.computeIfAbsent(dimension, d -> new HashMap<>());
		// Whether each colony's land shows, asked once per look.
		Map<Integer, Boolean> shown = new HashMap<>();
		Map<Long, Integer> seen = new HashMap<>();
		if (claimData != null) {
			for (Map.Entry<?, ?> claim : ((Map<?, ?>) claimData.invoke(manager, dimension)).entrySet()) {
				if (claim.getValue() != null) {
					ChunkPos pos = (ChunkPos) claim.getKey();
					seen.put(key(pos.getMinBlockX() >> 4, pos.getMinBlockZ() >> 4), (int) claimColony.invoke(claim.getValue()));
				}
			}
		} else {
			int radius = mc.options.renderDistance().get() + 2;
			int centreX = mc.player.blockPosition().getX() >> 4;
			int centreZ = mc.player.blockPosition().getZ() >> 4;
			for (int x = centreX - radius; x <= centreX + radius; x++) {
				for (int z = centreZ - radius; z <= centreZ + radius; z++) {
					LevelChunk chunk = mc.level.getChunkSource().getChunkNow(x, z);
					if (chunk != null && !chunk.isEmpty()) {
						seen.put(key(x, z), (int) chunkColony.invoke(null, chunk));
					}
				}
			}
		}
		for (Map.Entry<Long, Integer> chunk : seen.entrySet()) {
			int colony = chunk.getValue();
			if (colony != NO_COLONY && !shown.computeIfAbsent(colony, id -> shows(manager, id, dimension, mc.player))) {
				colony = NO_COLONY;
			}
			hand(dimension, known, chunk.getKey(), colony);
		}
		// Chunks handed over before and not seen now: unclaimed on 1.21.1, where every claim is
		// seen. On 1.20.1 they're out of sight and stay their colony's while it shows, which
		// takes them back if another claims mod held them and let go.
		for (Iterator<Map.Entry<Long, Integer>> it = known.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Long, Integer> chunk = it.next();
			if (seen.containsKey(chunk.getKey())) {
				continue;
			}
			long at = chunk.getKey();
			if (claimData != null || !shown.computeIfAbsent(chunk.getValue(), id -> shows(manager, id, dimension, mc.player))) {
				Claims.updated(dimension, (int) (at >> 32), (int) at, Colony.class, null, false);
				it.remove();
			} else {
				claim(dimension, (int) (at >> 32), (int) at, chunk.getValue());
			}
		}
	}

	/**
	 * Hands a chunk to its colony, or to no one. Land another claims mod holds stays that mod's: a
	 * colony takes a chunk only while no one else has it, and is asked again at every look, so it
	 * gets the chunk back once the other mod lets go.
	 */
	private static void hand(ResourceKey<Level> dimension, Map<Long, Integer> known, long at, int colony) {
		int x = (int) (at >> 32);
		int z = (int) at;
		if (colony == NO_COLONY) {
			if (known.remove(at) != null) {
				Claims.updated(dimension, x, z, Colony.class, null, false);
			}
			return;
		}
		claim(dimension, x, z, colony);
		known.put(at, colony);
	}

	private static void claim(ResourceKey<Level> dimension, int x, int z, int colony) {
		Claims.Owner now = Claims.owner(dimension, x, z);
		if (now == null || now instanceof Colony other && other.id() != colony) {
			Claims.updated(dimension, x, z, Colony.class, new Colony(dimension, colony), false);
		}
	}

	private static long key(int chunkX, int chunkZ) {
		return (long) chunkX << 32 | chunkZ & 0xFFFFFFFFL;
	}

	/** Whether a colony's land shows: the server sent the colony, and it lets this player see its border on maps. */
	private static boolean shows(Object manager, int id, ResourceKey<Level> dimension, Player player) {
		try {
			Object view = colonyView.invoke(manager, id, dimension);
			return view != null && (boolean) hasPermission.invoke(colonyPermissions.invoke(view), player, mapBorder);
		} catch (ReflectiveOperationException | RuntimeException e) {
			Diorama.LOGGER.debug("MineColonies couldn't give a colony", e);
			return false;
		}
	}

	/** A colony, as the owner of the chunks it claims. */
	public record Colony(ResourceKey<Level> dimension, int id) implements Claims.Owner {
		@Override
		public @Nullable Optional<Claims.Claim> look() {
			Minecraft mc = Minecraft.getInstance();
			if (!available() || mc.player == null) {
				return null;
			}
			try {
				Object view = colonyView.invoke(managerInstance.invoke(null), this.id, this.dimension);
				if (view == null || !(boolean) hasPermission.invoke(colonyPermissions.invoke(view), mc.player, mapBorder)) {
					return Optional.empty();
				}
				// A team colour is one of the game's sixteen chat colours.
				TextColor colour = colonyColour.invoke(view) instanceof ChatFormatting team ? TextColor.fromLegacyFormat(team) : null;
				String name = colonyName.invoke(view) instanceof String s ? s : "";
				return Optional.of(new Claims.Claim(this, colour == null ? 0xFFFFFF : colour.getValue() & 0xFFFFFF, name, false, true));
			} catch (InvocationTargetException e) {
				Diorama.LOGGER.debug("MineColonies couldn't give a colony", e);
				return null;
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				fail(e);
				return null;
			}
		}
	}

	private static void fail(Throwable e) {
		if (!failed) {
			failed = true;
			Diorama.LOGGER.warn("Couldn't read MineColonies' colonies; their land won't show on the map", e);
		}
	}
}

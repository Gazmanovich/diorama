package dev.diorama.compat;

import dev.diorama.Claims;
import dev.diorama.Diorama;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;
//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?} else {
/*import net.neoforged.fml.ModList;
*///?}
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Reads the land claims FTB Chunks tells the client about. The server sends every claim the player
 * may see when they join, and each change after, whether or not FTB Chunks' minimap is on. FTB
 * Chunks applies each to its own client map in a task, on the client tick;
 * {@code FtbChunksUpdateMixin} calls {@link #updated} after each, and this hands the task's chunk,
 * team and force loading to {@link Claims}, which looks the team up as FTB Chunks does. All on the
 * client thread, where FTB Chunks keeps its map, so nothing here races it.
 *
 * <p>Diorama doesn't build against FTB Chunks. The names below have stayed the same from 1.20.1 to
 * 26.1. If one isn't where this class expects it, it logs once and stays off.
 */
public final class FtbChunks {
	private static final String MAP = "dev.ftb.mods.ftbchunks.client.map.";

	private static boolean started;
	private static boolean failed;
	private static Field taskDimension;
	/** The update's chunk: {@code chunk} before 1.21, {@code info} after, each with x, z and forceLoaded fields. */
	private static Field taskChunk;
	private static Field taskTeam;
	private static Field chunkX;
	private static Field chunkZ;
	private static Field chunkForceLoaded;
	private static Field dimensionKey;
	private static Field dimensionManager;
	private static Method managerInvalid;
	private static Method teamId;
	private static Method getProperty;
	private static Object colorProperty;
	private static Object nameProperty;
	private static Method rgb;
	private static Method teamsApi;
	private static Method clientManagerLoaded;
	private static Method clientManager;
	private static Method teamById;

	private FtbChunks() {
	}

	public static boolean installed() {
		//? if fabric {
		return FabricLoader.getInstance().isModLoaded("ftbchunks");
		//?} else {
		/*return ModList.get().isLoaded("ftbchunks");
		*///?}
	}

	/** Whether claims can be read: FTB Chunks is installed and everything was found. */
	public static boolean available() {
		if (!started) {
			started = true;
			if (installed()) {
				try {
					lookUp();
					Diorama.LOGGER.info("FTB Chunks is installed: showing its claims on the map");
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
		Class<?> task = Class.forName(MAP + "UpdateChunkFromServerTask");
		taskDimension = task.getDeclaredField("dimension");
		taskDimension.setAccessible(true);
		Class<?> dimension = Class.forName(MAP + "MapDimension");
		dimensionKey = dimension.getField("dimension");
		dimensionManager = dimension.getDeclaredField("manager");
		dimensionManager.setAccessible(true);
		managerInvalid = Class.forName(MAP + "MapManager").getMethod("isInvalid");
		taskTeam = task.getDeclaredField("teamId");
		taskTeam.setAccessible(true);
		try {
			taskChunk = task.getDeclaredField("info");
		} catch (NoSuchFieldException e) {
			// 1.20.1: the packet's own chunk record.
			taskChunk = task.getDeclaredField("chunk");
		}
		taskChunk.setAccessible(true);
		chunkX = field(taskChunk.getType(), "x");
		chunkZ = field(taskChunk.getType(), "z");
		chunkForceLoaded = field(taskChunk.getType(), "forceLoaded");
		Class<?> team = Class.forName("dev.ftb.mods.ftbteams.api.Team");
		teamId = team.getMethod("getId");
		Class<?> property = Class.forName("dev.ftb.mods.ftbteams.api.property.TeamProperty");
		getProperty = team.getMethod("getProperty", property);
		Class<?> properties = Class.forName("dev.ftb.mods.ftbteams.api.property.TeamProperties");
		colorProperty = properties.getField("COLOR").get(null);
		nameProperty = properties.getField("DISPLAY_NAME").get(null);
		rgb = Class.forName("dev.ftb.mods.ftblibrary.icon.Color4I").getMethod("rgb");
		teamsApi = Class.forName("dev.ftb.mods.ftbteams.api.FTBTeamsAPI").getMethod("api");
		Class<?> api = teamsApi.getReturnType();
		clientManagerLoaded = api.getMethod("isClientManagerLoaded");
		clientManager = api.getMethod("getClientManager");
		teamById = clientManager.getReturnType().getMethod("getTeamByID", UUID.class);
	}

	private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	/**
	 * After FTB Chunks applied a claim update from the server to one chunk of its map: tells
	 * {@link Claims} the chunk's team and whether it's force loaded. Client thread, from
	 * {@code FtbChunksUpdateMixin}.
	 */
	public static void updated(Object task) {
		if (!available() || !net.minecraft.client.Minecraft.getInstance().isSameThread()) {
			return;
		}
		long start = System.nanoTime();
		try {
			Object dimension = taskDimension.get(task);
			// A map being shut down (leaving a server, or a proxy moving the player to another)
			// still runs its queued updates: they're for the old server.
			if ((boolean) managerInvalid.invoke(dimensionManager.get(dimension))) {
				return;
			}
			Object chunk = taskChunk.get(task);
			@SuppressWarnings("unchecked")
			ResourceKey<Level> key = (ResourceKey<Level>) dimensionKey.get(dimension);
			Claims.updated(key, chunkX.getInt(chunk), chunkZ.getInt(chunk), (UUID) taskTeam.get(task), chunkForceLoaded.getBoolean(chunk));
		} catch (InvocationTargetException e) {
			// FTB Chunks itself threw: skip this update, as it would have.
			Diorama.LOGGER.debug("FTB Chunks couldn't give a chunk's claim", e);
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			fail(e);
		} finally {
			Claims.spent(System.nanoTime() - start);
		}
	}

	/**
	 * A team's claim as it stands now, from FTB Teams on the client: empty if FTB Teams knows the
	 * team no longer exists (a deleted server team, whose chunks get no update), null if it can't
	 * tell now. Client thread.
	 */
	public static @Nullable Optional<Claims.Claim> team(UUID id) {
		if (!available()) {
			return null;
		}
		try {
			Object api = teamsApi.invoke(null);
			if (!(boolean) clientManagerLoaded.invoke(api)) {
				return null;
			}
			Optional<?> team = (Optional<?>) teamById.invoke(clientManager.invoke(api), id);
			return team.isEmpty() ? Optional.empty() : Optional.of(owner(team.get()));
		} catch (InvocationTargetException e) {
			Diorama.LOGGER.debug("FTB Teams couldn't give a team", e);
			return null;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			fail(e);
			return null;
		}
	}

	private static Claims.Claim owner(Object team) throws ReflectiveOperationException {
		UUID id = (UUID) teamId.invoke(team);
		Object colour = getProperty.invoke(team, colorProperty);
		Object name = getProperty.invoke(team, nameProperty);
		return new Claims.Claim(id, colour == null ? 0xFFFFFF : (int) rgb.invoke(colour) & 0xFFFFFF, name instanceof String s ? s : "", false);
	}

	private static void fail(Throwable e) {
		if (!failed) {
			failed = true;
			Diorama.LOGGER.warn("Couldn't read FTB Chunks' claims; they won't show on the map", e);
		}
	}
}

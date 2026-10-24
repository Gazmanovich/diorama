package dev.diorama.data;

import dev.diorama.Diorama;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?} else {
/*import net.neoforged.fml.ModList;
*///?}
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * What copycat blocks are filled with. Create's copycat step and panel, and the copycats of its
 * Copycats+ addon, keep the block they copy (their material) in their block entity, not in their
 * block state, so the map's colour table only knows what they look like empty. Snapshots read the
 * material here so the map can draw a filled copycat in its material's colours.
 *
 * <p>Diorama doesn't build against either mod. When Create is installed (Copycats+ needs it), this
 * looks up their getters by name, once per block entity class. If anything isn't where this class
 * expects it, it logs once and stays off: copycats then draw as they do empty.
 */
public final class Copycats {
	/** Create's copycat block entity, with {@code getMaterial()}. */
	private static final String CREATE = "com.simibubi.create.content.decoration.copycat.CopycatBlockEntity";
	/** Every Copycats+ copycat's block entity, with {@code getMaterial()}. */
	private static final String PLUS = "com.copycatsplus.copycats.foundation.copycat.ICopycatBlockEntity";
	/** Copycats+ copycats made of parts that each take their own material, like a slab with two halves. */
	private static final String PLUS_MULTI = "com.copycatsplus.copycats.foundation.copycat.multistate.IMultiStateCopycatBlockEntity";
	private static final String PLUS_MULTI_BLOCK = "com.copycatsplus.copycats.foundation.copycat.multistate.IMultiStateCopycatBlock";
	/** What an empty copycat (or an empty part) holds as its material. */
	private static final String EMPTY = "create:copycat_base";

	/** Whether a mod with copycats is installed. When it isn't, nothing here runs. */
	public static final boolean INSTALLED = installed();

	private static final ClassValue<@Nullable Reader> READERS = new ClassValue<>() {
		@Override
		protected @Nullable Reader computeValue(Class<?> type) {
			return reader(type);
		}
	};
	/** For a Copycats+ block made of parts: its {@code partExists} and {@code getVectorFromProperty}, or null. */
	private static final ClassValue<Method @Nullable []> PART_GETTERS = new ClassValue<>() {
		@Override
		protected Method @Nullable [] computeValue(Class<?> type) {
			Class<?> block = supertype(type, PLUS_MULTI_BLOCK);
			try {
				return block == null ? null : new Method[] {
					block.getMethod("partExists", BlockState.class, String.class),
					block.getMethod("getVectorFromProperty", BlockState.class, String.class)
				};
			} catch (ReflectiveOperationException e) {
				return null;
			}
		}
	};
	private static volatile boolean failed;

	private Copycats() {
	}

	private static boolean installed() {
		//? if fabric {
		return FabricLoader.getInstance().isModLoaded("create");
		//?} else {
		/*return ModList.get().isLoaded("create");
		*///?}
	}

	/** What the copycat block entity is filled with, or null if it isn't a copycat or is empty. Client thread. */
	public static @Nullable BlockState material(@Nullable BlockEntity entity) {
		if (!INSTALLED || failed || entity == null) {
			return null;
		}
		Reader reader = READERS.get(entity.getClass());
		if (reader == null) {
			return null;
		}
		try {
			BlockState material = reader.material(entity);
			return material == null || isEmpty(material) ? null : material;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			failed = true;
			Diorama.LOGGER.warn("Couldn't read what a copycat ({}) is filled with; filled copycats draw as they do empty", entity.getClass().getName(), e);
			return null;
		}
	}

	private static boolean isEmpty(BlockState material) {
		return material.isAir() || BuiltInRegistries.BLOCK.getKey(material.getBlock()).toString().equals(EMPTY);
	}

	private interface Reader {
		@Nullable BlockState material(BlockEntity entity) throws ReflectiveOperationException;
	}

	/** How to read a block entity class's material, or null if it isn't a copycat. */
	private static @Nullable Reader reader(Class<?> type) {
		try {
			Class<?> multi = supertype(type, PLUS_MULTI);
			if (multi != null) {
				Method storage = multi.getMethod("getMaterialItemStorage");
				Method parts = storage.getReturnType().getMethod("getMaterialMap");
				return entity -> topPart(entity, (Map<?, ?>) parts.invoke(storage.invoke(entity)));
			}
			Class<?> single = supertype(type, PLUS);
			if (single == null) {
				single = supertype(type, CREATE);
			}
			if (single != null) {
				// Looked up on the mod's own public type: the block entity's class may not be public.
				Method getter = single.getMethod("getMaterial");
				return entity -> getter.invoke(entity) instanceof BlockState state ? state : null;
			}
			return null;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			failed = true;
			Diorama.LOGGER.warn("Couldn't find how {} keeps what it's filled with; filled copycats draw as they do empty", type.getName(), e);
			return null;
		}
	}

	/**
	 * The material of the part the map sees from above: the highest part that is filled, and among
	 * parts at the same height, the material most of them have (ties go to the first part by name,
	 * so every snapshot picks the same one). A double slab in oak over stone shows its oak top on
	 * the map, as it does looking down in game. Parts the block doesn't have in its current state
	 * still keep an entry, so they're skipped.
	 */
	private static @Nullable BlockState topPart(BlockEntity entity, Map<?, ?> parts) throws ReflectiveOperationException {
		BlockState state = entity.getBlockState();
		Method[] getters = PART_GETTERS.get(state.getBlock().getClass());
		if (getters == null) {
			// Without them there's no telling which parts are there, or which is on top.
			return null;
		}
		Method exists = getters[0];
		Method vector = getters[1];
		int topY = Integer.MIN_VALUE;
		// In part name order, so ties go to the first part by name.
		Map<BlockState, Integer> atTop = new LinkedHashMap<>();
		for (Map.Entry<?, ?> part : new TreeMap<>(parts).entrySet()) {
			if (!(part.getKey() instanceof String name) || !(part.getValue() instanceof BlockState material) || isEmpty(material)) {
				continue;
			}
			if (!Boolean.TRUE.equals(exists.invoke(state.getBlock(), state, name))) {
				continue;
			}
			int y = vector.invoke(state.getBlock(), state, name) instanceof Vec3i v ? v.getY() : 0;
			if (y > topY) {
				topY = y;
				atTop.clear();
			}
			if (y == topY) {
				atTop.merge(material, 1, Integer::sum);
			}
		}
		BlockState best = null;
		for (Map.Entry<BlockState, Integer> entry : atTop.entrySet()) {
			if (best == null || entry.getValue() > atTop.get(best)) {
				best = entry.getKey();
			}
		}
		return best;
	}

	/** The class or interface of this name that {@code type} is or extends, or null. */
	private static @Nullable Class<?> supertype(@Nullable Class<?> type, String name) {
		if (type == null) {
			return null;
		}
		if (type.getName().equals(name)) {
			return type;
		}
		Class<?> found = supertype(type.getSuperclass(), name);
		for (Class<?> parent : type.getInterfaces()) {
			if (found == null) {
				found = supertype(parent, name);
			}
		}
		return found;
	}
}

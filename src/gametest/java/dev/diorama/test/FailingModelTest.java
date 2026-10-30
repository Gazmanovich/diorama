package dev.diorama.test;

import dev.diorama.bake.BlockLooks;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A block model that throws when asked for its faces, as Immersive Engineering's mirrored
 * multiblock parts do without their block entity's data: the map's block colours still build,
 * where the throw used to crash the game as a world loaded (Forge 1.20.1, Diorama 0.6.0). Gold
 * block's model is swapped for one that throws on every call given a random source (the faces on
 * every version) and answers the rest as gold block, so gold takes its particle texture's colour,
 * which for gold is its face's.
 */
final class FailingModelTest {
	private FailingModelTest() {
	}

	static void run(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			BlockState gold = Blocks.GOLD_BLOCK.defaultBlockState();
			int id = Block.BLOCK_STATE_REGISTRY.getId(gold);
			BlockLooks real = BlockLooks.build();
			Object models = models(mc);
			Field field = modelMap(models);
			BlockLooks looks;
			try {
				@SuppressWarnings("unchecked")
				Map<BlockState, Object> byState = (Map<BlockState, Object>) field.get(models);
				Object goldModel = byState.get(gold);
				Map<BlockState, Object> swapped = new HashMap<>(byState);
				swapped.put(gold, Proxy.newProxyInstance(modelType(field).getClassLoader(), new Class<?>[] {modelType(field)}, (proxy, method, args) -> {
					for (Class<?> parameter : method.getParameterTypes()) {
						if (parameter == RandomSource.class) {
							throw new NullPointerException("A stand-in for a mod's model that needs its block entity's data");
						}
					}
					try {
						return method.invoke(goldModel, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				}));
				field.set(models, swapped);
				try {
					looks = BlockLooks.build();
				} finally {
					field.set(models, byState);
				}
			} catch (IllegalAccessException e) {
				throw new AssertionError(e);
			} finally {
				BlockLooks.build();
			}
			if (looks.kind[id] != real.kind[id] || looks.top[id] != real.top[id] || looks.side[id] != real.side[id]) {
				throw new AssertionError(String.format("Gold with a failing model looks %d #%06x/#%06x, not %d #%06x/#%06x",
					looks.kind[id], looks.top[id], looks.side[id], real.kind[id], real.top[id], real.side[id]));
			}
		});
	}

	/** What the game's block models are kept in, which BlockLooks reads them from. */
	private static Object models(Minecraft mc) {
		//? if >=26.1 {
		return mc.getModelManager().getBlockStateModelSet();
		//?} else {
		/*return mc.getModelManager().getBlockModelShaper();
		*///?}
	}

	/**
	 * Its map of models by block state. Found by type, not name, so it works where the game's names
	 * are obfuscated (Forge 1.20.1 in production).
	 */
	private static Field modelMap(Object models) {
		for (Field field : models.getClass().getDeclaredFields()) {
			if (field.getGenericType() instanceof ParameterizedType type && type.getRawType() == Map.class
				&& type.getActualTypeArguments()[0] == BlockState.class) {
				field.setAccessible(true);
				return field;
			}
		}
		throw new AssertionError(models.getClass() + " has no Map<BlockState, model> field");
	}

	private static Class<?> modelType(Field field) {
		Type type = ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[1];
		return (Class<?>) type;
	}
}

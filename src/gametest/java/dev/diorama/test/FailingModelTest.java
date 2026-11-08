package dev.diorama.test;

import dev.diorama.bake.BlockLooks;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block models that throw when asked for their faces, which used to crash the game as a world
 * loaded: the map's block colours still build. Immersive Engineering's mirrored multiblock parts
 * throw without their block entity's data (Forge 1.20.1, Diorama 0.6.0), and Immortalers
 * Delight's spike trap throws an AssertionError when asked the vanilla way rather than NeoForge's
 * (NeoForge 1.21.1, Diorama 0.8.0).
 */
final class FailingModelTest {
	private FailingModelTest() {
	}

	static void run(ClientGameTestContext context) {
		context.runOnClient(mc -> {
			// Gold block's model throws on every call given a random source (the faces on every
			// version) and answers the rest as gold block, so gold takes its particle texture's
			// colour, which for gold is its face's. An Error, not an exception, as the spike trap's.
			assertLooksUnchanged(mc, Blocks.GOLD_BLOCK.defaultBlockState(), method -> Arrays.asList(method.getParameterTypes()).contains(RandomSource.class));
			//? if <1.21.5 && !fabric {
			/*// Grass block's model throws only when asked the vanilla way, as the spike trap's does,
			// and answers NeoForge's and Forge's form of the call. Asked that form, grass keeps its
			// own faces' colours; asked the vanilla way it would fall back to its particle, dirt.
			assertLooksUnchanged(mc, Blocks.GRASS_BLOCK.defaultBlockState(), method -> Arrays.equals(method.getParameterTypes(),
				new Class<?>[] {BlockState.class, Direction.class, RandomSource.class}));
			*///?}
		});
	}

	/**
	 * Builds the table with the state's model swapped for one that throws on the methods
	 * {@code throwsOn} picks and answers the rest as the real model; the state must look as it does
	 * with the real model.
	 */
	private static void assertLooksUnchanged(Minecraft mc, BlockState state, Predicate<Method> throwsOn) {
		int id = Block.BLOCK_STATE_REGISTRY.getId(state);
		BlockLooks real = BlockLooks.build();
		Object models = models(mc);
		Field field = modelMap(models);
		BlockLooks looks;
		// Calls given a random source: asked for faces at all, the vanilla way or the loader's.
		int[] asked = {0};
		try {
			@SuppressWarnings("unchecked")
			Map<BlockState, Object> byState = (Map<BlockState, Object>) field.get(models);
			Object realModel = byState.get(state);
			InvocationHandler failing = (proxy, method, args) -> {
				if (Arrays.asList(method.getParameterTypes()).contains(RandomSource.class)) {
					asked[0]++;
				}
				if (throwsOn.test(method)) {
					throw new AssertionError("A stand-in for a mod's model that can't be asked this way");
				}
				try {
					return method.invoke(realModel, args);
				} catch (InvocationTargetException e) {
					throw e.getCause();
				}
			};
			Map<BlockState, Object> swapped = new HashMap<>(byState);
			swapped.put(state, Proxy.newProxyInstance(modelType(field).getClassLoader(), new Class<?>[] {modelType(field)}, failing));
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
		if (asked[0] == 0) {
			throw new AssertionError("The table never asked the stand-in model for " + state + "'s faces");
		}
		if (looks.kind[id] != real.kind[id] || looks.top[id] != real.top[id] || looks.side[id] != real.side[id] || looks.tint[id] != real.tint[id]) {
			throw new AssertionError(String.format("%s with a failing model looks %d #%06x/#%06x tint %d, not %d #%06x/#%06x tint %d", state,
				looks.kind[id], looks.top[id], looks.side[id], looks.tint[id], real.kind[id], real.top[id], real.side[id], real.tint[id]));
		}
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

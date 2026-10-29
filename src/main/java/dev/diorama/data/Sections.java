package dev.diorama.data;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.SingleValuePalette;
import org.jspecify.annotations.Nullable;

/** Reads a section's palette type, which vanilla keeps private. */
final class Sections {
	//? if >=26.1 {
	private static final Field DATA;
	private static final Method PALETTE;

	static {
		try {
			DATA = PalettedContainer.class.getDeclaredField("data");
			DATA.setAccessible(true);
			PALETTE = DATA.getType().getDeclaredMethod("palette");
			PALETTE.setAccessible(true);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException("PalettedContainer layout changed", e);
		}
	}
	//?} else if <1.21.9 {
	/*// The container's data record and its storage, found by type: outside the dev environment the
	// game runs with intermediary or SRG names. Null if the layout isn't the one expected.
	private static final java.lang.invoke.@Nullable MethodHandle STORAGE;

	static {
		java.lang.invoke.MethodHandle storage = null;
		try {
			for (Field field : PalettedContainer.class.getDeclaredFields()) {
				Class<?> type = field.getType();
				if (!type.isRecord() || type.getEnclosingClass() != PalettedContainer.class) {
					continue;
				}
				for (java.lang.reflect.RecordComponent component : type.getRecordComponents()) {
					if (component.getType() == net.minecraft.util.BitStorage.class) {
						field.setAccessible(true);
						Method accessor = component.getAccessor();
						accessor.setAccessible(true);
						java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.lookup();
						storage = java.lang.invoke.MethodHandles.filterReturnValue(lookup.unreflectGetter(field), lookup.unreflect(accessor))
							.asType(java.lang.invoke.MethodType.methodType(net.minecraft.util.BitStorage.class, PalettedContainer.class));
					}
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			storage = null;
		}
		STORAGE = storage;
	}
	*///?}

	private Sections() {
	}

	/** The block filling the section if it uses a single-value palette, otherwise null. */
	static @Nullable BlockState singleValue(PalettedContainer<BlockState> states) {
		//? if >=26.1 {
		try {
			Object palette = PALETTE.invoke(DATA.get(states));
			return palette instanceof SingleValuePalette<?> ? states.get(0, 0, 0) : null;
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
		//?} else if >=1.21.9 {
		/*// Outside the dev environment the game runs with intermediary names, so the private
		// fields can't be looked up by name. Storage of zero bits per entry only ever comes with the
		// single-value palette (Strategy.ZERO_BITS).
		return states.bitsPerEntry() == 0 ? states.get(0, 0, 0) : null;
		*///?} else {
		/*// No bitsPerEntry() yet. Storage of zero bits per entry only ever comes with the single-value
		// palette. The serialized size tells too, but works it out from the whole palette, slowly
		// with Lithium's: a single-value palette needs at most 6 bytes and has no storage, while any
		// other palette stores 4096 entries of at least one bit, 512 bytes or more (#180).
		if (STORAGE != null) {
			try {
				return ((net.minecraft.util.BitStorage) STORAGE.invokeExact(states)).getBits() == 0 ? states.get(0, 0, 0) : null;
			} catch (Throwable e) {
				// Falls through to the size.
			}
		}
		return states.getSerializedSize() < 512 ? states.get(0, 0, 0) : null;
		*///?}
	}
}

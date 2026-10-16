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
	//?}

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
		//?} else {
		/*// Outside the dev environment the game runs with intermediary names, so the private
		// fields can't be looked up by name. Storage of zero bits per entry only ever comes with the
		// single-value palette (Strategy.ZERO_BITS).
		return states.bitsPerEntry() == 0 ? states.get(0, 0, 0) : null;
		*///?}
	}
}

package dev.diorama.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
//? if <1.21.5 {
/*import net.minecraft.nbt.Tag;
*///?}
import org.jspecify.annotations.Nullable;

/**
 * Reading saved NBT the same way on every version: its getters changed in 1.21.5. A missing value
 * reads as an empty one. No client classes, so the server half uses it too.
 */
public final class Nbt {
	private Nbt() {
	}

	//? if >=1.21.5 {
	public static CompoundTag compound(CompoundTag tag, String name) {
		return tag.getCompoundOrEmpty(name);
	}

	public static CompoundTag compound(ListTag list, int index) {
		return list.getCompoundOrEmpty(index);
	}

	public static ListTag list(CompoundTag tag, String name) {
		return tag.getListOrEmpty(name);
	}

	public static String string(CompoundTag tag, String name) {
		return tag.getStringOr(name, "");
	}

	public static int sectionY(CompoundTag section) {
		return section.getByteOr("Y", (byte) 0);
	}

	public static long longValue(CompoundTag tag, String name) {
		return tag.getLongOr(name, 0L);
	}

	public static byte @Nullable [] bytes(CompoundTag tag, String name) {
		return tag.getByteArray(name).orElse(null);
	}

	public static long @Nullable [] longs(CompoundTag tag, String name) {
		return tag.getLongArray(name).orElse(null);
	}
	//?} else {
	/*public static CompoundTag compound(CompoundTag tag, String name) {
		return tag.getCompound(name);
	}

	public static CompoundTag compound(ListTag list, int index) {
		return list.getCompound(index);
	}

	public static ListTag list(CompoundTag tag, String name) {
		return tag.getList(name, Tag.TAG_COMPOUND);
	}

	public static String string(CompoundTag tag, String name) {
		return tag.getString(name);
	}

	public static int sectionY(CompoundTag section) {
		return section.getByte("Y");
	}

	public static long longValue(CompoundTag tag, String name) {
		return tag.getLong(name);
	}

	public static byte @Nullable [] bytes(CompoundTag tag, String name) {
		return tag.contains(name, Tag.TAG_BYTE_ARRAY) ? tag.getByteArray(name) : null;
	}

	public static long @Nullable [] longs(CompoundTag tag, String name) {
		return tag.contains(name, Tag.TAG_LONG_ARRAY) ? tag.getLongArray(name) : null;
	}
	*///?}
}

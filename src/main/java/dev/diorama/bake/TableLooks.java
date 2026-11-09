package dev.diorama.bake;

import dev.diorama.data.Copycats;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block looks with vanilla colours from {@link VanillaTable}, for where there are no textures to
 * read: a dedicated server drawing the web map. Everything but the colours comes from the
 * game's rules, as for {@link BlockLooks}; a block the table doesn't know takes its colour on
 * Minecraft's own maps.
 */
public final class TableLooks extends Looks {
	private final VanillaTable table;
	/** The table's look by state id, -1 for a state it doesn't know. */
	private final int[] lookOf;

	public TableLooks(VanillaTable table) {
		super(Block.BLOCK_STATE_REGISTRY.size(), Copycats.INSTALLED ? Block.BLOCK_STATE_REGISTRY.size() + COPYCAT_LOOKS : Block.BLOCK_STATE_REGISTRY.size());
		this.table = table;
		this.lookOf = table.lookByStateId();
		// What a bake reads where a section's palette has no entry.
		this.ensure(VoxelReader.AIR_ID);
	}

	@Override
	protected void make(int id) {
		synchronized (this) {
			if (this.kind[id] != NOT_YET) {
				return;
			}
			BlockState state = Block.BLOCK_STATE_REGISTRY.byId(id);
			byte kind = state == null ? AIR : this.work(id, state);
			KINDS.setRelease(this.kind, id, kind);
		}
	}

	@Override
	protected byte colours(int id, BlockState state, byte kind) {
		int index = id < this.lookOf.length ? this.lookOf[id] : -1;
		if (index < 0) {
			return this.flatColours(id, state, kind);
		}
		VanillaTable.Look look = this.table.looks.get(index);
		this.top[id] = look.top();
		this.topTinted[id] = look.topTinted();
		this.topTintWeight[id] = look.topTintWeight();
		this.side[id] = look.side();
		this.sideTinted[id] = look.sideTinted();
		this.sideTintWeight[id] = look.sideTintWeight();
		this.tint[id] = look.tint();
		this.constantTint[id] = look.constantTint();
		// The game's rules here say how rays treat the block; the table only adds what its
		// textures say, as BlockLooks's do: a see-through solid is glass, a block with nothing to
		// show is air.
		if (look.kind() == AIR || kind == SOLID && look.kind() == GLASS) {
			return look.kind();
		}
		return kind;
	}
}

package dev.diorama.bake;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import org.jspecify.annotations.Nullable;

/**
 * Vanilla Minecraft's block colours as Diorama's own client works them out with no resource pack,
 * shipped in the jar so a dedicated server, which has no textures, draws the web map in the same
 * colours. A game test makes the table for each Minecraft version ({@code vanilla-table}) and
 * checks the one in the jar still matches the game.
 *
 * <p>Holds only what comes from textures and the client's tint code: each look's colours, tint,
 * and kind (a see-through texture makes a block {@link Looks#GLASS}). The rest of a look, its
 * outline, light and spawn rules, the server works out from the game's rules as a client does.
 * Looks are kept once each and states point at them, by block id and the block's properties, so
 * a table made on one patch version still fits blocks that didn't change on the next. Also holds
 * the grass, foliage and dry foliage colour maps, which a server has no copy of either, at a
 * quarter of their size.
 */
public final class VanillaTable {
	/** Where the jar keeps the table for the Minecraft version it was built for. */
	public static final String RESOURCE = "/diorama/vanilla_looks.dat";
	private static final int MAGIC = 0x44564C54;
	private static final int FORMAT = 1;
	/** Colour maps are 256 pixels a side; the table keeps one pixel for each 4 by 4. */
	static final int MAP_STEP = 4;
	static final int MAP_SIZE = 256 / MAP_STEP;
	public static final int GRASS = 0;
	public static final int FOLIAGE = 1;
	public static final int DRY_FOLIAGE = 2;

	/** One look's colours. */
	public record Look(byte kind, byte tint, int top, int topTinted, float topTintWeight, int side, int sideTinted, float sideTintWeight, int constantTint) {
		static Look of(Looks looks, int id) {
			return new Look(looks.kind[id], looks.tint[id], looks.top[id], looks.topTinted[id], looks.topTintWeight[id],
				looks.side[id], looks.sideTinted[id], looks.sideTintWeight[id], looks.constantTint[id]);
		}
	}

	/** A block's properties, each with its values in the game's order, and its states' looks in the game's order. */
	record Entry(List<String> signature, int[] looks) {
	}

	final List<Look> looks;
	final Map<String, Entry> blocks;
	/** {@link #GRASS}, {@link #FOLIAGE}, {@link #DRY_FOLIAGE}: {@link #MAP_SIZE} squared 0xRRGGBB, or null if the version has none. */
	final int @Nullable [][] colourMaps;

	VanillaTable(List<Look> looks, Map<String, Entry> blocks, int @Nullable [][] colourMaps) {
		this.looks = looks;
		this.blocks = blocks;
		this.colourMaps = colourMaps;
	}

	private static @Nullable VanillaTable shipped;
	private static boolean loaded;

	/** The table in the jar, or null if this jar has none (a version nobody made one for yet). */
	public static synchronized @Nullable VanillaTable shipped() {
		if (!loaded) {
			loaded = true;
			try (InputStream in = VanillaTable.class.getResourceAsStream(RESOURCE)) {
				shipped = in == null ? null : read(in);
			} catch (IOException | RuntimeException e) {
				dev.diorama.Diorama.LOGGER.warn("Couldn't read the vanilla colour table: {}", e.toString());
			}
		}
		return shipped;
	}

	/** How many looks and blocks the table has, for the log. */
	public String describe() {
		return this.looks.size() + " looks for " + this.blocks.size() + " blocks";
	}

	/** The table's look for each state the registry numbers, by state id; -1 for a state it doesn't know. */
	int[] lookByStateId() {
		int[] out = new int[Block.BLOCK_STATE_REGISTRY.size()];
		Arrays.fill(out, -1);
		for (Block block : BuiltInRegistries.BLOCK) {
			Entry entry = this.blocks.get(BuiltInRegistries.BLOCK.getKey(block).toString());
			if (entry == null) {
				continue;
			}
			List<BlockState> states = block.getStateDefinition().getPossibleStates();
			// A block whose properties changed since the table was made gets nothing rather than
			// another state's look.
			if (states.size() != entry.looks().length || !signature(block).equals(entry.signature())) {
				continue;
			}
			for (int i = 0; i < states.size(); i++) {
				int id = Block.BLOCK_STATE_REGISTRY.getId(states.get(i));
				if (id >= 0 && id < out.length) {
					out[id] = entry.looks()[i];
				}
			}
		}
		return out;
	}

	/**
	 * The colour map blown back up to the game's 256 by 256, in the layout {@code GrassColor.init}
	 * takes, or null if the table has none.
	 */
	public int @Nullable [] colourMap(int which) {
		int[][] maps = this.colourMaps;
		int[] small = maps == null || which >= maps.length ? null : maps[which];
		if (small == null) {
			return null;
		}
		int[] out = new int[256 * 256];
		for (int y = 0; y < 256; y++) {
			// Each kept pixel sits at the middle of its 4 by 4: bilinear between the nearest four.
			float fy = Math.max(0, Math.min(MAP_SIZE - 1, (y + 0.5F) / MAP_STEP - 0.5F));
			int y0 = (int) fy;
			int y1 = Math.min(MAP_SIZE - 1, y0 + 1);
			float ty = fy - y0;
			for (int x = 0; x < 256; x++) {
				float fx = Math.max(0, Math.min(MAP_SIZE - 1, (x + 0.5F) / MAP_STEP - 0.5F));
				int x0 = (int) fx;
				int x1 = Math.min(MAP_SIZE - 1, x0 + 1);
				float tx = fx - x0;
				int rgb = 0;
				for (int shift = 0; shift <= 16; shift += 8) {
					float a = (small[y0 * MAP_SIZE + x0] >> shift & 0xFF) * (1 - tx) + (small[y0 * MAP_SIZE + x1] >> shift & 0xFF) * tx;
					float b = (small[y1 * MAP_SIZE + x0] >> shift & 0xFF) * (1 - tx) + (small[y1 * MAP_SIZE + x1] >> shift & 0xFF) * tx;
					rgb |= Math.round(a * (1 - ty) + b * ty) << shift;
				}
				out[y * 256 + x] = 0xFF000000 | rgb;
			}
		}
		return out;
	}

	/** The block's properties and their values, as the game orders its states by them. */
	static List<String> signature(Block block) {
		List<String> out = new ArrayList<>();
		for (Property<?> property : block.getStateDefinition().getProperties()) {
			out.add(property.getName() + "=" + String.join(",", valueNames(property)));
		}
		return out;
	}

	private static <T extends Comparable<T>> List<String> valueNames(Property<T> property) {
		List<String> out = new ArrayList<>();
		for (T value : property.getPossibleValues()) {
			out.add(property.getName(value));
		}
		return out;
	}

	/**
	 * The table of every vanilla block's look in {@code looks}, working each out first, and the
	 * game's colour maps as they are now. For the game test that makes the shipped table.
	 */
	public static VanillaTable of(Looks looks) {
		List<Look> kept = new ArrayList<>();
		Map<Look, Integer> index = new HashMap<>();
		Map<String, Entry> blocks = new java.util.TreeMap<>();
		for (Block block : BuiltInRegistries.BLOCK) {
			String name = BuiltInRegistries.BLOCK.getKey(block).toString();
			if (!name.startsWith("minecraft:")) {
				continue;
			}
			List<BlockState> states = block.getStateDefinition().getPossibleStates();
			int[] ids = new int[states.size()];
			for (int i = 0; i < ids.length; i++) {
				int id = looks.lookId(states.get(i));
				ids[i] = index.computeIfAbsent(Look.of(looks, id), look -> {
					kept.add(look);
					return kept.size() - 1;
				});
			}
			blocks.put(name, new Entry(signature(block), ids));
		}
		int[][] maps = new int[3][];
		maps[GRASS] = shrink(GrassColor::get);
		maps[FOLIAGE] = shrink(FoliageColor::get);
		//? if >=1.21.5 {
		maps[DRY_FOLIAGE] = shrink(net.minecraft.world.level.DryFoliageColor::get);
		//?}
		return new VanillaTable(kept, blocks, maps);
	}

	private interface ColourMap {
		int get(double temperature, double downfall);
	}

	/**
	 * Reads a colour map back through the game's own lookup and keeps the average of each 4 by 4.
	 * Only pixels the lookup can reach count (downfall times temperature is never above the
	 * temperature); a square with none takes its nearest reachable neighbour's colour, so the
	 * blow-up blends into nothing the game never reads.
	 */
	private static int[] shrink(ColourMap map) {
		int[] full = new int[256 * 256];
		boolean[] reached = new boolean[256 * 256];
		for (int y = 0; y < 256; y++) {
			for (int x = 0; x <= y; x++) {
				// The lookup is index = (int) ((1 - downfall * temp) * 255) << 8 | (int) ((1 - temp) * 255).
				double temp = 1 - (x + 0.5) / 255;
				double rain = (1 - (y + 0.5) / 255) / temp;
				if (rain > 1 || temp <= 0) {
					continue;
				}
				full[y * 256 + x] = map.get(temp, rain) & 0xFFFFFF;
				reached[y * 256 + x] = true;
			}
		}
		int[] small = new int[MAP_SIZE * MAP_SIZE];
		boolean[] filled = new boolean[MAP_SIZE * MAP_SIZE];
		for (int sy = 0; sy < MAP_SIZE; sy++) {
			for (int sx = 0; sx < MAP_SIZE; sx++) {
				int r = 0;
				int g = 0;
				int b = 0;
				int n = 0;
				for (int y = sy * MAP_STEP; y < (sy + 1) * MAP_STEP; y++) {
					for (int x = sx * MAP_STEP; x < (sx + 1) * MAP_STEP; x++) {
						if (reached[y * 256 + x]) {
							int c = full[y * 256 + x];
							r += c >> 16 & 0xFF;
							g += c >> 8 & 0xFF;
							b += c & 0xFF;
							n++;
						}
					}
				}
				if (n > 0) {
					small[sy * MAP_SIZE + sx] = Math.round((float) r / n) << 16 | Math.round((float) g / n) << 8 | Math.round((float) b / n);
					filled[sy * MAP_SIZE + sx] = true;
				}
			}
		}
		// Unreachable squares (above the diagonal) copy the square on the diagonal in their row.
		for (int sy = 0; sy < MAP_SIZE; sy++) {
			for (int sx = 0; sx < MAP_SIZE; sx++) {
				if (!filled[sy * MAP_SIZE + sx]) {
					int near = Math.min(sy, MAP_SIZE - 1);
					small[sy * MAP_SIZE + sx] = small[sy * MAP_SIZE + near];
				}
			}
		}
		return small;
	}

	public void write(OutputStream raw) throws IOException {
		try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(raw)))) {
			out.writeInt(MAGIC);
			out.writeInt(FORMAT);
			out.writeInt(this.looks.size());
			for (Look look : this.looks) {
				out.writeByte(look.kind());
				out.writeByte(look.tint());
				out.writeInt(look.top());
				out.writeInt(look.topTinted());
				out.writeFloat(look.topTintWeight());
				out.writeInt(look.side());
				out.writeInt(look.sideTinted());
				out.writeFloat(look.sideTintWeight());
				out.writeInt(look.constantTint());
			}
			out.writeInt(this.blocks.size());
			for (Map.Entry<String, Entry> block : this.blocks.entrySet()) {
				out.writeUTF(block.getKey());
				List<String> signature = block.getValue().signature();
				out.writeByte(signature.size());
				for (String property : signature) {
					out.writeUTF(property);
				}
				int[] ids = block.getValue().looks();
				out.writeInt(ids.length);
				for (int id : ids) {
					writeVarInt(out, id);
				}
			}
			int[][] maps = this.colourMaps == null ? new int[0][] : this.colourMaps;
			out.writeByte(maps.length);
			for (int[] map : maps) {
				out.writeBoolean(map != null);
				if (map != null) {
					for (int rgb : map) {
						out.writeByte(rgb >> 16);
						out.writeByte(rgb >> 8);
						out.writeByte(rgb);
					}
				}
			}
		}
	}

	public static VanillaTable read(InputStream raw) throws IOException {
		DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(raw)));
		if (in.readInt() != MAGIC || in.readInt() != FORMAT) {
			throw new IOException("not a vanilla colour table of format " + FORMAT);
		}
		int count = in.readInt();
		List<Look> looks = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			looks.add(new Look(in.readByte(), in.readByte(), in.readInt(), in.readInt(), in.readFloat(), in.readInt(), in.readInt(), in.readFloat(), in.readInt()));
		}
		int blockCount = in.readInt();
		Map<String, Entry> blocks = new HashMap<>(blockCount * 2);
		for (int b = 0; b < blockCount; b++) {
			String name = in.readUTF();
			int properties = in.readUnsignedByte();
			List<String> signature = new ArrayList<>(properties);
			for (int p = 0; p < properties; p++) {
				signature.add(in.readUTF());
			}
			int[] ids = new int[in.readInt()];
			for (int i = 0; i < ids.length; i++) {
				ids[i] = readVarInt(in);
				if (ids[i] < 0 || ids[i] >= count) {
					throw new IOException("look " + ids[i] + " of " + name + " is past the " + count + " looks");
				}
			}
			blocks.put(name, new Entry(List.copyOf(signature), ids));
		}
		int mapCount = in.readUnsignedByte();
		int[][] maps = new int[mapCount][];
		for (int m = 0; m < mapCount; m++) {
			if (in.readBoolean()) {
				int[] map = new int[MAP_SIZE * MAP_SIZE];
				for (int i = 0; i < map.length; i++) {
					map[i] = in.readUnsignedByte() << 16 | in.readUnsignedByte() << 8 | in.readUnsignedByte();
				}
				maps[m] = map;
			}
		}
		return new VanillaTable(looks, blocks, maps);
	}

	/** Every difference from another table, one line each, at most {@code limit} lines. */
	public List<String> differences(VanillaTable other, int limit) {
		List<String> out = new ArrayList<>();
		java.util.Set<String> names = new java.util.TreeSet<>(this.blocks.keySet());
		names.addAll(other.blocks.keySet());
		for (String name : names) {
			Entry a = this.blocks.get(name);
			Entry b = other.blocks.get(name);
			if (a == null || b == null) {
				out.add(name + (a == null ? " is new" : " is gone"));
			} else if (!a.signature().equals(b.signature()) || a.looks().length != b.looks().length) {
				out.add(name + " has other properties: " + a.signature() + " vs " + b.signature());
			} else {
				for (int i = 0; i < a.looks().length; i++) {
					Look la = this.looks.get(a.looks()[i]);
					Look lb = other.looks.get(b.looks()[i]);
					if (!la.equals(lb)) {
						out.add(name + " state " + i + ": " + la + " vs " + lb);
						break;
					}
				}
			}
			if (out.size() >= limit) {
				return out;
			}
		}
		for (int m = 0; m < 3; m++) {
			int[] a = this.colourMaps == null || m >= this.colourMaps.length ? null : this.colourMaps[m];
			int[] b = other.colourMaps == null || m >= other.colourMaps.length ? null : other.colourMaps[m];
			if (!Arrays.equals(a, b)) {
				out.add("colour map " + m + " differs");
			}
		}
		return out;
	}

	private static void writeVarInt(DataOutputStream out, int value) throws IOException {
		while ((value & ~0x7F) != 0) {
			out.writeByte(value & 0x7F | 0x80);
			value >>>= 7;
		}
		out.writeByte(value);
	}

	private static int readVarInt(DataInputStream in) throws IOException {
		int value = 0;
		for (int shift = 0; shift < 35; shift += 7) {
			int b = in.readUnsignedByte();
			value |= (b & 0x7F) << shift;
			if ((b & 0x80) == 0) {
				return value;
			}
		}
		throw new IOException("varint too long");
	}
}

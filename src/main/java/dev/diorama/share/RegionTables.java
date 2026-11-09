package dev.diorama.share;

import dev.diorama.Diorama;
import dev.diorama.data.ChunkTags;
import dev.diorama.data.Nbt;
import dev.diorama.mixin.ChunkMapInvoker;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Which chunks of one level the server shares, region file by region file: for each chunk, when the
 * save last wrote it, and for those the share rule allows, how high it reaches and how deep its sky
 * view goes, so a client can plan which tiles to draw before it asks for any blocks. Worked out from
 * the region files' headers and a scan of each chunk's status, height maps and inhabited time, and
 * kept in memory: only chunks saved again since are scanned again. Nothing is written anywhere.
 *
 * <p>The share worker's own: one thread at a time.
 */
final class RegionTables {
	private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d{1,8})\\.(-?\\d{1,8})\\.mca");
	private static final long READ_SECONDS = 30;
	private static final int SCANS_AT_ONCE = 32;

	private final ServerLevel level;
	private final ChunkMap chunkMap;
	private final Path regionFolder;
	private final ChunkTags tags;
	private final Map<Long, Table> tables = new HashMap<>();

	/** One region's chunks, indexed {@code (z << 5) | x}. */
	static final class Table {
		/** When the save last wrote each chunk, in seconds, or 0 if it has none. */
		final int[] saved = new int[1024];
		/** The same, for the chunks the share rule allows; 0 for the rest. */
		final int[] shared = new int[1024];
		final short[] top = new short[1024];
		final short[] floor = new short[1024];
		/** Whether each chunk's inhabited time is above 0, as of when it was saved. */
		final boolean[] visited = new boolean[1024];
		final boolean[] finished = new boolean[1024];
		/** When the header was last read, in milliseconds; 0 for never. */
		long readAt;
	}

	/** A table read this recently is taken as it is: serving a region's chunks reads it for each. */
	private static final long FRESH_MS = 5_000;

	RegionTables(ServerLevel level) {
		this.level = level;
		this.chunkMap = level.getChunkSource().chunkMap;
		this.regionFolder = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region");
		this.tags = new ChunkTags(level, false);
	}

	ServerLevel level() {
		return this.level;
	}

	ChunkTags tags() {
		return this.tags;
	}

	/** The regions with a file in the save, by {@link #key}. */
	List<Long> regions() {
		List<Long> regions = new ArrayList<>();
		if (!Files.isDirectory(this.regionFolder)) {
			return regions;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(this.regionFolder, "r.*.mca")) {
			for (Path file : files) {
				Matcher m = REGION_FILE.matcher(file.getFileName().toString());
				if (m.matches() && Files.size(file) >= 8192) {
					regions.add(key(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))));
				}
			}
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't list the region files in {}", this.regionFolder, e);
		}
		return regions;
	}

	static long key(int regionX, int regionZ) {
		return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
	}

	/**
	 * The region's table as the save has it now, scanning the chunks saved since it was last worked
	 * out. {@code generated}: every finished chunk is shared, not only visited ones.
	 */
	Table table(int regionX, int regionZ, boolean generated) {
		long key = key(regionX, regionZ);
		if (!Files.isRegularFile(this.regionFolder.resolve("r." + regionX + "." + regionZ + ".mca"))) {
			// Nothing kept for regions with no file: a client can name any.
			this.tables.remove(key);
			return new Table();
		}
		Table table = this.tables.computeIfAbsent(key, k -> new Table());
		long now = System.currentTimeMillis();
		if (now - table.readAt < FRESH_MS) {
			return table;
		}
		table.readAt = now;
		int[] times = readHeader(this.regionFolder.resolve("r." + regionX + "." + regionZ + ".mca"));
		List<Integer> changed = new ArrayList<>();
		for (int i = 0; i < 1024; i++) {
			int time = times == null ? 0 : times[i];
			if (time != table.saved[i]) {
				table.saved[i] = time;
				table.finished[i] = false;
				if (time != 0) {
					changed.add(i);
				}
			}
		}
		// A few at a time on the server's storage thread, rather than one wait each.
		for (int from = 0; from < changed.size(); from += SCANS_AT_ONCE) {
			List<Integer> part = changed.subList(from, Math.min(changed.size(), from + SCANS_AT_ONCE));
			List<CollectFields> fields = new ArrayList<>(part.size());
			List<CompletableFuture<Void>> scans = new ArrayList<>(part.size());
			for (int i : part) {
				CollectFields wanted = fields();
				fields.add(wanted);
				scans.add(this.chunkMap.chunkScanner().scanChunk(pos(regionX, regionZ, i), wanted));
			}
			for (int n = 0; n < part.size(); n++) {
				this.scanned(table, part.get(n), regionX, regionZ, scans.get(n), fields.get(n));
			}
		}
		for (int i = 0; i < 1024; i++) {
			table.shared[i] = table.saved[i] != 0 && table.finished[i] && (generated || table.visited[i]) ? table.saved[i] : 0;
		}
		return table;
	}

	private static ChunkPos pos(int regionX, int regionZ, int index) {
		return new ChunkPos((regionX << 5) | (index & 31), (regionZ << 5) | (index >> 5));
	}

	/** Whether the share rule allows a chunk, by its saved tag. */
	static boolean allowed(CompoundTag tag, boolean generated) {
		return ChunkTags.finished(tag) && (generated || Nbt.longValue(tag, "InhabitedTime") > 0);
	}

	private static CollectFields fields() {
		return new CollectFields(
			new FieldSelector(IntTag.TYPE, "DataVersion"),
			new FieldSelector(StringTag.TYPE, "Status"),
			new FieldSelector(LongTag.TYPE, "InhabitedTime"),
			new FieldSelector(CompoundTag.TYPE, "Heightmaps"),
			new FieldSelector(CompoundTag.TYPE, "below_zero_retrogen")
		);
	}

	/** Notes what a chunk's scan found. */
	private void scanned(Table table, int index, int regionX, int regionZ, CompletableFuture<Void> scan, CollectFields fields) {
		ChunkPos pos = pos(regionX, regionZ, index);
		CompoundTag tag;
		try {
			scan.get(READ_SECONDS, TimeUnit.SECONDS);
			tag = fields.getResult() instanceof CompoundTag found ? found : null;
			if (tag != null && NbtUtils.getDataVersion(tag, -1) < ChunkTags.dataVersion()) {
				// Saved by an older version: its fields only mean what they say once it's upgraded.
				tag = this.read(pos);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			table.saved[index] = 0;
			return;
		} catch (Exception e) {
			Diorama.LOGGER.warn("Couldn't scan saved chunk {} to share it", pos, e);
			// Scanned again next time rather than taken for unshared until it's saved again.
			table.saved[index] = 0;
			return;
		}
		if (tag == null || tag.isEmpty() || !ChunkTags.finished(tag)) {
			return;
		}
		int[] surface = this.tags.surface(tag);
		int[] solid = this.tags.solid(tag);
		if (surface == null || solid == null) {
			return;
		}
		ChunkTags.Summary summary = ChunkTags.summaryOf(surface, solid);
		table.finished[index] = summary.maxY() > this.level.getMinY();
		table.top[index] = (short) summary.maxY();
		table.floor[index] = (short) Math.max(Short.MIN_VALUE, summary.floorY());
		table.visited[index] = Nbt.longValue(tag, "InhabitedTime") > 0;
	}

	/** Starts reading a chunk as saved, on the server's storage thread. */
	CompletableFuture<Optional<CompoundTag>> readLater(ChunkPos pos) {
		return this.chunkMap.read(pos);
	}

	/** A saved tag brought up to this version. */
	CompoundTag upgrade(CompoundTag tag) {
		return ((ChunkMapInvoker) this.chunkMap).diorama$upgrade(tag);
	}

	/** The chunk as saved, brought up to this version, or null if the save has none. Blocks. */
	@Nullable CompoundTag read(ChunkPos pos) throws Exception {
		Optional<CompoundTag> read = this.chunkMap.read(pos).get(READ_SECONDS, TimeUnit.SECONDS);
		return read.isEmpty() ? null : ((ChunkMapInvoker) this.chunkMap).diorama$upgrade(read.get());
	}

	/** A region file's chunk save times, from its header: 4 KiB of locations, then 4 KiB of times. */
	private static int @Nullable [] readHeader(Path file) {
		if (!Files.exists(file)) {
			return null;
		}
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			ByteBuffer header = ByteBuffer.allocate(8192);
			while (header.hasRemaining() && channel.read(header) >= 0) {
			}
			if (header.hasRemaining()) {
				return null;
			}
			header.flip();
			int[] times = new int[1024];
			for (int i = 0; i < 1024; i++) {
				int location = header.getInt(i * 4);
				times[i] = location == 0 ? 0 : Math.max(1, header.getInt(4096 + i * 4));
			}
			return times;
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't read the region file header of {}", file, e);
			return null;
		}
	}
}

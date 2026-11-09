package dev.diorama.web;

import dev.diorama.Diorama;
import dev.diorama.data.ChunkSave;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.data.ChunkTags;
import dev.diorama.data.Nbt;
import dev.diorama.data.SavedChunk;
import dev.diorama.mixin.ChunkMapInvoker;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * A dedicated server's own save, as the web map reads it: straight from the region files, as
 * BlueMap reads them, rather than through the server's storage thread. That thread runs reads
 * before writes, and reads through it held back the server's own saving: a flushing save
 * (save-all flush, a backup mod's) waited close to a minute behind them while players explored.
 * Chunks the server hasn't written yet aren't seen until it does, which is all the web map draws
 * anyway. Names no client class. One thread at a time.
 */
final class ServerSave implements ChunkSave {
	private static final Pattern REGION_FILE = Pattern.compile("r\\.(-?\\d{1,8})\\.(-?\\d{1,8})\\.mca");
	/** Chunks kept between reads: neighbouring tiles read many of the same. */
	private static final int CACHED_CHUNKS = 2048;
	private static final short UNKNOWN = Short.MIN_VALUE;
	private static final short NONE = Short.MIN_VALUE + 1;
	/** A server tick has 50 ms; past this the game is close to slowing down. */
	private static final long SERVER_BEHIND_NANOS = 40_000_000;

	private final ServerLevel level;
	private final Path regionFolder;
	private final ChunkTags tags;
	private final Map<Long, @Nullable SavedChunk> cache = new LinkedHashMap<>(CACHED_CHUNKS, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, @Nullable SavedChunk> eldest) {
			return this.size() > CACHED_CHUNKS;
		}
	};
	/** Each chunk's highest block by {@link #regionKey}, indexed {@code (z << 5) | x}; see {@link #top}. */
	private final Map<Long, short[]> tops = new HashMap<>();
	/** Reads of a chunk its region file has that failed every try; see {@link #failedReads}. */
	private final AtomicInteger failedReads = new AtomicInteger();
	private volatile boolean closed;

	private ServerSave(ServerLevel level) {
		this.level = level;
		this.regionFolder = DimensionType.getStorageFolder(level.dimension(), level.getServer().getWorldPath(LevelResource.ROOT)).resolve("region");
		this.tags = new ChunkTags(level, false);
	}

	static ServerSave of(ServerLevel level) {
		return new ServerSave(level);
	}

	static long regionKey(int regionX, int regionZ) {
		return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
	}

	/**
	 * What the web map needs to know of a chunk from one read: whether it's finished and how high
	 * and low it reaches, the game's count of ticks players spent near it, and a hash of its blocks
	 * and biomes (the game saves a chunk for much more than a change of those).
	 */
	record Info(ChunkTags.Summary summary, long inhabited, int blocks) {
	}

	/** {@link Info} for the chunk, or null if the save has none or it can't be read. */
	@Nullable Info info(int chunkX, int chunkZ) {
		CompoundTag tag = this.tag(chunkX, chunkZ);
		if (tag == null) {
			this.setTop(chunkX, chunkZ, null);
			return null;
		}
		ChunkTags.Summary summary = ChunkTags.Summary.UNFINISHED;
		if (ChunkTags.finished(tag)) {
			int[] surface = this.tags.surface(tag);
			int[] solid = this.tags.solid(tag);
			if (surface != null && solid != null) {
				summary = ChunkTags.summaryOf(surface, solid);
			}
		}
		this.setTop(chunkX, chunkZ, summary);
		ListTag sections = Nbt.list(tag, "sections");
		int hash = 1;
		for (int s = 0; s < sections.size(); s++) {
			CompoundTag section = Nbt.compound(sections, s);
			hash = hash * 31 + Nbt.sectionY(section);
			hash = hash * 31 + Nbt.compound(section, "block_states").hashCode();
			hash = hash * 31 + Nbt.compound(section, "biomes").hashCode();
		}
		return new Info(summary, Nbt.longValue(tag, "InhabitedTime"), hash);
	}

	/**
	 * How many reads of a chunk its region file has failed every try so far, as opposed to finding
	 * no chunk: a pass that saw this grow got less than the save holds, and reads again later.
	 */
	int failedReads() {
		return this.failedReads.get();
	}

	/** The chunk's tag, upgraded if an older version saved it; null if there's none or it can't be read. */
	private @Nullable CompoundTag tag(int chunkX, int chunkZ) {
		CompoundTag tag = this.readFile(chunkX, chunkZ);
		if (tag == null) {
			return null;
		}
		if (NbtUtils.getDataVersion(tag, -1) < ChunkTags.dataVersion()) {
			try {
				tag = ((ChunkMapInvoker) this.level.getChunkSource().chunkMap).diorama$upgrade(tag);
			} catch (RuntimeException e) {
				Diorama.LOGGER.warn("Couldn't upgrade saved chunk {}, {} for the web map", chunkX, chunkZ, e);
				return null;
			}
		}
		return tag;
	}

	/**
	 * The chunk as its region file has it, or null if it has none or it can't be read. A read that
	 * meets a chunk the server is writing fails to decompress, and is tried again a moment later.
	 */
	private @Nullable CompoundTag readFile(int chunkX, int chunkZ) {
		Path file = this.regionFolder.resolve("r." + (chunkX >> 5) + "." + (chunkZ >> 5) + ".mca");
		for (int attempt = 0; attempt < 3 && !this.closed; attempt++) {
			if (attempt > 0) {
				try {
					Thread.sleep(20);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return null;
				}
			}
			try {
				return this.readFile(file, chunkX, chunkZ);
			} catch (IOException | RuntimeException e) {
				// Being written: once more.
			}
		}
		this.failedReads.incrementAndGet();
		return null;
	}

	private @Nullable CompoundTag readFile(Path file, int chunkX, int chunkZ) throws IOException {
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			ByteBuffer slot = ByteBuffer.allocate(4);
			readFully(channel, slot, (long) (((chunkZ & 31) << 5) | (chunkX & 31)) * 4);
			int location = slot.getInt(0);
			if (location == 0) {
				return null;
			}
			int sectors = location & 0xFF;
			long start = (long) (location >>> 8) * 4096;
			// The length and format first, then just the chunk: the game pads the last sector of a
			// region file only when it closes the file, so the newest chunk's sector can end early.
			ByteBuffer head = ByteBuffer.allocate(5);
			readFully(channel, head, start);
			int length = head.getInt(0);
			int version = head.get(4) & 0xFF;
			InputStream raw;
			if ((version & 0x80) != 0) {
				// Too big for the region file: in a file of its own beside it.
				raw = Files.newInputStream(this.regionFolder.resolve("c." + chunkX + "." + chunkZ + ".mcc"));
				version &= 0x7F;
			} else {
				if (length < 1 || length > sectors * 4096 - 4) {
					throw new IOException("chunk length " + length + " past its sectors");
				}
				ByteBuffer data = ByteBuffer.allocate(length - 1);
				readFully(channel, data, start + 5);
				raw = new ByteArrayInputStream(data.array());
			}
			try (InputStream source = raw) {
				RegionFileVersion format = RegionFileVersion.fromId(version);
				if (format == null) {
					return null;
				}
				try (DataInputStream in = new DataInputStream(new BufferedInputStream(format.wrap(source)))) {
					return NbtIo.read(in);
				}
			}
		}
	}

	private static void readFully(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
		while (buffer.hasRemaining()) {
			if (channel.read(buffer, position + buffer.position()) < 0) {
				throw new IOException("region file ends early");
			}
		}
	}

	@Override
	public Level level() {
		return this.level;
	}

	@Override
	public boolean isOpen() {
		return !this.closed;
	}

	/**
	 * Every region file's chunks, by {@link #regionKey}: for each of its 32 × 32 chunks, indexed
	 * {@code (z << 5) | x}, when the save last wrote it in seconds, or 0 if it has none.
	 */
	@Override
	public Map<Long, int[]> scan() {
		Map<Long, int[]> regions = new HashMap<>();
		if (!Files.isDirectory(this.regionFolder)) {
			return regions;
		}
		try (DirectoryStream<Path> files = Files.newDirectoryStream(this.regionFolder, "r.*.mca")) {
			for (Path file : files) {
				Matcher m = REGION_FILE.matcher(file.getFileName().toString());
				if (!m.matches()) {
					continue;
				}
				int[] times = readHeader(file);
				if (times != null) {
					regions.put(regionKey(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))), times);
				}
			}
		} catch (IOException e) {
			Diorama.LOGGER.warn("Couldn't list the saved chunks in {}", this.regionFolder, e);
		}
		return regions;
	}

	/** A region file's chunk save times, from its header: 4 KiB of locations, then 4 KiB of times. */
	private int @Nullable [] readHeader(Path file) {
		try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			ByteBuffer header = ByteBuffer.allocate(8192);
			while (header.hasRemaining() && channel.read(header) >= 0) {
			}
			if (header.hasRemaining()) {
				// New and still empty, or cut short.
				return null;
			}
			header.flip();
			int[] times = new int[1024];
			for (int i = 0; i < 1024; i++) {
				int location = header.getInt(i * 4);
				int time = header.getInt(4096 + i * 4);
				times[i] = location == 0 ? 0 : Math.max(1, time);
			}
			return times;
		} catch (IOException e) {
			// Its chunks weren't seen: the pass reads again later.
			this.failedReads.incrementAndGet();
			Diorama.LOGGER.warn("Couldn't read the region file header of {}", file, e);
			return null;
		}
	}

	@Override
	public ChunkTags.@Nullable Summary summary(int chunkX, int chunkZ) {
		Info info = this.info(chunkX, chunkZ);
		return info == null ? null : info.summary();
	}

	private void setTop(int chunkX, int chunkZ, ChunkTags.@Nullable Summary summary) {
		short top = summary == null || !summary.finished() || summary.maxY() <= this.level.getMinY() ? NONE : (short) summary.maxY();
		this.tops.computeIfAbsent(regionKey(chunkX >> 5, chunkZ >> 5), k -> {
			short[] tops = new short[1024];
			Arrays.fill(tops, UNKNOWN);
			return tops;
		})[((chunkZ & 31) << 5) | (chunkX & 31)] = top;
	}

	@Override
	public int top(int chunkX, int chunkZ) {
		short[] tops = this.tops.get(regionKey(chunkX >> 5, chunkZ >> 5));
		short top = tops == null ? UNKNOWN : tops[((chunkZ & 31) << 5) | (chunkX & 31)];
		if (top == UNKNOWN) {
			this.info(chunkX, chunkZ);
			top = this.tops.get(regionKey(chunkX >> 5, chunkZ >> 5))[((chunkZ & 31) << 5) | (chunkX & 31)];
		}
		return top == NONE ? Integer.MIN_VALUE : top;
	}

	@Override
	public @Nullable SavedChunk read(int chunkX, int chunkZ) {
		long key = ChunkSnapshot.key(chunkX, chunkZ);
		if (this.cache.containsKey(key)) {
			return this.cache.get(key);
		}
		CompoundTag tag = this.tag(chunkX, chunkZ);
		SavedChunk chunk = null;
		if (tag != null) {
			try {
				chunk = this.tags.read(chunkX, chunkZ, tag);
			} catch (RuntimeException e) {
				Diorama.LOGGER.warn("Couldn't read saved chunk {}, {} for the web map", chunkX, chunkZ, e);
			}
		}
		if (!this.closed) {
			this.cache.put(key, chunk);
		}
		return chunk;
	}

	@Override
	public void forgetTops(long region) {
		this.tops.remove(region);
	}

	@Override
	public void clear() {
		this.cache.clear();
	}

	@Override
	public void close() {
		this.closed = true;
	}

	@Override
	public boolean serverBehind() {
		//? if >=1.21 {
		return this.level.getServer().getAverageTickTimeNanos() > SERVER_BEHIND_NANOS;
		//?} else {
		/*return this.level.getServer().getAverageTickTime() * 1_000_000 > SERVER_BEHIND_NANOS;
		*///?}
	}
}

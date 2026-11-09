package dev.diorama.share;

import dev.diorama.Diorama;
import dev.diorama.MapSession;
import dev.diorama.data.ChunkSave;
import dev.diorama.data.ChunkSnapshot;
import dev.diorama.data.ChunkTags;
import dev.diorama.data.SavedChunk;
import dev.diorama.data.SavedChunks;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.zip.InflaterInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * The land a server with Diorama shares, as a save the map fills in from: the same fill that draws
 * the world's save in singleplayer ({@code SavedChunkImport}) draws these, with this player's own
 * resource pack, facing and style, and keeps them in this server's map folder like land the player
 * walked. Only the sky view; caves stay what the player found.
 *
 * <p>The server lists the level's regions, then sends each region's table: which chunks it shares,
 * when each was saved, how high it reaches. That's all a fill needs to plan its tiles. Blocks come
 * when a tile is about to be drawn: the chunks behind the tiles on screen first, then a region's at
 * a time, nearest first, at the rate the server allows. Reads block until the chunk comes, from the
 * fill's own threads; messages arrive on the client thread.
 */
public final class SharedChunks implements ChunkSave {
	/** How long the first listing waits for the server, and a read for its chunk. */
	private static final long LIST_WAIT_MS = 30_000;
	private static final long FIRST_TABLES_WAIT_MS = 5_000;
	private static final long READ_WAIT_MS = 30_000;
	/** An ask not answered in this long is asked again: the server drops asks past its queue's length. */
	private static final long ASK_AGAIN_MS = 60_000;
	private static final int TABLES_PER_ASK = 256;
	/** The map asks for what changed at most this often. */
	private static final long REFRESH_MS = 10_000;
	private static final int KEPT_CHUNKS = 8192;
	private static final int PARSED_CHUNKS = Runtime.getRuntime().maxMemory() > 3L << 30 ? 2048 : 768;

	private static volatile @Nullable SharedChunks current;

	private final ClientLevel level;
	private final String dimension;
	private final ChunkTags tags;
	private final Object lock = new Object();
	private volatile boolean closed;
	private volatile @Nullable Runnable changed;

	/** The server's clock less this computer's, in milliseconds, as of its last list of regions. */
	private volatile long serverClock;
	/** When the map last asked for what changed, so a burst of map openings asks once. */
	private long refreshedAt;

	// Guarded by lock.
	private @Nullable List<Long> regions;
	private boolean regionsAsked;
	/** Asked for the regions again: every region's table is asked for again when they come. */
	private boolean refreshing;
	/**
	 * A refresh's tables, kept until the server says the round is done and then taken together. A
	 * tile spanning two regions drawn with one region's new table and the other's old one would be
	 * marked drawn as of now, and the other's change, saved a moment before, would never reach it.
	 */
	private @Nullable Map<Long, Table> round;
	private final Map<Long, Table> tables = new HashMap<>();
	private final Set<Long> tablesAsked = new HashSet<>();
	/** Chunks asked for or come, by {@link ChunkSnapshot#key}. */
	private final Map<Long, Asked> chunks = new LinkedHashMap<>(KEPT_CHUNKS, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, Asked> eldest) {
			return this.size() > KEPT_CHUNKS && eldest.getValue().answer.isDone();
		}
	};

	/** The fill's own: chunks read, as the fill reads the same ones for neighbouring tiles. */
	private final Map<Long, @Nullable SavedChunk> parsed = new LinkedHashMap<>(PARSED_CHUNKS, 0.75F, true) {
		@Override
		protected boolean removeEldestEntry(Map.Entry<Long, @Nullable SavedChunk> eldest) {
			return this.size() > PARSED_CHUNKS;
		}
	};
	/** What {@link #parsed} holds was read from saves of these times. */
	private final Map<Long, Integer> parsedTimes = new HashMap<>();

	/** One region's table, indexed {@code (z << 5) | x}. */
	private record Table(int[] times, short[] top, short[] floor, int newest) {
	}

	/** A chunk asked for, when, and its answer: the packed chunk and when it was saved, or null if it isn't shared. */
	private static final class Asked {
		final CompletableFuture<Answer> answer = new CompletableFuture<>();
		long askedAt;
	}

	/** {@code packed} is null when the server doesn't share the chunk, as of the save at {@code time}. */
	private record Answer(byte @Nullable [] packed, int time) {
	}

	private SharedChunks(ClientLevel level) {
		this.level = level;
		this.dimension = level.dimension().identifier().toString();
		this.tags = new ChunkTags(level, false);
	}

	/** The server's shared land in {@code level}, or null if the server doesn't share. Client thread. */
	public static @Nullable SharedChunks open(ClientLevel level) {
		ShareSettings settings = ShareClient.settings();
		if (settings == null || !settings.share() || Minecraft.getInstance().getSingleplayerServer() != null) {
			return null;
		}
		SharedChunks before = current;
		if (before != null) {
			before.close();
		}
		SharedChunks shared = new SharedChunks(level);
		current = shared;
		return shared;
	}

	/** For tests: the land the server shares in the level the player is in, or null. */
	public static @Nullable SharedChunks current() {
		return current;
	}

	/** Called whenever new land to draw comes. */
	public void onChange(Runnable changed) {
		this.changed = changed;
	}

	/** The player left the server or the level: what's waiting gives up. */
	static void closeCurrent() {
		SharedChunks shared = current;
		if (shared != null) {
			shared.close();
		}
	}

	@Override
	public Level level() {
		return this.level;
	}

	@Override
	public boolean isOpen() {
		Minecraft mc = Minecraft.getInstance();
		return !this.closed && current == this && mc.level == this.level && mc.getConnection() != null;
	}

	@Override
	public void close() {
		this.closed = true;
		if (current == this) {
			current = null;
		}
		synchronized (this.lock) {
			for (Asked asked : this.chunks.values()) {
				asked.answer.completeExceptionally(new Late("left the server"));
			}
			this.lock.notifyAll();
		}
	}

	@Override
	public Map<Long, int[]> scan() {
		long deadline = System.currentTimeMillis() + LIST_WAIT_MS;
		List<Long> toAsk;
		synchronized (this.lock) {
			if (this.regions == null && !this.regionsAsked) {
				this.regionsAsked = true;
				this.send(out -> {
					out.writeByte(ShareProtocol.REGIONS);
					out.writeUTF(this.dimension);
				});
			}
			while (this.regions == null && !this.closed && System.currentTimeMillis() < deadline) {
				this.waitLock(deadline);
			}
			if (this.regions == null) {
				return Map.of();
			}
			toAsk = new ArrayList<>();
			for (long region : this.regions) {
				if (!this.tables.containsKey(region) && this.tablesAsked.add(region)) {
					toAsk.add(region);
				}
			}
		}
		if (!toAsk.isEmpty()) {
			this.askTables(toAsk);
			long first = System.currentTimeMillis() + FIRST_TABLES_WAIT_MS;
			synchronized (this.lock) {
				while (this.tables.isEmpty() && !this.closed && System.currentTimeMillis() < first) {
					this.waitLock(first);
				}
			}
		}
		Map<Long, int[]> times = new HashMap<>();
		synchronized (this.lock) {
			for (Map.Entry<Long, Table> table : this.tables.entrySet()) {
				times.put(table.getKey(), table.getValue().times().clone());
			}
		}
		return times;
	}

	@Override
	public int now() {
		return (int) ((System.currentTimeMillis() + this.serverClock) / 1000);
	}

	/**
	 * Asks the server for the regions and every region's table again. Each chunk saved since this
	 * player's map drew it is drawn again; the rest isn't sent again. Client thread.
	 */
	@Override
	public void refresh() {
		long now = System.currentTimeMillis();
		synchronized (this.lock) {
			if (this.regions == null || this.refreshing || now - this.refreshedAt < REFRESH_MS) {
				return;
			}
			this.refreshedAt = now;
			this.refreshing = true;
		}
		if (Diorama.DEBUG) {
			Diorama.LOGGER.info("Shared map: asking what changed");
		}
		this.send(out -> {
			out.writeByte(ShareProtocol.REGIONS);
			out.writeUTF(this.dimension);
		});
	}

	/** Asks for regions' tables, the ones nearest the player first. */
	private void askTables(List<Long> regions) {
		Entity viewer = MapSession.viewer();
		double x = viewer == null ? 0 : viewer.getX();
		double z = viewer == null ? 0 : viewer.getZ();
		regions.sort(Comparator.comparingDouble(region -> {
			double dx = (region >> 32) * 512.0 + 256 - x;
			double dz = (int) (long) region * 512.0 + 256 - z;
			return dx * dx + dz * dz;
		}));
		for (int from = 0; from < regions.size(); from += TABLES_PER_ASK) {
			List<Long> part = regions.subList(from, Math.min(regions.size(), from + TABLES_PER_ASK));
			int[] newest = new int[part.size()];
			synchronized (this.lock) {
				for (int i = 0; i < part.size(); i++) {
					Table known = this.tables.get(part.get(i));
					newest[i] = known == null ? 0 : known.newest();
				}
			}
			this.send(out -> {
				out.writeByte(ShareProtocol.TABLES);
				out.writeUTF(this.dimension);
				out.writeShort(part.size());
				for (int i = 0; i < part.size(); i++) {
					out.writeInt((int) (part.get(i) >> 32));
					out.writeInt((int) (long) part.get(i));
					// The server answers only if something there was saved since.
					out.writeInt(newest[i]);
				}
			});
		}
	}

	private void waitLock(long deadline) {
		try {
			this.lock.wait(Math.max(1, Math.min(100, deadline - System.currentTimeMillis())));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			this.closed = true;
		}
	}

	@Override
	public ChunkTags.@Nullable Summary summary(int chunkX, int chunkZ) {
		synchronized (this.lock) {
			Table table = this.tables.get(SavedChunks.regionKey(chunkX >> 5, chunkZ >> 5));
			int i = ((chunkZ & 31) << 5) | (chunkX & 31);
			return table == null || table.times()[i] == 0 ? null : new ChunkTags.Summary(true, table.top()[i], table.floor()[i]);
		}
	}

	/**
	 * A bake reads this for every chunk its tile can show. A chunk in a region whose table hasn't
	 * come yet isn't known to be empty: the tile is drawn later, when it is, rather than saved
	 * without it.
	 */
	@Override
	public int top(int chunkX, int chunkZ) {
		this.knownOrLate(chunkX, chunkZ);
		ChunkTags.Summary summary = this.summary(chunkX, chunkZ);
		return summary == null ? Integer.MIN_VALUE : summary.maxY();
	}

	/** Throws {@link Late} if the chunk's region is in the server's list but its table hasn't come. */
	private void knownOrLate(int chunkX, int chunkZ) {
		long region = SavedChunks.regionKey(chunkX >> 5, chunkZ >> 5);
		synchronized (this.lock) {
			if (this.regions != null && !this.tables.containsKey(region) && this.regions.contains(region)) {
				throw new Late("region " + (chunkX >> 5) + ", " + (chunkZ >> 5) + " hasn't come yet");
			}
		}
	}

	/** When the server last saved a shared chunk, or 0 if it doesn't share it. */
	private int time(int chunkX, int chunkZ) {
		synchronized (this.lock) {
			Table table = this.tables.get(SavedChunks.regionKey(chunkX >> 5, chunkZ >> 5));
			return table == null ? 0 : table.times()[((chunkZ & 31) << 5) | (chunkX & 31)];
		}
	}

	@Override
	public boolean prefetches() {
		return true;
	}

	@Override
	public void prefetch(Collection<Long> wanted, boolean urgent) {
		List<Long> ask = new ArrayList<>();
		synchronized (this.lock) {
			long now = System.currentTimeMillis();
			for (long key : wanted) {
				if (this.time((int) (key >> 32), (int) key) != 0 && this.needsAsking(key, now)) {
					ask.add(key);
				}
			}
		}
		this.askChunks(ask, urgent);
	}

	/** Whether a chunk has to be asked for: never asked, an answer older than its table, or an ask gone unanswered. Under the lock. */
	private boolean needsAsking(long key, long now) {
		Asked asked = this.chunks.get(key);
		if (asked != null && asked.answer.isDone() && !asked.answer.isCompletedExceptionally()) {
			Answer answer = asked.answer.getNow(null);
			if (answer != null && answer.time() >= this.time((int) (key >> 32), (int) key)) {
				return false;
			}
		} else if (asked != null && !asked.answer.isDone() && now - asked.askedAt < ASK_AGAIN_MS) {
			return false;
		}
		Asked fresh = asked != null && !asked.answer.isDone() ? asked : new Asked();
		fresh.askedAt = now;
		this.chunks.put(key, fresh);
		return true;
	}

	private void askChunks(List<Long> keys, boolean urgent) {
		for (int from = 0; from < keys.size(); from += ShareProtocol.MAX_ASK) {
			List<Long> part = keys.subList(from, Math.min(keys.size(), from + ShareProtocol.MAX_ASK));
			this.send(out -> {
				out.writeByte(ShareProtocol.CHUNKS);
				out.writeUTF(this.dimension);
				out.writeBoolean(urgent);
				out.writeShort(part.size());
				for (long key : part) {
					out.writeInt((int) (key >> 32));
					out.writeInt((int) key);
				}
			});
		}
	}

	@Override
	public @Nullable SavedChunk read(int chunkX, int chunkZ) {
		long key = ChunkSnapshot.key(chunkX, chunkZ);
		this.knownOrLate(chunkX, chunkZ);
		int time = this.time(chunkX, chunkZ);
		if (time == 0) {
			return null;
		}
		if (this.parsed.containsKey(key) && this.parsedTimes.getOrDefault(key, 0) >= time) {
			return this.parsed.get(key);
		}
		Asked asked;
		synchronized (this.lock) {
			if (this.needsAsking(key, System.currentTimeMillis())) {
				this.askChunks(List.of(key), false);
			}
			asked = this.chunks.get(key);
		}
		Answer answer;
		try {
			answer = asked.answer.get(READ_WAIT_MS, TimeUnit.MILLISECONDS);
		} catch (TimeoutException e) {
			throw new Late("chunk " + chunkX + ", " + chunkZ + " didn't come in time");
		} catch (ExecutionException e) {
			throw new Late("chunk " + chunkX + ", " + chunkZ + ": " + e.getCause().getMessage());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new Late("interrupted");
		}
		SavedChunk chunk = answer.packed() == null ? null : this.unpack(chunkX, chunkZ, answer.packed());
		this.parsed.put(key, chunk);
		this.parsedTimes.put(key, answer.time());
		return chunk;
	}

	private @Nullable SavedChunk unpack(int chunkX, int chunkZ, byte[] packed) {
		try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(packed)))) {
			CompoundTag tag = NbtIo.read(in);
			return this.tags.read(chunkX, chunkZ, tag);
		} catch (IOException | RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't read shared chunk {}, {}", chunkX, chunkZ, e);
			return null;
		}
	}

	@Override
	public void forgetTops(long region) {
	}

	@Override
	public void clear() {
		this.parsed.clear();
		this.parsedTimes.clear();
	}

	@Override
	public boolean serverBehind() {
		return false;
	}

	/** A message from the server about shared land. Client thread. */
	static void receive(int kind, DataInputStream in) throws IOException {
		SharedChunks shared = current;
		String dimension = in.readUTF();
		if (shared == null || !shared.dimension.equals(dimension)) {
			return;
		}
		switch (kind) {
			case ShareProtocol.REGIONS -> shared.regions(in);
			case ShareProtocol.TABLES -> shared.table(in);
			case ShareProtocol.CHUNKS -> shared.chunk(in);
			case ShareProtocol.TABLES_DONE -> shared.roundDone();
			default -> {
			}
		}
	}

	private void regions(DataInputStream in) throws IOException {
		this.serverClock = in.readLong() - System.currentTimeMillis();
		int count = in.readInt();
		// Never more than the message holds, whatever it says.
		if (count < 0 || count > in.available() / 8) {
			throw new IOException("a list of " + count + " regions in " + in.available() + " bytes");
		}
		List<Long> regions = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			regions.add(SavedChunks.regionKey(in.readInt(), in.readInt()));
		}
		boolean again;
		synchronized (this.lock) {
			this.regions = regions;
			again = this.refreshing;
			this.refreshing = false;
			if (again) {
				this.tablesAsked.addAll(regions);
				this.round = new HashMap<>();
			}
			this.lock.notifyAll();
		}
		if (again) {
			if (Diorama.DEBUG) {
				Diorama.LOGGER.info("Shared map: {} regions listed; asking for their tables", regions.size());
			}
			this.askTables(new ArrayList<>(regions));
		}
	}

	private void table(DataInputStream in) throws IOException {
		int regionX = in.readInt();
		int regionZ = in.readInt();
		int newest = in.readInt();
		int[] times = new int[1024];
		short[] top = new short[1024];
		short[] floor = new short[1024];
		DataInputStream packed = new DataInputStream(new InflaterInputStream(in));
		for (int i = 0; i < 1024; i++) {
			times[i] = packed.readInt();
			if (times[i] != 0) {
				top[i] = packed.readShort();
				floor[i] = packed.readShort();
			}
		}
		Table table = new Table(times, top, floor, newest);
		long key = SavedChunks.regionKey(regionX, regionZ);
		synchronized (this.lock) {
			if (this.round != null && this.tables.containsKey(key)) {
				this.round.put(key, table);
				if (Diorama.DEBUG) {
					Diorama.LOGGER.info("Shared map: region {}, {} changed, newest save {}", regionX, regionZ, newest);
				}
				return;
			}
			this.tables.put(key, table);
			this.lock.notifyAll();
		}
		this.changed();
	}

	/** The server sent every table of the round: a refresh's tables go in together. */
	private void roundDone() {
		synchronized (this.lock) {
			if (this.round == null) {
				return;
			}
			if (Diorama.DEBUG) {
				Diorama.LOGGER.info("Shared map: {} changed regions taken in", this.round.size());
			}
			this.tables.putAll(this.round);
			this.round = null;
			this.lock.notifyAll();
		}
		this.changed();
	}

	private void chunk(DataInputStream in) throws IOException {
		int chunkX = in.readInt();
		int chunkZ = in.readInt();
		int time = in.readInt();
		int length = in.readInt();
		if (length < 0 || length > ShareProtocol.MAX_CHUNK_BYTES || length > in.available()) {
			throw new IOException("a chunk of " + length + " bytes in " + in.available());
		}
		byte[] packed = new byte[length];
		in.readFully(packed);
		synchronized (this.lock) {
			long key = ChunkSnapshot.key(chunkX, chunkZ);
			Asked asked = this.chunks.get(key);
			if (asked == null || asked.answer.isDone()) {
				asked = new Asked();
				this.chunks.put(key, asked);
			}
			asked.answer.complete(new Answer(length == 0 ? null : packed, time));
		}
	}

	private void changed() {
		Runnable changed = this.changed;
		if (changed != null) {
			changed.run();
		}
	}

	private interface Writer {
		void write(DataOutputStream out) throws IOException;
	}

	/** Sends a message to the server, from the client thread. */
	private void send(Writer writer) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			writer.write(out);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		byte[] data = bytes.toByteArray();
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (!this.closed && current == this && mc.getConnection() != null) {
				ShareProtocol.sendToServer(data);
			}
		});
	}
}

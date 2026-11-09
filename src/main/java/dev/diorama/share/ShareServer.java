package dev.diorama.share;

import dev.diorama.Diorama;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.DeflaterOutputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Diorama's half on a dedicated server: it tells each player with Diorama what the server shares
 * of its map, and answers their Diorama's questions about it from the world's save. It never draws
 * anything and stores nothing; the world save is the data. Loads on a dedicated server, so nothing
 * here may name a client class. The loader's common entrypoint calls in from the server's events.
 *
 * <p>Players ask for regions and chunks ({@link ShareProtocol}). The answers come from one worker
 * thread of the share's own, in turns between players, each player at most at the rate the settings
 * allow: reading the save, packing the chunk ({@link ChunkPacker}) and sending it. A chunk loaded
 * right now is read from memory, as it may be newer than its save. Only chunks the share rule allows
 * go out, only of the level the player is in, and only the view from the sky.
 *
 * <p>Only dedicated servers share. In singleplayer the client reads the save itself, and a LAN
 * world's host has no settings file for it.
 */
public final class ShareServer {
	/** The most chunks a player can have waiting; older asks past this are dropped, and asked again. */
	private static final int MAX_WAITING = 16384;
	/** How long a read waits for the server's storage or its thread. */
	private static final long READ_SECONDS = 30;
	private static volatile @Nullable ShareServer running;

	private final MinecraftServer server;
	private final ShareSettings settings;
	private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
	/** The share worker's own from here on. */
	private final Map<ServerLevel, RegionTables> tables = new HashMap<>();
	private final Map<ServerLevel, ChunkPacker> packers = new HashMap<>();
	private final Thread worker;
	private final Object wake = new Object();
	private volatile boolean stopped;

	private ShareServer(MinecraftServer server, ShareSettings settings) {
		this.server = server;
		this.settings = settings;
		this.worker = new Thread(this::work, "diorama-share");
		this.worker.setDaemon(true);
		this.worker.setPriority(Thread.NORM_PRIORITY - 1);
	}

	/** The server started; {@code configFolder} is the loader's config folder. */
	public static void started(MinecraftServer server, Path configFolder) {
		if (!server.isDedicatedServer()) {
			return;
		}
		ShareSettings settings = ShareSettings.load(configFolder);
		ShareServer share = new ShareServer(server, settings);
		running = share;
		if (settings.share()) {
			share.worker.start();
		}
		Diorama.LOGGER.info("Diorama on the server: {} ({} land, up to {} KB/s a player)",
			settings.share() ? "sharing the map with players who have Diorama" : "not sharing the map",
			settings.land().key(), settings.kilobytesPerSecond());
	}

	public static void stopped(MinecraftServer server) {
		ShareServer share = running;
		if (share != null && share.server == server) {
			running = null;
			share.stopped = true;
			share.worker.interrupt();
		}
	}

	/**
	 * A player whose client has Diorama's channel joined, or said it has the channel. Server thread.
	 * Tells them the settings, which also tells their Diorama this server has Diorama.
	 */
	public static void listening(ServerPlayer player) {
		ShareServer share = running;
		if (share == null || player.level().getServer() != share.server) {
			return;
		}
		ShareProtocol.send(player, share.settings.encode());
	}

	/** A message from a player's Diorama. Server thread. */
	public static void receive(ServerPlayer player, byte[] data) {
		ShareServer share = running;
		if (share == null || !share.settings.share() || data.length == 0 || player.level().getServer() != share.server) {
			return;
		}
		try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
			int kind = in.readUnsignedByte();
			String dimension = in.readUTF();
			Session session = share.sessions.computeIfAbsent(player.getUUID(), id -> new Session(player, share.settings.kilobytesPerSecond()));
			session.player = player;
			switch (kind) {
				case ShareProtocol.REGIONS -> session.ask(new Ask.Regions(dimension));
				case ShareProtocol.TABLES -> {
					int count = in.readUnsignedShort();
					List<Long> regions = new ArrayList<>(count);
					for (int i = 0; i < count; i++) {
						regions.add(RegionTables.key(in.readInt(), in.readInt()));
					}
					session.ask(new Ask.Tables(dimension, regions));
				}
				case ShareProtocol.CHUNKS -> {
					boolean urgent = in.readBoolean();
					int count = in.readUnsignedShort();
					long[] chunks = new long[count];
					for (int i = 0; i < count; i++) {
						chunks[i] = ShareProtocol.chunkKey(in.readInt(), in.readInt());
					}
					session.chunks(dimension, chunks, urgent);
				}
				default -> {
					return;
				}
			}
		} catch (IOException e) {
			Diorama.LOGGER.debug("A malformed share message from {}", player.getName().getString());
			return;
		}
		synchronized (share.wake) {
			share.wake.notifyAll();
		}
	}

	/** What a player asked for, waiting for the worker. */
	private sealed interface Ask {
		record Regions(String dimension) implements Ask {
		}

		record Tables(String dimension, List<Long> regions) implements Ask {
		}
	}

	/** One player's asks and allowance. Fields are guarded by the session. */
	private static final class Session {
		volatile ServerPlayer player;
		private final Deque<Ask> asks = new ArrayDeque<>();
		/** Chunks asked for, in the order they go out, for {@link #dimension}. */
		private final LinkedHashSet<Long> chunks = new LinkedHashSet<>();
		private String dimension = "";
		private final double bytesPerSecond;
		private double allowance;
		private long refilledAt = System.nanoTime();
		// Totals for the log, the worker's own.
		long sentChunks;
		long sentBytes;
		long tableBytes;
		long refused;
		long workNanos;
		long startedAt = System.nanoTime();

		Session(ServerPlayer player, int kilobytesPerSecond) {
			this.player = player;
			this.bytesPerSecond = kilobytesPerSecond * 1024.0;
			this.allowance = this.bytesPerSecond;
		}

		synchronized void ask(Ask ask) {
			this.asks.add(ask);
		}

		synchronized @Nullable Ask nextAsk() {
			return this.asks.poll();
		}

		/** Urgent ones go first, in their order; the rest after what's waiting. */
		synchronized void chunks(String dimension, long[] asked, boolean urgent) {
			if (!dimension.equals(this.dimension)) {
				this.chunks.clear();
				this.dimension = dimension;
			}
			if (urgent) {
				LinkedHashSet<Long> rest = new LinkedHashSet<>(this.chunks);
				this.chunks.clear();
				for (long chunk : asked) {
					this.chunks.add(chunk);
				}
				this.chunks.addAll(rest);
			} else {
				for (long chunk : asked) {
					this.chunks.add(chunk);
				}
			}
			while (this.chunks.size() > MAX_WAITING) {
				// The oldest asks that aren't urgent: the end of the queue.
				Long last = null;
				for (Long chunk : this.chunks) {
					last = chunk;
				}
				this.chunks.remove(last);
			}
		}

		/** The next chunk to send, if the allowance has room. */
		synchronized @Nullable Long nextChunk() {
			long now = System.nanoTime();
			this.allowance = Math.min(this.bytesPerSecond, this.allowance + (now - this.refilledAt) / 1e9 * this.bytesPerSecond);
			this.refilledAt = now;
			if (this.allowance <= 0 || this.chunks.isEmpty()) {
				return null;
			}
			var it = this.chunks.iterator();
			Long chunk = it.next();
			it.remove();
			return chunk;
		}

		synchronized String dimension() {
			return this.dimension;
		}

		synchronized void spent(int bytes) {
			this.allowance -= bytes;
		}

		synchronized boolean waiting() {
			return !this.asks.isEmpty() || !this.chunks.isEmpty() && this.allowance > 0;
		}
	}

	private void work() {
		while (!this.stopped) {
			boolean busy = false;
			try {
				for (Map.Entry<UUID, Session> entry : this.sessions.entrySet()) {
					Session session = entry.getValue();
					if (session.player.hasDisconnected()) {
						this.sessions.remove(entry.getKey());
						this.logSession(session);
						continue;
					}
					Ask ask = session.nextAsk();
					if (ask != null) {
						this.answer(session, ask);
						busy = true;
						continue;
					}
					Long chunk = session.nextChunk();
					if (chunk != null) {
						this.serve(session, chunk);
						busy = true;
					}
				}
				if (!busy) {
					synchronized (this.wake) {
						// The allowance refills meanwhile.
						this.wake.wait(50);
					}
				}
			} catch (InterruptedException e) {
				return;
			} catch (RuntimeException e) {
				if (!this.stopped) {
					Diorama.LOGGER.error("Sharing the map failed", e);
				}
			}
		}
	}

	private void logSession(Session session) {
		if (session.sentChunks + session.refused == 0) {
			return;
		}
		double seconds = (System.nanoTime() - session.startedAt) / 1e9;
		Diorama.LOGGER.info("Shared map with {}: {} chunks, {} KB ({} bytes a chunk) and {} KB of region tables, {} not shared, {} ms of the share thread a chunk, over {} s",
			session.player.getName().getString(), session.sentChunks, session.sentBytes / 1024,
			session.sentChunks == 0 ? 0 : session.sentBytes / session.sentChunks, session.tableBytes / 1024, session.refused,
			session.sentChunks == 0 ? 0 : String.format(java.util.Locale.ROOT, "%.2f", session.workNanos / 1e6 / session.sentChunks), Math.round(seconds));
	}

	/** The level a player asked about, if it's the one they're in. */
	private @Nullable ServerLevel levelOf(Session session, String dimension) {
		ServerLevel level = (ServerLevel) session.player.level();
		return level.dimension().identifier().toString().equals(dimension) ? level : null;
	}

	private RegionTables tables(ServerLevel level) {
		return this.tables.computeIfAbsent(level, RegionTables::new);
	}

	private void answer(Session session, Ask ask) {
		if (ask instanceof Ask.Regions regions) {
			this.regions(session, regions.dimension());
		} else if (ask instanceof Ask.Tables asked) {
			this.tables(session, asked.dimension(), asked.regions());
		}
	}

	/** Lists the regions the level's save has. */
	private void regions(Session session, String dimension) {
		ServerLevel level = this.levelOf(session, dimension);
		if (level == null) {
			return;
		}
		List<Long> found = this.tables(level).regions();
		this.send(session, out -> {
			out.writeByte(ShareProtocol.REGIONS);
			out.writeUTF(dimension);
			out.writeInt(found.size());
			for (long region : found) {
				out.writeInt((int) (region >> 32));
				out.writeInt((int) region);
			}
		});
	}

	/** Sends each region's table: for each chunk the share rule allows, when it was saved, how high it reaches and how deep it shows. */
	private void tables(Session session, String dimension, List<Long> regions) {
		ServerLevel level = this.levelOf(session, dimension);
		if (level == null) {
			return;
		}
		boolean generated = this.settings.land() == ShareSettings.Land.GENERATED;
		for (long region : regions) {
			if (this.stopped || session.player.hasDisconnected()) {
				return;
			}
			int regionX = (int) (region >> 32);
			int regionZ = (int) region;
			RegionTables.Table table = this.tables(level).table(regionX, regionZ, generated);
			int sent = this.send(session, out -> {
				out.writeByte(ShareProtocol.TABLES);
				out.writeUTF(dimension);
				out.writeInt(regionX);
				out.writeInt(regionZ);
				DeflaterOutputStream deflated = new DeflaterOutputStream(out);
				DataOutputStream packed = new DataOutputStream(deflated);
				for (int i = 0; i < 1024; i++) {
					packed.writeInt(table.shared[i]);
					if (table.shared[i] != 0) {
						packed.writeShort(table.top[i]);
						packed.writeShort(table.floor[i]);
					}
				}
				packed.flush();
				deflated.finish();
			});
			session.tableBytes += sent;
			session.spent(sent);
		}
	}

	/** Sends one chunk, or says it's not shared. */
	private void serve(Session session, long chunk) {
		long start = System.nanoTime();
		String dimension = session.dimension();
		ServerLevel level = this.levelOf(session, dimension);
		if (level == null) {
			return;
		}
		int chunkX = (int) (chunk >> 32);
		int chunkZ = (int) (long) chunk;
		boolean generated = this.settings.land() == ShareSettings.Land.GENERATED;
		RegionTables tables = this.tables(level);
		RegionTables.Table table = tables.table(chunkX >> 5, chunkZ >> 5, generated);
		int index = ((chunkZ & 31) << 5) | (chunkX & 31);
		byte[] packed = null;
		int time = table.shared[index];
		if (time != 0) {
			CompoundTag tag = this.tag(level, tables, chunkX, chunkZ);
			if (tag != null && RegionTables.allowed(tag, generated)) {
				packed = this.packer(level, tables).pack(chunkX, chunkZ, tag);
			}
			if (packed != null && packed.length > ShareProtocol.MAX_CHUNK_BYTES) {
				packed = null;
			}
		}
		byte[] body = packed;
		int sent = this.send(session, out -> {
			out.writeByte(ShareProtocol.CHUNKS);
			out.writeUTF(dimension);
			out.writeInt(chunkX);
			out.writeInt(chunkZ);
			out.writeInt(body == null ? 0 : time);
			out.writeInt(body == null ? 0 : body.length);
			if (body != null) {
				out.write(body);
			}
		});
		session.spent(sent);
		if (body == null) {
			session.refused++;
		} else {
			session.sentChunks++;
			session.sentBytes += sent;
			session.workNanos += System.nanoTime() - start;
		}
	}

	private ChunkPacker packer(ServerLevel level, RegionTables tables) {
		return this.packers.computeIfAbsent(level, l -> new ChunkPacker(l, tables.tags(), (x, z) -> this.tag(l, tables, x, z)));
	}

	/**
	 * A chunk's tag, brought up to this version: from memory if it's loaded, as it may be newer than
	 * its save, else as saved. Null if there's neither.
	 */
	private @Nullable CompoundTag tag(ServerLevel level, RegionTables tables, int chunkX, int chunkZ) {
		try {
			CompletableFuture<@Nullable Object> loaded = this.server.submit(() -> {
				LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
				return chunk == null ? null : copy(level, chunk);
			});
			Object copy = loaded.get(READ_SECONDS, TimeUnit.SECONDS);
			if (copy != null) {
				return written(copy);
			}
			return tables.read(new ChunkPos(chunkX, chunkZ));
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} catch (Exception e) {
			if (!this.stopped) {
				Diorama.LOGGER.warn("Couldn't read chunk {}, {} to share it", chunkX, chunkZ, e);
			}
			return null;
		}
	}

	/** What saving a loaded chunk would write, taken on the server thread. */
	private static Object copy(ServerLevel level, LevelChunk chunk) {
		//? if >=1.21.2 {
		return net.minecraft.world.level.chunk.storage.SerializableChunkData.copyOf(level, chunk);
		//?} else {
		/*return net.minecraft.world.level.chunk.storage.ChunkSerializer.write(level, chunk);
		*///?}
	}

	/** The tag of a {@link #copy}, off the server thread. */
	private static CompoundTag written(Object copy) {
		//? if >=1.21.2 {
		return ((net.minecraft.world.level.chunk.storage.SerializableChunkData) copy).write();
		//?} else {
		/*return (CompoundTag) copy;
		*///?}
	}

	private interface Writer {
		void write(DataOutputStream out) throws IOException;
	}

	/** Sends a message and returns its size. */
	private int send(Session session, Writer writer) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream out = new DataOutputStream(bytes)) {
			writer.write(out);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		byte[] data = bytes.toByteArray();
		ShareProtocol.send(session.player, data);
		return data.length;
	}
}

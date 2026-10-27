package dev.diorama;

import dev.diorama.compat.FtbChunks;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Land claims from FTB Chunks, for the maps to draw. {@link dev.diorama.compat.FtbChunks} hands
 * over each chunk's team as FTB Chunks applies the server's updates, in every dimension, from the
 * full list sent on joining onwards. Once a tick, changes in the player's dimension make a new
 * {@link Snapshot}, which any thread can read. Everything else here is client thread only.
 */
public final class Claims {
	/** Who owns a chunk: a team, with its colour (RGB) and name, and whether the chunk is force loaded. */
	public record Claim(UUID team, int rgb, String name, boolean forceLoaded) {
		public Claim asForceLoaded() {
			return new Claim(this.team, this.rgb, this.name, true);
		}

		Claim as(Claim team) {
			return this.forceLoaded ? team.asForceLoaded() : team;
		}
	}

	/** One of FTB Chunks' regions, 32 by 32 chunks. */
	private record Region(int x, int z) {
	}

	/** Chunks in a region, kept by {@link #index}. */
	public static final int REGION_CHUNKS = 32 * 32;
	/** Teams' colours and names are looked up again this often, in ticks, as changing them sends no claim update. */
	private static final int TEAM_CHECK_TICKS = 40;

	private static final Snapshot NONE = new Snapshot(null, Map.of());
	private static volatile Snapshot current = NONE;
	/** Every claim known, by dimension, then region; each region's chunks by {@link #index}. Never handed out. */
	private static final Map<ResourceKey<Level>, Map<Region, Claim[]>> known = new HashMap<>();
	/** Each team's colour and name as last read. */
	private static final Map<UUID, Claim> teams = new HashMap<>();
	/** Regions changed since the last snapshot, by dimension. */
	private static final Map<ResourceKey<Level>, Set<Region>> changed = new HashMap<>();
	private static int ticks;
	/** Time spent on claims this tick, and the most in any tick since {@link #resetSlowestTick}. For tests. */
	private static long tickNanos;
	private static int tickUpdates;
	private static long slowestTick;
	/** For tests: what the slowest tick was made of. */
	private static String slowestTickParts = "";

	private Claims() {
	}

	/** The claims in the player's dimension as they stand, for any thread; none while they aren't shown. */
	public static Snapshot get() {
		return current;
	}

	/** Whether there's anything to read claims from, so settings about them are worth showing. */
	public static boolean supported() {
		return FtbChunks.available();
	}

	/** Where a chunk's claim is kept in its region's array. */
	public static int index(int chunkX, int chunkZ) {
		return (chunkZ & 31) << 5 | chunkX & 31;
	}

	/** No one's: the team the server sends for an unclaimed chunk, or one the player may not see. */
	private static final UUID NO_TEAM = new UUID(0, 0);

	/**
	 * A chunk's team as FTB Chunks has it now, and whether it's force loaded. Like FTB Chunks, a
	 * team FTB Teams doesn't know owns nothing.
	 */
	public static void updated(ResourceKey<Level> dimension, int chunkX, int chunkZ, UUID team, boolean forceLoaded) {
		Claim plain = team == null || team.equals(NO_TEAM) ? null : teams.get(team);
		if (plain == null && team != null && !team.equals(NO_TEAM)) {
			Optional<Claim> found = FtbChunks.team(team);
			if (found != null && found.isPresent()) {
				plain = found.get();
				teams.put(team, plain);
			}
		}
		Claim claim = plain == null ? null : forceLoaded ? plain.asForceLoaded() : plain;
		Region region = new Region(chunkX >> 5, chunkZ >> 5);
		Map<Region, Claim[]> regions = known.computeIfAbsent(dimension, d -> new HashMap<>());
		Claim[] chunks = regions.get(region);
		if (chunks == null) {
			if (claim == null) {
				return;
			}
			chunks = new Claim[REGION_CHUNKS];
			regions.put(region, chunks);
		}
		int i = index(chunkX, chunkZ);
		if (!Objects.equals(chunks[i], claim)) {
			chunks[i] = claim;
			changed.computeIfAbsent(dimension, d -> new HashSet<>()).add(region);
		}
	}

	/** Time spent taking in an update, counted toward this tick's. */
	public static void spent(long nanos) {
		tickNanos += nanos;
		tickUpdates++;
	}

	/** Called every client tick: makes a new snapshot when the claims in the player's dimension changed. */
	public static void tick(Minecraft mc) {
		long start = System.nanoTime();
		if (++ticks % TEAM_CHECK_TICKS == 0) {
			checkTeams();
		}
		boolean shown = mc.level != null && Settings.get().claims != Settings.ClaimsShown.OFF && FtbChunks.available();
		ResourceKey<Level> dimension = shown ? mc.level.dimension() : null;
		Snapshot snapshot = current;
		if (dimension == null) {
			if (snapshot != NONE) {
				current = NONE;
			}
		} else if (!dimension.equals(snapshot.dimension)) {
			current = new Snapshot(dimension, copyAll(dimension));
		} else if (changed.containsKey(dimension)) {
			Map<Region, Claim[]> regions = new HashMap<>(snapshot.regions);
			Map<Region, Claim[]> all = known.getOrDefault(dimension, Map.of());
			for (Region region : changed.get(dimension)) {
				Claim[] chunks = all.get(region);
				if (chunks == null || isEmpty(chunks)) {
					regions.remove(region);
				} else {
					regions.put(region, chunks.clone());
				}
			}
			current = new Snapshot(dimension, Map.copyOf(regions));
		}
		// Regions left with no claims aren't kept, in any dimension.
		for (Map.Entry<ResourceKey<Level>, Set<Region>> entry : changed.entrySet()) {
			Map<Region, Claim[]> all = known.get(entry.getKey());
			if (all != null) {
				for (Region region : entry.getValue()) {
					Claim[] chunks = all.get(region);
					if (chunks != null && isEmpty(chunks)) {
						all.remove(region);
					}
				}
			}
		}
		changed.clear();
		long own = System.nanoTime() - start;
		if (tickNanos + own > slowestTick) {
			slowestTick = tickNanos + own;
			slowestTickParts = tickUpdates + " updates in " + tickNanos / 1000 + " us, snapshot in " + own / 1000 + " us";
		}
		tickNanos = 0;
		tickUpdates = 0;
	}

	/**
	 * Teams whose colour or name changed get their claims redrawn, and teams FTB Teams says no
	 * longer exist lose theirs: deleting a server team sends no update for its chunks.
	 */
	private static void checkTeams() {
		Map<UUID, Claim> renamed = new HashMap<>();
		for (Map.Entry<UUID, Claim> entry : teams.entrySet()) {
			Optional<Claim> now = FtbChunks.team(entry.getKey());
			if (now != null && !now.equals(Optional.of(entry.getValue()))) {
				renamed.put(entry.getKey(), now.orElse(null));
			}
		}
		if (renamed.isEmpty()) {
			return;
		}
		for (Map.Entry<UUID, Claim> entry : renamed.entrySet()) {
			if (entry.getValue() == null) {
				teams.remove(entry.getKey());
			} else {
				teams.put(entry.getKey(), entry.getValue());
			}
		}
		for (Map.Entry<ResourceKey<Level>, Map<Region, Claim[]>> dimension : known.entrySet()) {
			for (Map.Entry<Region, Claim[]> region : dimension.getValue().entrySet()) {
				Claim[] chunks = region.getValue();
				for (int i = 0; i < chunks.length; i++) {
					if (chunks[i] != null && renamed.containsKey(chunks[i].team())) {
						Claim team = renamed.get(chunks[i].team());
						chunks[i] = team == null ? null : chunks[i].as(team);
						changed.computeIfAbsent(dimension.getKey(), d -> new HashSet<>()).add(region.getKey());
					}
				}
			}
		}
	}

	private static Map<Region, Claim[]> copyAll(ResourceKey<Level> dimension) {
		Map<Region, Claim[]> copy = new HashMap<>();
		for (Map.Entry<Region, Claim[]> region : known.getOrDefault(dimension, Map.of()).entrySet()) {
			if (!isEmpty(region.getValue())) {
				copy.put(region.getKey(), region.getValue().clone());
			}
		}
		return Map.copyOf(copy);
	}

	private static boolean isEmpty(Claim[] chunks) {
		for (Claim claim : chunks) {
			if (claim != null) {
				return false;
			}
		}
		return true;
	}

	/** Forgets every claim, on leaving a world or moving to another server: the next one sends its own. */
	public static void clear() {
		known.clear();
		teams.clear();
		changed.clear();
		current = NONE;
	}

	/** For tests: the most time spent on claims in one tick since the last call, in nanoseconds, and starts again. */
	public static long resetSlowestTick() {
		long slowest = slowestTick;
		slowestTick = 0;
		return slowest;
	}

	/** For tests: what the slowest tick since the last reset was made of. */
	public static String slowestTickParts() {
		return slowestTickParts;
	}

	/** The claims in one dimension at one moment. Never modified. */
	public static final class Snapshot {
		private final @Nullable ResourceKey<Level> dimension;
		/** By FTB Chunks' region, each region's chunks by {@link #index}; null for none. */
		private final Map<Region, Claim[]> regions;

		Snapshot(@Nullable ResourceKey<Level> dimension, Map<Region, Claim[]> regions) {
			this.dimension = dimension;
			this.regions = regions;
		}

		public boolean isEmpty() {
			return this.regions.isEmpty();
		}

		/** Who owns a chunk, or null if no one the player can see does. */
		public @Nullable Claim at(int chunkX, int chunkZ) {
			Claim[] region = this.regions.get(new Region(chunkX >> 5, chunkZ >> 5));
			return region == null ? null : region[index(chunkX, chunkZ)];
		}

		/** Whether a claim may lie in this box of chunks (inclusive); answered by region, so it can say yes when there's none. */
		public boolean mayHaveAny(int chunkX0, int chunkZ0, int chunkX1, int chunkZ1) {
			if (this.regions.isEmpty()) {
				return false;
			}
			int rx0 = chunkX0 >> 5;
			int rz0 = chunkZ0 >> 5;
			int rx1 = chunkX1 >> 5;
			int rz1 = chunkZ1 >> 5;
			if ((long) (rx1 - rx0 + 1) * (rz1 - rz0 + 1) > this.regions.size()) {
				for (Region region : this.regions.keySet()) {
					if (region.x() >= rx0 && region.x() <= rx1 && region.z() >= rz0 && region.z() <= rz1) {
						return true;
					}
				}
				return false;
			}
			for (int rx = rx0; rx <= rx1; rx++) {
				for (int rz = rz0; rz <= rz1; rz++) {
					if (this.regions.containsKey(new Region(rx, rz))) {
						return true;
					}
				}
			}
			return false;
		}

		/** Whether the claims in this box of chunks differ between two snapshots. */
		public boolean differsIn(Snapshot other, int chunkX0, int chunkZ0, int chunkX1, int chunkZ1) {
			if (other == this) {
				return false;
			}
			if (!Objects.equals(other.dimension, this.dimension)) {
				return true;
			}
			for (int rx = chunkX0 >> 5; rx <= chunkX1 >> 5; rx++) {
				for (int rz = chunkZ0 >> 5; rz <= chunkZ1 >> 5; rz++) {
					Region region = new Region(rx, rz);
					Claim[] mine = this.regions.get(region);
					Claim[] theirs = other.regions.get(region);
					if (mine != theirs && !Arrays.equals(mine, theirs)) {
						return true;
					}
				}
			}
			return false;
		}
	}
}

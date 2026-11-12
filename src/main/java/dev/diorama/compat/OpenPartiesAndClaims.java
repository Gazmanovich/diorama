package dev.diorama.compat;

import dev.diorama.Claims;
import dev.diorama.Diorama;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?} else {
/*import net.neoforged.fml.ModList;
*///?}
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;

/**
 * Reads the land claims Open Parties and Claims tells the client about, through its client API,
 * as Xaero's World Map does. Open Parties and Claims keeps every claim the server sent, in every
 * dimension, and says which chunks, regions or dimensions changed; this reads those again from it
 * on the next client tick and hands each chunk's claim to {@link Claims}. After joining, or once
 * Diorama forgot its claims, it reads them all.
 *
 * <p>A claim's colour and title ("Base - Alex's Claim") come from Open Parties and Claims, in the
 * player's language. Older versions, the newest on 1.21.3, 1.21.6 and 1.21.9 among them, have no
 * call for those, so they're put together here the way those word them when the player enters a claim.
 *
 * <p>Diorama doesn't build against Open Parties and Claims. The names below are its public API,
 * the same from 1.20.1 to 26.3. If one isn't where this class expects it, it logs once and stays off.
 */
public final class OpenPartiesAndClaims {
	private static final String API = "xaero.pac.";
	/** Who owns server claims and claims left by players gone too long, in Open Parties and Claims. */
	private static final UUID SERVER_CLAIM = new UUID(0, 0);
	private static final UUID EXPIRED_CLAIM = new UUID(0, 1);
	private static final int REGION_SIZE = 32;

	private static boolean started;
	private static boolean failed;
	/** The client's claims manager, {@code IClientClaimsManagerAPI}. */
	private static Object manager;
	private static Method claimAt;
	private static Method dimensionOf;
	private static Method dimensionStream;
	private static Method dimensionId;
	private static Method regionOf;
	private static Method regionStream;
	private static Method regionX;
	private static Method regionZ;
	private static Method regionClaim;
	private static Method claimPlayer;
	private static Method claimSub;
	private static Method claimForceloadable;
	private static @Nullable Method fullName;
	private static @Nullable Method colour;
	private static @Nullable Method moderatorMode;
	// For older versions: the parts of a title and colour.
	private static Method playerInfo;
	private static Method username;
	private static Method claimsName;
	private static Method subClaimsName;
	private static Method claimsColour;
	private static Method subClaimsColour;

	/** Read everything again on the next tick: on starting, and after {@link Claims} forgot its claims. */
	private static boolean readAll = true;
	/** Dimensions, regions and chunks Open Parties and Claims said changed, read again on the next tick. */
	private static final Set<Object> dimensionsChanged = new LinkedHashSet<>();
	private static final Set<Area> regionsChanged = new LinkedHashSet<>();
	private static final List<Area> chunksChanged = new ArrayList<>();

	/** A region or chunk, by its position in a dimension's id. */
	private record Area(Object dimension, int x, int z) {
	}

	private OpenPartiesAndClaims() {
	}

	public static boolean installed() {
		//? if fabric {
		return FabricLoader.getInstance().isModLoaded("openpartiesandclaims");
		//?} else {
		/*return ModList.get().isLoaded("openpartiesandclaims");
		*///?}
	}

	/** Whether claims can be read: Open Parties and Claims is installed and everything was found. */
	public static boolean available() {
		if (!started) {
			started = true;
			if (installed()) {
				try {
					lookUp();
					Diorama.LOGGER.info("Open Parties and Claims is installed: showing its claims on the map");
				} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
					fail(e);
				}
			} else {
				failed = true;
			}
		}
		return !failed;
	}

	private static void lookUp() throws ReflectiveOperationException {
		Class<?> clientApi = Class.forName(API + "client.api.OpenPACClientAPI");
		Object api = clientApi.getMethod("get").invoke(null);
		manager = Objects.requireNonNull(clientApi.getMethod("getClaimsManager").invoke(api), "claims manager");
		Class<?> claims = Class.forName(API + "client.claims.api.IClientClaimsManagerAPI");
		Class<?> claim = Class.forName(API + "common.claims.player.api.IPlayerChunkClaimAPI");
		claimAt = claims.getMethod("get", Identifier.class, int.class, int.class);
		dimensionOf = claims.getMethod("getDimension", Identifier.class);
		dimensionStream = claims.getMethod("getDimensionStream");
		Class<?> dimension = Class.forName(API + "client.claims.api.IClientDimensionClaimsManagerAPI");
		dimensionId = dimension.getMethod("getDimension");
		regionOf = dimension.getMethod("getRegion", int.class, int.class);
		regionStream = dimension.getMethod("getRegionStream");
		Class<?> region = Class.forName(API + "client.claims.api.IClientRegionClaimsAPI");
		regionX = region.getMethod("getX");
		regionZ = region.getMethod("getZ");
		regionClaim = region.getMethod("get", int.class, int.class);
		claimPlayer = claim.getMethod("getPlayerId");
		claimSub = claim.getMethod("getSubConfigIndex");
		claimForceloadable = claim.getMethod("isForceloadable");
		try {
			fullName = claims.getMethod("getFullName", claim, Identifier.class, boolean.class);
			colour = claims.getMethod("getColor", claim, Identifier.class);
			moderatorMode = claims.getMethod("isModeratorMode");
		} catch (NoSuchMethodException e) {
			// Older versions.
			fullName = null;
			colour = null;
			moderatorMode = null;
		}
		playerInfo = claims.getMethod("getPlayerInfo", UUID.class);
		Class<?> info = Class.forName(API + "client.claims.player.api.IClientPlayerClaimInfoAPI");
		username = info.getMethod("getPlayerUsername");
		claimsName = info.getMethod("getClaimsName");
		subClaimsName = info.getMethod("getClaimsName", int.class);
		claimsColour = info.getMethod("getClaimsColor");
		subClaimsColour = info.getMethod("getClaimsColor", int.class);
		// Told of every change from now on.
		Class<?> listener = Class.forName(API + "common.claims.tracker.api.IClaimsManagerListenerAPI");
		Object tracker = claims.getMethod("getTracker").invoke(manager);
		Object proxy = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[] {listener}, new Listener());
		Class.forName(API + "common.claims.tracker.api.IClaimsManagerTrackerAPI").getMethod("register", listener).invoke(tracker, proxy);
	}

	/**
	 * Takes note of what Open Parties and Claims says changed, to read on the next tick: it says so
	 * in the middle of changing it, and reading it then could get in its way.
	 */
	private static final class Listener implements InvocationHandler {
		@Override
		public Object invoke(Object proxy, Method method, Object[] args) {
			switch (method.getName()) {
				case "onChunkChange", "onWholeRegionChange", "onDimensionChange" -> {
					Minecraft mc = Minecraft.getInstance();
					if (mc.isSameThread()) {
						changed(method.getName(), args);
					} else {
						mc.execute(() -> changed(method.getName(), args));
					}
					return null;
				}
				case "equals" -> {
					return proxy == args[0];
				}
				case "hashCode" -> {
					return System.identityHashCode(proxy);
				}
				case "toString" -> {
					return "Diorama's claims listener";
				}
				default -> {
					return null;
				}
			}
		}
	}

	private static void changed(String what, Object[] args) {
		if (readAll) {
			return;
		}
		switch (what) {
			case "onChunkChange" -> chunksChanged.add(new Area(args[0], (int) args[1], (int) args[2]));
			case "onWholeRegionChange" -> regionsChanged.add(new Area(args[0], (int) args[1], (int) args[2]));
			default -> dimensionsChanged.add(args[0]);
		}
	}

	/** Diorama forgot every claim: they're all read again on the next tick. */
	public static void cleared() {
		readAll = true;
		dimensionsChanged.clear();
		regionsChanged.clear();
		chunksChanged.clear();
	}

	/** Called every client tick, before {@link Claims} makes its snapshot: reads what changed since the last. */
	public static void tick() {
		if (!available()) {
			return;
		}
		try {
			if (readAll) {
				readAll = false;
				try (Stream<?> dimensions = (Stream<?>) dimensionStream.invoke(manager)) {
					for (Iterator<?> it = dimensions.iterator(); it.hasNext(); ) {
						Object dimension = it.next();
						readDimension(dimensionId.invoke(dimension), dimension);
					}
				}
			}
			for (Object id : dimensionsChanged) {
				readDimension(id, dimensionOf.invoke(manager, id));
			}
			for (Area region : regionsChanged) {
				if (!dimensionsChanged.contains(region.dimension())) {
					Object dimension = dimensionOf.invoke(manager, region.dimension());
					readRegion(region.dimension(), region.x(), region.z(), dimension == null ? null : regionOf.invoke(dimension, region.x(), region.z()), false);
				}
			}
			for (Area chunk : chunksChanged) {
				if (!dimensionsChanged.contains(chunk.dimension()) && !regionsChanged.contains(new Area(chunk.dimension(), chunk.x() >> 5, chunk.z() >> 5))) {
					Object state = claimAt.invoke(manager, chunk.dimension(), chunk.x(), chunk.z());
					Claims.updated(key(chunk.dimension()), chunk.x(), chunk.z(), Owner.class, owner(state, chunk.dimension()), false);
				}
			}
		} catch (InvocationTargetException e) {
			// Open Parties and Claims itself threw: read everything again next tick.
			Diorama.LOGGER.debug("Open Parties and Claims couldn't give its claims", e);
			readAll = true;
		} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
			fail(e);
		} finally {
			dimensionsChanged.clear();
			regionsChanged.clear();
			chunksChanged.clear();
		}
	}

	/** Every claim in a dimension, Diorama's old ones forgotten first; {@code dimension} null when it has none. */
	private static void readDimension(Object id, @Nullable Object dimension) throws ReflectiveOperationException {
		Claims.forget(key(id), Owner.class);
		if (dimension == null) {
			return;
		}
		try (Stream<?> regions = (Stream<?>) regionStream.invoke(dimension)) {
			for (Iterator<?> it = regions.iterator(); it.hasNext(); ) {
				Object region = it.next();
				readRegion(id, (int) regionX.invoke(region), (int) regionZ.invoke(region), region, true);
			}
		}
	}

	/** A region's chunks, as unclaimed where {@code region} is null; {@code forgotten} when its old claims already are. */
	private static void readRegion(Object id, int x, int z, @Nullable Object region, boolean forgotten) throws ReflectiveOperationException {
		ResourceKey<Level> key = key(id);
		// Open Parties and Claims keeps one claim object per kind of claim, so a region has few.
		Map<Object, Owner> owners = new IdentityHashMap<>();
		for (int i = 0; i < REGION_SIZE; i++) {
			for (int j = 0; j < REGION_SIZE; j++) {
				Object state = region == null ? null : regionClaim.invoke(region, i, j);
				if (state == null && forgotten) {
					continue;
				}
				Owner owner = null;
				if (state != null) {
					owner = owners.get(state);
					if (owner == null) {
						owner = owner(state, id);
						owners.put(state, owner);
					}
				}
				Claims.updated(key, x << 5 | i, z << 5 | j, Owner.class, owner, false);
			}
		}
	}

	private static ResourceKey<Level> key(Object id) {
		return ResourceKey.create(Registries.DIMENSION, (Identifier) id);
	}

	private static @Nullable Owner owner(@Nullable Object state, Object dimension) throws ReflectiveOperationException {
		if (state == null) {
			return null;
		}
		return new Owner((UUID) claimPlayer.invoke(state), (int) claimSub.invoke(state), (boolean) claimForceloadable.invoke(state), dimension, state);
	}

	/**
	 * One kind of claim: a player's (or the server's) claims with one sub-config, force loadable or
	 * not, which share a colour and title. In a dimension, since server claims can take theirs from
	 * the dimension's config. Keeps a claim of that kind, to look the colour and title up with.
	 */
	private static final class Owner implements Claims.Owner {
		private final UUID player;
		private final int sub;
		private final boolean forceloadable;
		private final Object dimension;
		private final Object state;

		Owner(UUID player, int sub, boolean forceloadable, Object dimension, Object state) {
			this.player = player;
			this.sub = sub;
			this.forceloadable = forceloadable;
			this.dimension = dimension;
			this.state = state;
		}

		@Override
		public @Nullable Optional<Claims.Claim> look() {
			if (!available()) {
				return null;
			}
			try {
				String title;
				int rgb;
				if (fullName != null) {
					// Party names as Xaero's World Map shows them: not to moderators, who see owners.
					boolean parties = !(boolean) moderatorMode.invoke(manager);
					title = ((Component) fullName.invoke(manager, this.state, this.dimension, parties)).getString();
					rgb = (int) colour.invoke(manager, this.state, this.dimension);
				} else {
					Object info = playerInfo.invoke(manager, this.player);
					title = oldTitle(info);
					Integer own = (Integer) subClaimsColour.invoke(info, this.sub);
					rgb = own != null ? own : (int) claimsColour.invoke(info);
				}
				return Optional.of(new Claims.Claim(this, rgb & 0xFFFFFF, title, this.forceloadable, true));
			} catch (InvocationTargetException e) {
				Diorama.LOGGER.debug("Open Parties and Claims couldn't give a claim's name", e);
				return null;
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				fail(e);
				return null;
			}
		}

		/** A claim's title as older versions of Open Parties and Claims word it on entering the claim. */
		private String oldTitle(Object info) throws ReflectiveOperationException {
			String custom = (String) subClaimsName.invoke(info, this.sub);
			if (this.sub != -1 && (custom == null || custom.isEmpty())) {
				custom = (String) claimsName.invoke(info);
			}
			Component forceloaded = this.forceloadable ? Component.translatable("gui.xaero_pac_marked_for_forceload") : Component.literal("");
			Component owner = this.player.equals(SERVER_CLAIM) ? Component.translatable("gui.xaero_pac_title_entered_server_claim", forceloaded)
				: this.player.equals(EXPIRED_CLAIM) ? Component.translatable("gui.xaero_pac_title_entered_expired_claim", forceloaded)
				: Component.translatable("gui.xaero_pac_title_entered_claim", (String) username.invoke(info), forceloaded);
			return custom == null || custom.isEmpty() ? owner.getString() : custom + " - " + owner.getString();
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Owner other && other.player.equals(this.player) && other.sub == this.sub
				&& other.forceloadable == this.forceloadable && other.dimension.equals(this.dimension);
		}

		@Override
		public int hashCode() {
			return Objects.hash(this.player, this.sub, this.forceloadable, this.dimension);
		}

		@Override
		public String toString() {
			return "Open Parties and Claims claim of " + this.player + (this.sub == -1 ? "" : " #" + this.sub) + (this.forceloadable ? ", forceloadable" : "");
		}
	}

	private static void fail(Throwable e) {
		if (!failed) {
			failed = true;
			Diorama.LOGGER.warn("Couldn't read Open Parties and Claims' claims; they won't show on the map", e);
		}
	}
}

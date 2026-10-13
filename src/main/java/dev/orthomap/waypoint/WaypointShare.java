package dev.orthomap.waypoint;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import com.mojang.brigadier.CommandDispatcher;
import org.jspecify.annotations.Nullable;

/**
 * Sharing waypoints through chat, in the format Xaero's Minimap uses, so players of either mod
 * can pick up each other's waypoints: {@code xaero-waypoint:Name:N:x:y:z:colour:false:0:Internal-overworld-waypoints}.
 * Each shared waypoint chat shows gets a line under it with an "Add" link that opens the waypoint
 * editor filled in. The message itself is left alone. Chat can carry anything, so everything read
 * from it is checked.
 */
public final class WaypointShare {
	private static final Pattern SHARED = Pattern.compile(
		"xaero-waypoint:([^:]{1,160}):([^:]{0,20}):(-?\\d{1,9}):(~|-?\\d{1,5}):(-?\\d{1,9}):(\\d{1,2}):(?:true|false):-?\\d{1,5}:(Internal-[^\\s]{1,200}-waypoints)"
	);
	/** Further out than the world border, or outside any world's height, isn't a real place. */
	private static final int MAX_HORIZONTAL = 30_000_000;
	private static final int MAX_VERTICAL = 4096;
	/** The longest chat message a client may send. */
	private static final int MAX_CHAT = 256;
	/** The waypoint editor's limits. */
	private static final int MAX_NAME = 32;
	private static final int MAX_INITIALS = 2;
	private static final Pattern DIMENSION_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

	/** The line this client shared last, and when, so its own share doesn't get an offer. */
	private static @Nullable String lastSent;
	private static long lastSentAt;

	/** A waypoint someone shared. {@code y} is null when the sender didn't know the height. */
	public record Shared(String name, String initials, int x, Integer y, int z, int colorIndex, String dimension) {
	}

	private WaypointShare() {
	}

	/**
	 * The chat text for a waypoint, or empty if it can't fit in chat's 256 characters. Names are
	 * escaped the way Xaero's Minimap does, since colons separate the fields, and anything chat
	 * refuses (formatting codes, control characters) is dropped.
	 */
	public static Optional<String> format(Waypoint waypoint) {
		String initials = encode(chatSafe(waypoint.label()));
		String tail = ":" + initials + ":" + waypoint.x + ":" + waypoint.y + ":" + waypoint.z + ":"
			+ colorIndex(waypoint.color) + ":false:0:Internal-" + xaeroDimension(waypoint.dimension) + "-waypoints";
		String name = chatSafe(waypoint.name).trim();
		if (name.isEmpty()) {
			name = "Waypoint";
		}
		// Cut the name, not an escape, until the line fits.
		String encoded = encode(name);
		while ("xaero-waypoint:".length() + encoded.length() + tail.length() > MAX_CHAT && name.length() > 1) {
			name = name.substring(0, name.length() - 1);
			encoded = encode(name);
		}
		String line = "xaero-waypoint:" + encoded + tail;
		return line.length() > MAX_CHAT ? Optional.empty() : Optional.of(line);
	}

	/** Remembers a line this client is about to send, so its own share gets no offer. */
	public static void sending(String line) {
		lastSent = line;
		lastSentAt = System.currentTimeMillis();
	}

	/** Xaero's escapes for characters that mean something in the share line. */
	static String encode(String text) {
		return text.replace("-", "^min^").replace("_", "-").replace(":", "^col^").replace("*", "^ast^");
	}

	static String decode(String text) {
		return text.replace("^ast^", "*").replace("^col^", ":").replace("-", "_").replace("^min^", "-");
	}

	private static String chatSafe(String text) {
		StringBuilder out = new StringBuilder();
		text.codePoints().filter(c -> c >= ' ' && c != '§' && c != 127).forEach(out::appendCodePoint);
		return out.toString();
	}

	/** The first shared waypoint in a chat message, if there's a well-formed one. */
	public static Optional<Shared> parse(String text) {
		Matcher m = SHARED.matcher(text);
		if (!m.find()) {
			return Optional.empty();
		}
		String name = chatSafe(decode(m.group(1))).trim();
		if (name.length() > MAX_NAME) {
			name = name.substring(0, MAX_NAME).trim();
		}
		StringBuilder initials = new StringBuilder();
		decode(m.group(2)).codePoints().filter(Character::isLetterOrDigit).limit(MAX_INITIALS).forEach(initials::appendCodePoint);
		int x = Integer.parseInt(m.group(3));
		int z = Integer.parseInt(m.group(5));
		Integer y = "~".equals(m.group(4)) ? null : Integer.parseInt(m.group(4));
		int color = Integer.parseInt(m.group(6));
		String xaero = m.group(7).substring("Internal-".length(), m.group(7).length() - "-waypoints".length());
		String dimension = ourDimension(xaero);
		if (name.isEmpty() || Math.abs(x) > MAX_HORIZONTAL || Math.abs(z) > MAX_HORIZONTAL || (y != null && Math.abs(y) > MAX_VERTICAL)
			|| color > 15 || dimension == null) {
			return Optional.empty();
		}
		return Optional.of(new Shared(name, initials.toString().toUpperCase(Locale.ROOT), x, y, z, color, dimension));
	}

	/**
	 * Called for each message chat shows. If it carries a waypoint someone else shared, adds a
	 * line under it offering to add it.
	 */
	public static void shown(Component message) {
		String text = message.getString();
		Optional<Shared> shared = parse(text);
		if (shared.isEmpty()) {
			return;
		}
		if (lastSent != null && text.contains(lastSent) && System.currentTimeMillis() - lastSentAt < 30_000) {
			lastSent = null;
			return;
		}
		offer(shared.get());
	}

	/** The command the "Add" link runs for a shared waypoint. */
	public static String addCommand(Shared shared, int y) {
		return "/orthomap waypoint add " + shared.x() + " " + y + " " + shared.z() + " " + shared.colorIndex()
			+ " \"" + shared.dimension() + "\" \"" + shared.initials() + "\" " + shared.name();
	}

	private static void offer(Shared shared) {
		Minecraft mc = Minecraft.getInstance();
		int y = shared.y() != null ? shared.y() : mc.player != null ? mc.player.getBlockY() : 64;
		String command = addCommand(shared, y);
		MutableComponent add = Component.translatable("orthomap.share.add").withStyle(style -> style
			.withColor(ChatFormatting.GREEN)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.translatable("orthomap.share.add_hover"))));
		Component line = Component.translatable("orthomap.share.received", shared.name(), shared.x(), y, shared.z());
		mc.gui.hud.getChat().addClientSystemMessage(line.copy().append(" ").append(add));
	}

	/** {@code /orthomap waypoint add <x> <y> <z> <colour> "<dimension>" "<initials>" <name>}, which the "Add" link runs. */
	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommands.literal("orthomap")
			.then(ClientCommands.literal("waypoint")
				.then(ClientCommands.literal("add")
					.then(ClientCommands.argument("x", IntegerArgumentType.integer())
						.then(ClientCommands.argument("y", IntegerArgumentType.integer())
							.then(ClientCommands.argument("z", IntegerArgumentType.integer())
								.then(ClientCommands.argument("colour", IntegerArgumentType.integer(0, 15))
									.then(ClientCommands.argument("dimension", StringArgumentType.string())
										.then(ClientCommands.argument("initials", StringArgumentType.string())
											.then(ClientCommands.argument("name", StringArgumentType.greedyString())
												.executes(WaypointShare::add)))))))))));
	}

	private static int add(CommandContext<FabricClientCommandSource> context) {
		String name = StringArgumentType.getString(context, "name");
		Waypoint waypoint = new Waypoint(
			name.length() > MAX_NAME ? name.substring(0, MAX_NAME) : name,
			IntegerArgumentType.getInteger(context, "x"),
			IntegerArgumentType.getInteger(context, "y"),
			IntegerArgumentType.getInteger(context, "z"),
			StringArgumentType.getString(context, "dimension"),
			paletteColor(IntegerArgumentType.getInteger(context, "colour"))
		);
		String initials = StringArgumentType.getString(context, "initials");
		if (!initials.isEmpty()) {
			waypoint.initials = initials.length() > MAX_INITIALS ? initials.substring(0, MAX_INITIALS) : initials;
		}
		Minecraft mc = Minecraft.getInstance();
		// Queued for later: a chat click puts the chat screen back right after running this.
		mc.schedule(() -> mc.gui.setScreen(new WaypointEditScreen(null, waypoint, true)));
		return 1;
	}
	/** Xaero's colours are the 16 chat colours, the first 16 {@link ChatFormatting} values; the nearest one to a waypoint colour. */
	static int colorIndex(int argb) {
		int best = 15;
		long bestDistance = Long.MAX_VALUE;
		for (int i = 0; i < 16; i++) {
			int chat = TextColor.fromLegacyFormat(ChatFormatting.values()[i]).getValue();
			long dr = ((argb >> 16) & 0xFF) - ((chat >> 16) & 0xFF);
			long dg = ((argb >> 8) & 0xFF) - ((chat >> 8) & 0xFF);
			long db = (argb & 0xFF) - (chat & 0xFF);
			long distance = dr * dr + dg * dg + db * db;
			if (distance < bestDistance) {
				bestDistance = distance;
				best = i;
			}
		}
		return best;
	}

	/** The palette colour nearest a chat colour. */
	static int paletteColor(int index) {
		int chat = TextColor.fromLegacyFormat(ChatFormatting.values()[Math.max(0, Math.min(15, index))]).getValue();
		int best = Waypoint.PALETTE[0];
		long bestDistance = Long.MAX_VALUE;
		for (int color : Waypoint.PALETTE) {
			long dr = ((color >> 16) & 0xFF) - ((chat >> 16) & 0xFF);
			long dg = ((color >> 8) & 0xFF) - ((chat >> 8) & 0xFF);
			long db = (color & 0xFF) - (chat & 0xFF);
			long distance = dr * dr + dg * dg + db * db;
			if (distance < bestDistance) {
				bestDistance = distance;
				best = color;
			}
		}
		return best;
	}

	/** Xaero's name for a dimension: {@code overworld}, {@code the-nether}, {@code the-end}, or {@code dim%namespace$path}. */
	static String xaeroDimension(String id) {
		return switch (id) {
			case "minecraft:overworld" -> "overworld";
			case "minecraft:the_nether" -> "the-nether";
			case "minecraft:the_end" -> "the-end";
			default -> "dim%" + id.replace(':', '$').replace('/', '%');
		};
	}

	/** Our dimension id for Xaero's name, or null if it isn't one we can read. */
	static @Nullable String ourDimension(String xaero) {
		String id = switch (xaero.toLowerCase(Locale.ROOT)) {
			case "overworld" -> "minecraft:overworld";
			case "the-nether" -> "minecraft:the_nether";
			case "the-end" -> "minecraft:the_end";
			default -> xaero.startsWith("dim%") ? xaero.substring(4).replace('$', ':').replace('%', '/') : null;
		};
		return id != null && DIMENSION_ID.matcher(id).matches() ? id : null;
	}
}

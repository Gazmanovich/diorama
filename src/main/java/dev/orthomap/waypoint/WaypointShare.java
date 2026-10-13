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
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import org.jspecify.annotations.Nullable;

/**
 * Sharing waypoints through chat, in the format Xaero's Minimap uses, so players of either mod
 * can pick up each other's waypoints: {@code xaero-waypoint:Name:N:x:y:z:colour:false:0:Internal-overworld-waypoints}.
 * A shared waypoint in chat gets a line after it with an "Add" link that opens the waypoint
 * editor filled in. Chat can carry anything, so everything read from it is checked.
 */
public final class WaypointShare {
	private static final Pattern SHARED = Pattern.compile(
		"xaero-waypoint:([^:]{1,64}):([^:]{0,4}):(-?\\d{1,9}):(~|-?\\d{1,5}):(-?\\d{1,9}):(\\d{1,2}):(?:true|false):-?\\d{1,5}:(Internal-[^\\s]{1,200}-waypoints)"
	);
	/** Further out than the world border, or outside any world's height, isn't a real place. */
	private static final int MAX_HORIZONTAL = 30_000_000;
	private static final int MAX_VERTICAL = 4096;
	/** The longest chat message a client may send. */
	private static final int MAX_CHAT = 256;
	private static final Pattern DIMENSION_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

	/** A waypoint someone shared. {@code y} is null when the sender didn't know the height. */
	public record Shared(String name, String initials, int x, Integer y, int z, int colorIndex, String dimension) {
	}

	private WaypointShare() {
	}

	/**
	 * The chat text for a waypoint, or empty if it can't be sent: chat allows at most 256
	 * characters. A colon in the name becomes a semicolon, since colons separate the fields, and
	 * anything chat refuses (formatting codes, control characters) is dropped.
	 */
	public static Optional<String> format(Waypoint waypoint) {
		String tail = ":" + chatSafe(waypoint.label()).replace(":", "") + ":" + waypoint.x + ":" + waypoint.y + ":" + waypoint.z + ":"
			+ colorIndex(waypoint.color) + ":false:0:Internal-" + xaeroDimension(waypoint.dimension) + "-waypoints";
		String name = chatSafe(waypoint.name).replace(':', ';').trim();
		int room = MAX_CHAT - "xaero-waypoint:".length() - tail.length();
		if (room < 1) {
			return Optional.empty();
		}
		if (name.isEmpty()) {
			name = "Waypoint";
		}
		return Optional.of("xaero-waypoint:" + name.substring(0, Math.min(name.length(), Math.min(room, 64))) + tail);
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
		String name = m.group(1).trim();
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
		return Optional.of(new Shared(name, m.group(2), x, y, z, color, dimension));
	}

	/**
	 * Called for every chat and server message. If it carries a waypoint someone else shared,
	 * adds a line after it offering to add it. The message itself stays as it is.
	 */
	public static void received(Component message, @Nullable GameProfile sender) {
		Minecraft mc = Minecraft.getInstance();
		if (sender != null && mc.player != null && sender.id().equals(mc.player.getUUID())) {
			return;
		}
		// Queued for later, so it lands after the message itself; execute() would run it now.
		parse(message.getString()).ifPresent(shared -> mc.schedule(() -> offer(shared, sender == null ? null : sender.name())));
	}

	/** The command the "Add" link runs for a shared waypoint. */
	public static String addCommand(Shared shared, int y) {
		return "/orthomap waypoint add " + shared.x() + " " + y + " " + shared.z() + " " + shared.colorIndex()
			+ " \"" + shared.dimension() + "\" " + shared.name();
	}

	private static void offer(Shared shared, @Nullable String from) {
		Minecraft mc = Minecraft.getInstance();
		int y = shared.y() != null ? shared.y() : mc.player != null ? mc.player.getBlockY() : 64;
		String command = addCommand(shared, y);
		MutableComponent add = Component.translatable("orthomap.share.add").withStyle(style -> style
			.withColor(ChatFormatting.GREEN)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.translatable("orthomap.share.add_hover"))));
		Component line = from == null
			? Component.translatable("orthomap.share.received", shared.name(), shared.x(), y, shared.z())
			: Component.translatable("orthomap.share.received_from", from, shared.name(), shared.x(), y, shared.z());
		mc.gui.hud.getChat().addClientSystemMessage(line.copy().append(" ").append(add));
	}

	/** {@code /orthomap waypoint add <x> <y> <z> <colour> "<dimension>" <name>}, which the "Add" link runs. */
	public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
		dispatcher.register(ClientCommands.literal("orthomap")
			.then(ClientCommands.literal("waypoint")
				.then(ClientCommands.literal("add")
					.then(ClientCommands.argument("x", IntegerArgumentType.integer())
						.then(ClientCommands.argument("y", IntegerArgumentType.integer())
							.then(ClientCommands.argument("z", IntegerArgumentType.integer())
								.then(ClientCommands.argument("colour", IntegerArgumentType.integer(0, 15))
									.then(ClientCommands.argument("dimension", StringArgumentType.string())
										.then(ClientCommands.argument("name", StringArgumentType.greedyString())
											.executes(WaypointShare::add))))))))));
	}

	private static int add(CommandContext<FabricClientCommandSource> context) {
		Waypoint waypoint = new Waypoint(
			StringArgumentType.getString(context, "name"),
			IntegerArgumentType.getInteger(context, "x"),
			IntegerArgumentType.getInteger(context, "y"),
			IntegerArgumentType.getInteger(context, "z"),
			StringArgumentType.getString(context, "dimension"),
			paletteColor(IntegerArgumentType.getInteger(context, "colour"))
		);
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

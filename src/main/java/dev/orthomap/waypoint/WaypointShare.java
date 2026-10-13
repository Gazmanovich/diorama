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
 * A shared waypoint in chat gets an "Add" link that opens the waypoint editor filled in.
 */
public final class WaypointShare {
	private static final Pattern SHARED = Pattern.compile(
		"xaero-waypoint:([^:]*):([^:]*):(-?\\d+):(~|-?\\d+):(-?\\d+):(\\d+):(?:true|false):-?\\d+:(Internal-[^\\s]+-waypoints)"
	);
	/** Xaero writes a colon in a name as this. */
	private static final String COLON = "§§";

	/** A waypoint someone shared. {@code y} is null when the sender didn't know the height. */
	public record Shared(String name, String initials, int x, Integer y, int z, int colorIndex, String dimension) {
	}

	private WaypointShare() {
	}

	/** The chat text for a waypoint. */
	public static String format(Waypoint waypoint) {
		return "xaero-waypoint:" + waypoint.name.replace(":", COLON) + ":" + waypoint.label().replace(":", "") + ":"
			+ waypoint.x + ":" + waypoint.y + ":" + waypoint.z + ":" + colorIndex(waypoint.color) + ":false:0:"
			+ "Internal-" + xaeroDimension(waypoint.dimension) + "-waypoints";
	}

	/** The first shared waypoint in a chat message, if there is one. */
	public static Optional<Shared> parse(String text) {
		Matcher m = SHARED.matcher(text);
		if (!m.find()) {
			return Optional.empty();
		}
		String name = m.group(1).replace(COLON, ":");
		Integer y = "~".equals(m.group(4)) ? null : Integer.parseInt(m.group(4));
		int color = Math.min(15, Integer.parseInt(m.group(6)));
		String dimension = m.group(7).substring("Internal-".length(), m.group(7).length() - "-waypoints".length());
		return Optional.of(new Shared(name, m.group(2), Integer.parseInt(m.group(3)), y, Integer.parseInt(m.group(5)), color, ourDimension(dimension)));
	}

	/**
	 * Called for every chat and server message. If it carries a waypoint, shows a readable line
	 * in its place that offers to add it, and returns true so the raw line is hidden.
	 */
	public static boolean received(Component message, @Nullable GameProfile sender) {
		Optional<Shared> shared = parse(message.getString());
		if (shared.isEmpty()) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		boolean own = sender != null && mc.player != null && sender.id().equals(mc.player.getUUID());
		if (own) {
			mc.gui.hud.getChat().addClientSystemMessage(Component.translatable("orthomap.share.sent", shared.get().name()).withStyle(ChatFormatting.GRAY));
		} else {
			offer(shared.get(), sender == null ? null : sender.name());
		}
		return true;
	}

	private static void offer(Shared shared, @Nullable String from) {
		Minecraft mc = Minecraft.getInstance();
		int y = shared.y() != null ? shared.y() : mc.player != null ? mc.player.getBlockY() : 64;
		String command = "/orthomap waypoint add " + shared.x() + " " + y + " " + shared.z() + " " + shared.colorIndex()
			+ " \"" + shared.dimension() + "\" " + shared.name();
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
		// After the chat screen that ran the command has closed.
		mc.execute(() -> mc.gui.setScreen(new WaypointEditScreen(null, waypoint, true)));
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

	static String ourDimension(String xaero) {
		return switch (xaero.toLowerCase(Locale.ROOT)) {
			case "overworld" -> "minecraft:overworld";
			case "the-nether" -> "minecraft:the_nether";
			case "the-end" -> "minecraft:the_end";
			default -> xaero.startsWith("dim%") ? xaero.substring(4).replace('$', ':').replace('%', '/') : "minecraft:overworld";
		};
	}
}

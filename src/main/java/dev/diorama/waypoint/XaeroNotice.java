package dev.diorama.waypoint;

import dev.diorama.Diorama;
import dev.diorama.WorldKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import org.jspecify.annotations.Nullable;

/**
 * Once per world, a chat line saying Xaero's Minimap has waypoints for it that aren't in Diorama
 * yet, with an [Import] link that opens the waypoint list, where the import is. The list's import
 * button alone is out of sight for the players who need it most: with Xaero's installed, U opens
 * Xaero's list instead (both mods bind it).
 */
public final class XaeroNotice {
	/** About 5 s after joining, so the line isn't lost among the join's own messages. */
	private static final int DELAY_TICKS = 100;
	/** Kept in the world's folder once the line was shown, so it's shown once per world. */
	private static final String SHOWN_FILE = "xaero-import-offered";
	/** The command the link runs. */
	public static final String COMMAND = "/diorama waypoint list";

	/** The world last checked, and the one counting down to its check. */
	private static @Nullable String checked;
	private static @Nullable String pending;
	private static int ticks;

	private XaeroNotice() {
	}

	/** Called at the end of every client tick. */
	public static void tick(Minecraft mc) {
		String key = mc.level != null && mc.player != null ? WorldKey.current() : null;
		if (key == null) {
			if (mc.level == null) {
				checked = null;
			}
			pending = null;
			return;
		}
		if (key.equals(checked)) {
			return;
		}
		// Each world counts from its own start: a network's lobby doesn't count toward the next server.
		if (!key.equals(pending)) {
			pending = key;
			ticks = 0;
		}
		if (++ticks < DELAY_TICKS) {
			return;
		}
		checked = key;
		pending = null;
		Path shown = WorldKey.folder(key).resolve(SHOWN_FILE);
		if (Files.exists(shown)) {
			return;
		}
		WaypointStore store = WaypointStore.get();
		if (store == null) {
			return;
		}
		Component message = message(XaeroImport.find(), store);
		if (message == null) {
			return;
		}
		try {
			Files.createDirectories(shown.getParent());
			Files.writeString(shown, "");
		} catch (IOException e) {
			// Shown anyway; it comes again on the next visit.
			Diorama.LOGGER.warn("Couldn't note that the Xaero's import was offered for {}", key, e);
		}
		//? if >=26.1 {
		mc.gui.hud.getChat().addClientSystemMessage(message);
		//?} else {
		/*mc.gui.getChat().addMessage(message);
		*///?}
	}

	/** The chat line for what Xaero's has, or null when the waypoint list wouldn't offer an import either. */
	static @Nullable Component message(XaeroImport.@Nullable Found found, WaypointStore store) {
		if (found == null) {
			return null;
		}
		XaeroImport.World chosen = found.chosen();
		if (chosen != null) {
			int count = XaeroImport.missing(chosen, store).size();
			if (count == 0) {
				return null;
			}
			Component waypoints = count == 1
				? Component.translatable("diorama.xaero.count_one")
				: Component.translatable("diorama.xaero.count", count);
			return Component.translatable("diorama.xaero.notice", waypoints, link());
		}
		if (found.worlds().stream().allMatch(world -> XaeroImport.missing(world, store).isEmpty())) {
			return null;
		}
		return Component.translatable("diorama.xaero.notice_server", link());
	}

	private static Component link() {
		return Component.translatable("diorama.xaero.notice_link").withStyle(style -> style
			.withColor(ChatFormatting.GREEN)
			.withClickEvent(new ClickEvent.RunCommand(COMMAND))
			.withHoverEvent(new HoverEvent.ShowText(Component.translatable("diorama.xaero.notice_hover"))));
	}
}

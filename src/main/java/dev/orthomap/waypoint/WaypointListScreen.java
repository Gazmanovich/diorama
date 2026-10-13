package dev.orthomap.waypoint;

import dev.orthomap.render.MapIcons;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import org.jspecify.annotations.Nullable;

/**
 * Every waypoint in the current dimension, nearest first, with buttons to edit, hide, delete and,
 * for players allowed to use /tp, teleport.
 */
public final class WaypointListScreen extends Screen {
	private static final int ROW = 24;
	private static final int LIST_WIDTH = 340;

	private final @Nullable Screen parent;
	private final List<Waypoint> rows = new ArrayList<>();
	private int scroll;
	private int listTop;
	private int visibleRows;

	public WaypointListScreen(@Nullable Screen parent) {
		super(Component.translatable("orthomap.waypoints.title"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		this.rows.clear();
		WaypointStore store = WaypointStore.get();
		LocalPlayer player = this.minecraft.player;
		if (store != null && player != null && this.minecraft.level != null) {
			this.rows.addAll(store.in(this.minecraft.level.dimension().identifier().toString()));
			this.rows.sort(Comparator.comparingDouble(w -> w.distanceTo(player.getX(), player.getY(), player.getZ())));
		}
		this.listTop = 40;
		this.visibleRows = Math.max(1, (this.height - this.listTop - 40) / ROW);
		this.scroll = Math.max(0, Math.min(this.scroll, this.rows.size() - this.visibleRows));
		int left = (this.width - LIST_WIDTH) / 2;
		boolean canTeleport = player != null && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
		for (int i = 0; i < this.visibleRows && this.scroll + i < this.rows.size(); i++) {
			Waypoint waypoint = this.rows.get(this.scroll + i);
			int y = this.listTop + i * ROW + 2;
			int bx = left + LIST_WIDTH;
			if (canTeleport) {
				bx -= 30;
				this.addRenderableWidget(Button.builder(Component.translatable("orthomap.waypoints.tp"), b -> this.teleport(waypoint)).bounds(bx, y, 28, 20).build());
			}
			bx -= 46;
			this.addRenderableWidget(Button.builder(Component.translatable("orthomap.waypoint.delete"), b -> {
				WaypointStore s = WaypointStore.get();
				if (s != null) {
					s.remove(waypoint);
				}
				this.rebuildWidgets();
			}).bounds(bx, y, 44, 20).build());
			bx -= 46;
			this.addRenderableWidget(Button.builder(Component.translatable(waypoint.visible ? "orthomap.waypoints.hide" : "orthomap.waypoints.show"), b -> {
				waypoint.visible = !waypoint.visible;
				WaypointStore s = WaypointStore.get();
				if (s != null) {
					s.changed();
				}
				this.rebuildWidgets();
			}).bounds(bx, y, 44, 20).build());
			bx -= 46;
			this.addRenderableWidget(Button.builder(Component.translatable("orthomap.waypoints.edit"), b ->
				this.minecraft.gui.setScreen(new WaypointEditScreen(this, waypoint, false))).bounds(bx, y, 44, 20).build());
		}
		int bottom = this.height - 30;
		this.addRenderableWidget(Button.builder(Component.translatable("orthomap.waypoint.new"), b -> {
			Waypoint fresh = Waypoints.atPlayer();
			if (fresh != null) {
				this.minecraft.gui.setScreen(new WaypointEditScreen(this, fresh, true));
			}
		}).bounds(this.width / 2 - 102, bottom, 100, 20).build());
		this.addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> this.onClose()).bounds(this.width / 2 + 2, bottom, 100, 20).build());
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		super.extractRenderState(graphics, mouseX, mouseY, a);
		graphics.centeredText(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);
		LocalPlayer player = this.minecraft.player;
		int left = (this.width - LIST_WIDTH) / 2;
		if (this.rows.isEmpty()) {
			graphics.centeredText(this.font, Component.translatable("orthomap.waypoints.empty"), this.width / 2, this.listTop + 20, 0xFFA0A0A0);
		}
		for (int i = 0; i < this.visibleRows && this.scroll + i < this.rows.size(); i++) {
			Waypoint waypoint = this.rows.get(this.scroll + i);
			int y = this.listTop + i * ROW;
			graphics.fill(left - 4, y, left + LIST_WIDTH + 4, y + ROW - 1, i % 2 == 0 ? 0x30FFFFFF : 0x18FFFFFF);
			float cx = left + 9;
			float cy = y + ROW / 2.0F;
			MapIcons.dot(graphics, cx, cy, 16, 0xFF101418);
			MapIcons.dot(graphics, cx, cy, 14, waypoint.color);
			String label = waypoint.kind == Waypoint.Kind.DEATH ? "✝" : waypoint.label();
			graphics.pose().pushMatrix();
			graphics.pose().translate(cx, cy - 3);
			graphics.pose().scale(0.75F);
			graphics.centeredText(this.font, label, 0, 0, WaypointOverlay.textColour(waypoint.color));
			graphics.pose().popMatrix();
			int nameColor = waypoint.visible ? 0xFFFFFFFF : 0xFF808080;
			graphics.text(this.font, waypoint.name, left + 22, y + 4, nameColor, false);
			String detail = waypoint.x + ", " + waypoint.y + ", " + waypoint.z;
			if (player != null) {
				detail = Math.round(waypoint.distanceTo(player.getX(), player.getY(), player.getZ())) + " m · " + detail;
			}
			graphics.text(this.font, detail, left + 22, y + 14, 0xFF909090, false);
		}
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		int next = Math.max(0, Math.min(this.scroll - (int) Math.signum(scrollY), this.rows.size() - this.visibleRows));
		if (next != this.scroll) {
			this.scroll = next;
			this.rebuildWidgets();
		}
		return true;
	}

	private void teleport(Waypoint waypoint) {
		LocalPlayer player = this.minecraft.player;
		if (player != null) {
			player.connection.sendCommand("tp @s " + waypoint.x + " " + waypoint.y + " " + waypoint.z);
		}
		this.minecraft.gui.setScreen(null);
	}

	@Override
	public void onClose() {
		this.minecraft.gui.setScreen(this.parent);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}

package dev.orthomap;

import dev.orthomap.bake.TileKey;
import dev.orthomap.render.MapViewport;
import dev.orthomap.render.Markers;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import com.mojang.blaze3d.platform.InputConstants;

/** The fullscreen map: drag to pan, scroll to zoom, C for caves, PgUp/PgDn to move the slice. */
public final class MapScreen extends Screen {
	private static final double MIN_ZOOM = 0.5;
	private static final double MAX_ZOOM = 16;

	private final MapViewport viewport = new MapViewport();
	private double centerU;
	private double centerV;
	private double zoom = 2.0;
	private int layer;
	private boolean following = true;

	public MapScreen() {
		super(Component.translatable("orthomap.screen.title"));
		MapSession session = MapSession.get();
		LocalPlayer player = net.minecraft.client.Minecraft.getInstance().player;
		this.layer = session != null && player != null ? session.autoLayer(player) : TileKey.SURFACE;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
		MapSession session = MapSession.get();
		LocalPlayer player = this.minecraft.player;
		if (session == null || player == null) {
			return;
		}
		Vec3 pos = player.getPosition(a);
		double playerU = pos.x;
		double playerV = Projection.v(pos.y, pos.z);
		if (this.following) {
			this.centerU = playerU;
			this.centerV = playerV;
		}
		MapViewport.View view = new MapViewport.View(0, 0, this.width, this.height, this.centerU, this.centerV, this.zoom, this.layer);
		this.viewport.draw(graphics, session.map(), view);
		Markers.player(graphics, view.screenX(playerU), view.screenY(playerV), player.getViewYRot(a), 6);

		String mode = this.layer == TileKey.SURFACE
			? "Surface"
			: "Cave slice at y=" + this.layer;
		String status = String.format(
			"%s   ·   %d, %d, %d   ·   %d chunks%s",
			mode, player.getBlockX(), player.getBlockY(), player.getBlockZ(), session.map().chunkCount(),
			session.map().bakingCount() > 0 ? "   ·   baking" : ""
		);
		graphics.fill(0, 0, this.width, 14, 0x90000000);
		graphics.text(this.font, status, 6, 3, 0xFFFFFFFF, false);
		String help = "Drag to pan · Scroll to zoom · C caves · PgUp/PgDn slice · Space recenter";
		graphics.fill(0, this.height - 14, this.width, this.height, 0x90000000);
		graphics.text(this.font, help, 6, this.height - 11, 0xFFC8D0DA, false);
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
			this.centerU -= dx / this.zoom;
			this.centerV -= dy / this.zoom;
			this.following = false;
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		double before = this.zoom;
		double after = Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, before * Math.pow(1.25, scrollY)));
		if (after != before) {
			// Keep the map point under the cursor where it is.
			double mapU = this.centerU + (x - this.width / 2.0) / before;
			double mapV = this.centerV + (y - this.height / 2.0) / before;
			this.centerU = mapU - (x - this.width / 2.0) / after;
			this.centerV = mapV - (y - this.height / 2.0) / after;
			this.zoom = after;
			if (this.following) {
				this.following = false;
			}
		}
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		LocalPlayer player = this.minecraft.player;
		switch (event.key()) {
			case InputConstants.KEY_C -> {
				this.layer = this.layer == TileKey.SURFACE && player != null ? MapSession.caveLayerFor(player.getY()) : TileKey.SURFACE;
				return true;
			}
			case InputConstants.KEY_PAGEUP -> {
				this.shiftSlice(4, player);
				return true;
			}
			case InputConstants.KEY_PAGEDOWN -> {
				this.shiftSlice(-4, player);
				return true;
			}
			case InputConstants.KEY_SPACE -> {
				this.following = true;
				return true;
			}
			default -> {
			}
		}
		if (OrthomapClient.OPEN_MAP.matches(event)) {
			this.onClose();
			return true;
		}
		return super.keyPressed(event);
	}

	private void shiftSlice(int delta, LocalPlayer player) {
		if (this.layer == TileKey.SURFACE) {
			if (player == null) {
				return;
			}
			this.layer = MapSession.caveLayerFor(player.getY());
		}
		this.layer = Math.max(this.minecraft.level.getMinY() + 4, Math.min(this.minecraft.level.getMaxY() + 1, this.layer + delta));
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void removed() {
		this.viewport.close();
	}

	/** For tests: put the view at a spot without following the player. */
	public void lookAt(double u, double v, double zoom, int layer) {
		this.centerU = u;
		this.centerV = v;
		this.zoom = zoom;
		this.layer = layer;
		this.following = false;
	}
}

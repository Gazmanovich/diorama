package dev.diorama.test;

import dev.diorama.Diorama;
import dev.diorama.MapScreen;
import dev.diorama.MapSession;
import dev.diorama.Projection;
import dev.diorama.bake.TileKey;
import dev.diorama.render.MapLevel;
import dev.diorama.render.MapViewport;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;

/**
 * Pictures of the full map in the frames right after a zoom crosses to another tile level, and
 * once it has settled (opt-in: {@code zoom-stand-in}). For PR 264: tiles not loaded yet at the new
 * level, drawn from a loaded coarser or finer one instead of fog.
 */
final class ZoomStandInTest {
	private static final int SPREAD = 320;

	private ZoomStandInTest() {
	}

	static void run(ClientGameTestContext context, TestSingleplayerContext world) {
		int[] home = context.computeOnClient(mc -> new int[] {mc.player.blockPosition().getX(), mc.player.blockPosition().getZ()});
		// Land to zoom over: the spawn area and four more around it, so it fills a zoomed-out view.
		int[][] stops = {{SPREAD, SPREAD}, {-SPREAD, SPREAD}, {-SPREAD, -SPREAD}, {SPREAD, -SPREAD}, {0, 0}};
		for (int[] stop : stops) {
			world.getServer().runCommand("tp @a " + (home[0] + stop[0]) + " 200 " + (home[1] + stop[1]));
			context.waitTicks(200);
		}
		DioramaClientTest.settle(context, 100);
		double u = Projection.u(home[0], home[1]);
		double v = Projection.v(home[0], 64, home[1]);
		context.setScreen(MapScreen::new);
		// Out across the overview levels, then in again somewhere else, then in close.
		double[][] steps = {
			{u, v, 2.0},
			{u, v, 0.4},
			{u, v, 0.2},
			{u, v, 0.08},
			{u, v, 0.03},
			{u - SPREAD, v - SPREAD * 0.6, 0.3},
			{u + SPREAD, v + SPREAD * 0.6, 1.0},
			{u, v, 0.45},
		};
		for (int i = 0; i < steps.length; i++) {
			double[] step = steps[i];
			context.runOnClient(mc -> ((MapScreen) mc.gui.screen()).lookAt(step[0], step[1], step[2], TileKey.SURFACE));
			String name = "zoom-" + i + "-" + step[2];
			context.takeScreenshot(name + "-a-first");
			log(context, step, name + " first frame");
			context.waitTicks(2);
			context.takeScreenshot(name + "-b-2ticks");
			context.waitTicks(8);
			context.takeScreenshot(name + "-c-10ticks");
			log(context, step, name + " after 10 ticks");
			DioramaClientTest.settle(context, 20);
			context.takeScreenshot(name + "-d-settled");
			log(context, step, name + " settled");
		}
		context.setScreen(() -> null);
	}

	/** How many tiles the view shows at its level, and how many of those have no texture yet. */
	private static void log(ClientGameTestContext context, double[] step, String what) {
		context.runOnClient(mc -> {
			MapLevel level = MapSession.get().map();
			int w = mc.getWindow().getGuiScaledWidth();
			int h = mc.getWindow().getGuiScaledHeight();
			MapViewport.View view = new MapViewport.View(0, 0, w, h, step[0], step[1], step[2], TileKey.SURFACE);
			int lod = MapViewport.lodFor(level, view);
			double units = (double) Projection.TILE_UNITS * (1 << lod);
			int shown = 0;
			int missing = 0;
			for (int tu = (int) Math.floor(view.minU() / units); tu <= (int) Math.floor((view.minU() + w / view.zoom()) / units); tu++) {
				for (int tv = (int) Math.floor(view.minV() / units); tv <= (int) Math.floor((view.minV() + h / view.zoom()) / units); tv++) {
					shown++;
					var tile = level.peek(new TileKey(TileKey.SURFACE, tu, tv, lod));
					if (tile == null || tile.texture() == null && !tile.isEmpty()) {
						missing++;
					}
				}
			}
			Diorama.LOGGER.info("zoom-stand-in {}: zoom {} lod {}, {} tiles, {} not loaded", what, view.zoom(), lod, shown, missing);
		});
	}
}

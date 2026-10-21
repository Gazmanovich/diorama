package dev.diorama.waypoint;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
//? if >=1.21.6 {
import net.minecraft.client.renderer.RenderPipelines;
//?}
import net.minecraft.resources.Identifier;

/** Vanilla's own pieces of GUI art, drawn the way each Minecraft version takes them. */
final class ScreenArt {
	/** The darker panel vanilla puts under a list while a world is open. */
	private static final Identifier LIST_BACKGROUND = Identifier.withDefaultNamespace("textures/gui/inworld_menu_list_background.png");
	static final Identifier SEARCH = Identifier.withDefaultNamespace("icon/search");
	static final Identifier TEXT_FIELD = Identifier.withDefaultNamespace("widget/text_field");
	static final Identifier TEXT_FIELD_FOCUSED = Identifier.withDefaultNamespace("widget/text_field_highlighted");
	static final Identifier SCROLLER = Identifier.withDefaultNamespace("widget/scroller");
	static final Identifier SCROLLER_BACKGROUND = Identifier.withDefaultNamespace("widget/scroller_background");
	/** Vanilla's scrollbar width and shortest handle. */
	static final int SCROLLBAR_WIDTH = 6;
	static final int SCROLLER_MIN = 32;

	private ScreenArt() {
	}

	/** A GUI sprite (nine-sliced where its metadata says so) stretched over the rectangle. */
	static void sprite(GuiGraphicsExtractor graphics, Identifier sprite, int x, int y, int width, int height) {
		//? if >=1.21.6 {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height);
		//?} else if >=1.21.2 {
		/*graphics.blitSprite(net.minecraft.client.renderer.RenderType::guiTextured, sprite, x, y, width, height);
		*///?} else {
		/*com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		graphics.blitSprite(sprite, x, y, width, height);
		com.mojang.blaze3d.systems.RenderSystem.disableBlend();
		*///?}
	}

	/** Vanilla's in-world list panel from y0 to y1, with its header and footer separators. */
	static void list(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1) {
		int w = x1 - x0;
		int h = y1 - y0;
		// Texture offsets as vanilla's lists use them, so the pattern sits the same way.
		//? if >=1.21.6 {
		graphics.blit(RenderPipelines.GUI_TEXTURED, LIST_BACKGROUND, x0, y0, x1, y1, w, h, 32, 32);
		graphics.blit(RenderPipelines.GUI_TEXTURED, Screen.INWORLD_HEADER_SEPARATOR, x0, y0 - 2, 0, 0, w, 2, 32, 2);
		graphics.blit(RenderPipelines.GUI_TEXTURED, Screen.INWORLD_FOOTER_SEPARATOR, x0, y1, 0, 0, w, 2, 32, 2);
		//?} else if >=1.21.2 {
		/*graphics.blit(net.minecraft.client.renderer.RenderType::guiTextured, LIST_BACKGROUND, x0, y0, x1, y1, w, h, 32, 32);
		graphics.blit(net.minecraft.client.renderer.RenderType::guiTextured, Screen.INWORLD_HEADER_SEPARATOR, x0, y0 - 2, 0, 0, w, 2, 32, 2);
		graphics.blit(net.minecraft.client.renderer.RenderType::guiTextured, Screen.INWORLD_FOOTER_SEPARATOR, x0, y1, 0, 0, w, 2, 32, 2);
		*///?} else {
		/*com.mojang.blaze3d.systems.RenderSystem.enableBlend();
		graphics.blit(LIST_BACKGROUND, x0, y0, x1, y1, w, h, 32, 32);
		graphics.blit(Screen.INWORLD_HEADER_SEPARATOR, x0, y0 - 2, 0, 0, w, 2, 32, 2);
		graphics.blit(Screen.INWORLD_FOOTER_SEPARATOR, x0, y1, 0, 0, w, 2, 32, 2);
		com.mojang.blaze3d.systems.RenderSystem.disableBlend();
		*///?}
	}

	/** Vanilla's selection outline: white when the list has focus, as vanilla's lists draw it. */
	static void selection(GuiGraphicsExtractor graphics, int x0, int y0, int x1, int y1) {
		graphics.fill(x0, y0, x1, y1, 0xFFFFFFFF);
		graphics.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, 0xFF000000);
	}

	/** Vanilla's scrollbar on the right of a list from y0 to y1, its handle {@code top} of {@code range} down. */
	static void scrollbar(GuiGraphicsExtractor graphics, int x, int y0, int y1, int handle, int top) {
		sprite(graphics, SCROLLER_BACKGROUND, x, y0, SCROLLBAR_WIDTH, y1 - y0);
		sprite(graphics, SCROLLER, x, y0 + top, SCROLLBAR_WIDTH, handle);
	}
}

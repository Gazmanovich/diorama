package dev.diorama.gameshim;

/**
 * Added to the game's Window by {@code WindowMixin}. The framebuffer, and the window size the game
 * believes in, are whatever the test asked for; the real window follows as far as the screen lets
 * it, and its real size is only used to show the frame.
 */
public interface WindowHooks {
	int diorama$realFramebufferWidth();

	int diorama$realFramebufferHeight();

	/** Back to the size the game started with. */
	void diorama$resetSize();

	/** The game believes its window has focus (from 26.1, where it asks the window). */
	void diorama$focus();

	void diorama$resize(int width, int height);
}

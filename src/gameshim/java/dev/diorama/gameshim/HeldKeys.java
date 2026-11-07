package dev.diorama.gameshim;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The keys a test holds ({@code InputConstants.KEY_*}), which the game reads as held through
 * {@code InputConstants.isKeyDown}; every other key reads as up.
 */
public final class HeldKeys {
	private static final Set<Integer> HELD = ConcurrentHashMap.newKeySet();

	private HeldKeys() {
	}

	public static boolean held(int key) {
		return HELD.contains(key);
	}

	static boolean hold(int key) {
		return HELD.add(key);
	}

	static boolean release(int key) {
		return HELD.remove(key);
	}
}

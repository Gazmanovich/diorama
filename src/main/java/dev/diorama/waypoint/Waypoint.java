package dev.diorama.waypoint;

import java.util.UUID;

/**
 * A marked place. Mutable so the edit screen can change it in place; {@link WaypointStore} saves
 * after every change.
 */
public final class Waypoint {
	public enum Kind {
		MANUAL,
		/** Added automatically where the player died. */
		DEATH,
		/** Set from the map's menu; never saved, so it's gone once the player leaves the world. */
		TEMPORARY,
		/** A waystone the Waystones mod told the client about ({@link Waystones}). Never saved, and Waystones names it. */
		WAYSTONE
	}

	/** Colours a waypoint can take, picked to stay readable on both grass and water. */
	public static final int[] PALETTE = {
		0xFFE5484D, 0xFFF76B15, 0xFFFFC53D, 0xFF46A758, 0xFF12A594, 0xFF0090FF, 0xFF3E63DD, 0xFF8E4EC6,
		0xFFD6409F, 0xFFFFFFFF, 0xFFB0B4BA, 0xFF60646C, 0xFF8D6B4B, 0xFF99D52A, 0xFF7CE2FE, 0xFFF0C0D8
	};

	public UUID id = UUID.randomUUID();
	public String name = "";
	public String initials = "";
	public int x;
	public int y;
	public int z;
	/** Dimension id, e.g. {@code minecraft:overworld}. */
	public String dimension = "minecraft:overworld";
	public int color = PALETTE[0];
	public boolean visible = true;
	/** The custom folder it's in, or empty for its dimension's own folder. See {@link WaypointStore#folderKey}. */
	public String folder = "";
	public Kind kind = Kind.MANUAL;
	public long created = System.currentTimeMillis();

	public Waypoint() {
	}

	public Waypoint(String name, int x, int y, int z, String dimension, int color) {
		this.name = name;
		this.initials = initialsFor(name);
		this.x = x;
		this.y = y;
		this.z = z;
		this.dimension = dimension;
		this.color = color;
	}

	/** Whether the player can edit or delete it here: everything but waystones, which Waystones owns. */
	public boolean editable() {
		return this.kind != Kind.WAYSTONE;
	}

	public String label() {
		return this.initials.isEmpty() ? initialsFor(this.name) : this.initials;
	}

	public double distanceTo(double px, double py, double pz) {
		double dx = this.x + 0.5 - px;
		double dy = this.y - py;
		double dz = this.z + 0.5 - pz;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/** First letter of up to two words: "Home" is H, "Iron Farm" is IF. */
	public static String initialsFor(String name) {
		StringBuilder out = new StringBuilder();
		for (String word : name.trim().split("\\s+")) {
			if (!word.isEmpty() && out.length() < 2) {
				out.appendCodePoint(Character.toUpperCase(word.codePointAt(0)));
			}
		}
		return out.isEmpty() ? "?" : out.toString();
	}
}

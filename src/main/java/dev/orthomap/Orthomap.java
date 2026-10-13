package dev.orthomap;

import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Orthomap {
	public static final String MOD_ID = "orthomap";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
	/** Extra logging, on with -Dorthomap.debug=true or ORTHOMAP_DEBUG=1. */
	public static final boolean DEBUG = Boolean.getBoolean("orthomap.debug") || "1".equals(System.getenv("ORTHOMAP_DEBUG"));

	private Orthomap() {
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}

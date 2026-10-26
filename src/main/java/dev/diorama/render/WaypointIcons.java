package dev.diorama.render;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.jspecify.annotations.Nullable;

/**
 * The pictures a waypoint can show instead of its initials. An icon is named by an id:
 * {@code diorama:bed} is the image {@code assets/diorama/textures/waypoint_icons/bed.png}, so a
 * resource pack can redraw the set or add to it; {@code mob:minecraft:villager} is the face the
 * radar draws for that mob ({@link MobFaces}). Images are pixel art, one texel per GUI pixel.
 */
public final class WaypointIcons {
	public static final String MOB = "mob:";
	private static final String FOLDER = "textures/waypoint_icons/";
	/** The icons the picker offers, in order: Diorama's own, then mob faces. */
	public static final List<String> OFFERED = List.of(
		"diorama:bed", "diorama:house",
		MOB + "minecraft:villager", MOB + "minecraft:cow", MOB + "minecraft:pig", MOB + "minecraft:sheep",
		MOB + "minecraft:chicken", MOB + "minecraft:horse", MOB + "minecraft:wolf", MOB + "minecraft:cat", MOB + "minecraft:iron_golem", MOB + "minecraft:bee");
	private static final Map<String, Optional<PinArt.Image>> IMAGES = new HashMap<>();

	private WaypointIcons() {
	}

	/** The icon's pixels, or null if there's no such icon (then the pin shows initials). */
	public static PinArt.@Nullable Image image(String id) {
		Optional<PinArt.Image> known = IMAGES.get(id);
		if (known != null) {
			return known.orElse(null);
		}
		PinArt.Image image = load(id);
		// A mob face needs a world to make the mob in; try again once there is one.
		if (image != null || !id.startsWith(MOB) || Minecraft.getInstance().level != null) {
			IMAGES.put(id, Optional.ofNullable(image));
		}
		return image;
	}

	/** Forgets loaded icons, for a resource reload. */
	public static void clear() {
		IMAGES.clear();
	}

	private static PinArt.@Nullable Image load(String id) {
		if (id.startsWith(MOB)) {
			Identifier type = Identifier.tryParse(id.substring(MOB.length()));
			if (type == null) {
				return null;
			}
			// Small faces (a chicken's is 4 x 6) are doubled, so every face is at least 8 pixels.
			return BuiltInRegistries.ENTITY_TYPE.getOptional(type).map(MobFaces::faceImage).map(face -> Math.max(face.width, face.height) < 8 ? twice(face) : face).orElse(null);
		}
		Identifier name = Identifier.tryParse(id);
		if (name == null) {
			return null;
		}
		Identifier file = Identifier.fromNamespaceAndPath(name.getNamespace(), FOLDER + name.getPath() + ".png");
		Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(file);
		if (resource.isEmpty()) {
			return null;
		}
		try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
			return cropped(image);
		} catch (IOException | RuntimeException e) {
			return null;
		}
	}

	private static PinArt.Image twice(PinArt.Image image) {
		int w = image.width * 2;
		int[] argb = new int[w * image.height * 2];
		for (int y = 0; y < image.height * 2; y++) {
			for (int x = 0; x < w; x++) {
				argb[y * w + x] = image.argb(x / 2, y / 2);
			}
		}
		return PinArt.fromArgb(w, image.height * 2, argb);
	}

	/** The image cut to its opaque pixels. */
	private static PinArt.@Nullable Image cropped(NativeImage image) {
		int width = image.getWidth();
		int height = image.getHeight();
		int x0 = width;
		int y0 = height;
		int x1 = -1;
		int y1 = -1;
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				if (image.getPixel(x, y) >>> 24 >= 128) {
					x0 = Math.min(x0, x);
					y0 = Math.min(y0, y);
					x1 = Math.max(x1, x);
					y1 = Math.max(y1, y);
				}
			}
		}
		if (x1 < 0) {
			return null;
		}
		int w = x1 - x0 + 1;
		int h = y1 - y0 + 1;
		int[] argb = new int[w * h];
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) {
				argb[y * w + x] = image.getPixel(x0 + x, y0 + y);
			}
		}
		return PinArt.fromArgb(w, h, argb);
	}

	/** Every icon a resource pack or the mod ships in its icon folder, as ids. */
	public static List<String> shipped() {
		List<String> ids = new ArrayList<>();
		Minecraft.getInstance().getResourceManager().listResources("textures/waypoint_icons", file -> file.getPath().endsWith(".png"))
			.keySet().forEach(file -> {
				String path = file.getPath().substring(FOLDER.length(), file.getPath().length() - 4);
				ids.add(file.getNamespace() + ":" + path);
			});
		ids.sort(null);
		return ids;
	}
}

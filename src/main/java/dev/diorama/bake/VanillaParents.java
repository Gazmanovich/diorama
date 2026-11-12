package dev.diorama.bake;

import java.util.List;
import java.util.Map;

/**
 * The faces of vanilla's parent block models, which mods' models build on (block/cube_all,
 * block/cube_column, block/cross, ...) but which only the client jar has. Each is written as
 * vanilla's model has it: which way a face looks, the side it's culled on (null for none), its
 * texture name and tint layer. Parents not listed here contribute no faces; a model on one of
 * them keeps its particle texture, or its block's map colour.
 */
final class VanillaParents {
	private VanillaParents() {
	}

	private record Face(String direction, String cull, String texture, int tint) {
	}

	private static List<Face> cube(String up, String down, String north, String south, String east, String west, int tint) {
		return List.of(new Face("up", "up", up, tint), new Face("down", "down", down, tint), new Face("north", "north", north, tint),
			new Face("south", "south", south, tint), new Face("east", "east", east, tint), new Face("west", "west", west, tint));
	}

	private static List<Face> cross(String texture, int tint) {
		// Two crossed planes, no culling: drawn into both colours.
		return List.of(new Face("north", null, texture, tint), new Face("south", null, texture, tint), new Face("east", null, texture, tint), new Face("west", null, texture, tint));
	}

	/** A block lower than a cube (a slab, a carpet): its top isn't culled, its sides are. */
	private static List<Face> low(String top, String bottom, String side) {
		return List.of(new Face("up", null, top, -1), new Face("down", "down", bottom, -1), new Face("north", "north", side, -1),
			new Face("south", "south", side, -1), new Face("east", "east", side, -1), new Face("west", "west", side, -1));
	}

	private static final Map<String, List<Face>> PARENTS = Map.ofEntries(
		Map.entry("block/cube_all", cube("#all", "#all", "#all", "#all", "#all", "#all", -1)),
		Map.entry("block/cube_all_inner_faces", cube("#all", "#all", "#all", "#all", "#all", "#all", -1)),
		Map.entry("block/leaves", cube("#all", "#all", "#all", "#all", "#all", "#all", 0)),
		Map.entry("block/cube", cube("#up", "#down", "#north", "#south", "#east", "#west", -1)),
		Map.entry("block/cube_directional", cube("#up", "#down", "#north", "#south", "#east", "#west", -1)),
		Map.entry("block/cube_mirrored_all", cube("#all", "#all", "#all", "#all", "#all", "#all", -1)),
		Map.entry("block/cube_column", cube("#end", "#end", "#side", "#side", "#side", "#side", -1)),
		Map.entry("block/cube_column_horizontal", cube("#side", "#side", "#end", "#end", "#side", "#side", -1)),
		Map.entry("block/cube_column_mirrored", cube("#end", "#end", "#side", "#side", "#side", "#side", -1)),
		Map.entry("block/cube_bottom_top", cube("#top", "#bottom", "#side", "#side", "#side", "#side", -1)),
		Map.entry("block/cube_top", cube("#top", "#side", "#side", "#side", "#side", "#side", -1)),
		Map.entry("block/orientable", cube("#top", "#top", "#front", "#side", "#side", "#side", -1)),
		Map.entry("block/orientable_with_bottom", cube("#top", "#bottom", "#front", "#side", "#side", "#side", -1)),
		Map.entry("block/orientable_vertical", cube("#front", "#side", "#side", "#side", "#side", "#side", -1)),
		Map.entry("block/template_glazed_terracotta", cube("#pattern", "#pattern", "#pattern", "#pattern", "#pattern", "#pattern", -1)),
		Map.entry("block/template_single_face", cube("#texture", "#texture", "#texture", "#texture", "#texture", "#texture", -1)),
		Map.entry("block/cross", cross("#cross", -1)),
		Map.entry("block/tinted_cross", cross("#cross", 0)),
		Map.entry("block/cross_emissive", cross("#cross", -1)),
		Map.entry("block/flower_pot_cross", cross("#plant", -1)),
		Map.entry("block/crop", cross("#crop", -1)),
		Map.entry("block/stem_growth0", cross("#stem", 0)),
		Map.entry("block/slab", low("#top", "#bottom", "#side")),
		Map.entry("block/slab_top", cube("#top", "#bottom", "#side", "#side", "#side", "#side", -1)),
		Map.entry("block/stairs", low("#top", "#bottom", "#side")),
		Map.entry("block/inner_stairs", low("#top", "#bottom", "#side")),
		Map.entry("block/outer_stairs", low("#top", "#bottom", "#side")),
		Map.entry("block/carpet", low("#wool", "#wool", "#wool")),
		Map.entry("block/pressure_plate_up", low("#texture", "#texture", "#texture")),
		Map.entry("block/pressure_plate_down", low("#texture", "#texture", "#texture")),
		Map.entry("block/button", low("#texture", "#texture", "#texture")),
		Map.entry("block/button_pressed", low("#texture", "#texture", "#texture")),
		Map.entry("block/fence_post", low("#texture", "#texture", "#texture")),
		Map.entry("block/fence_side", low("#texture", "#texture", "#texture")),
		Map.entry("block/fence_inventory", low("#texture", "#texture", "#texture")),
		Map.entry("block/template_fence_gate", low("#texture", "#texture", "#texture")),
		Map.entry("block/template_fence_gate_open", low("#texture", "#texture", "#texture")),
		Map.entry("block/template_fence_gate_wall", low("#texture", "#texture", "#texture")),
		Map.entry("block/template_fence_gate_wall_open", low("#texture", "#texture", "#texture")),
		Map.entry("block/template_wall_post", low("#wall", "#wall", "#wall")),
		Map.entry("block/template_wall_side", low("#wall", "#wall", "#wall")),
		Map.entry("block/template_wall_side_tall", low("#wall", "#wall", "#wall")),
		Map.entry("block/door_bottom_left", low("#bottom", "#bottom", "#bottom")),
		Map.entry("block/door_bottom_right", low("#bottom", "#bottom", "#bottom")),
		Map.entry("block/door_top_left", low("#top", "#top", "#top")),
		Map.entry("block/door_top_right", low("#top", "#top", "#top")),
		Map.entry("block/template_trapdoor_bottom", low("#texture", "#texture", "#texture")),
		Map.entry("block/template_trapdoor_top", low("#texture", "#texture", "#texture")),
		Map.entry("block/template_trapdoor_open", low("#texture", "#texture", "#texture")),
		Map.entry("block/template_orientable_trapdoor_bottom", low("#texture", "#texture", "#texture")),
		Map.entry("block/template_orientable_trapdoor_top", low("#texture", "#texture", "#texture")),
		Map.entry("block/template_orientable_trapdoor_open", low("#texture", "#texture", "#texture")),
		Map.entry("block/pane_post", low("#pane", "#pane", "#edge")),
		Map.entry("block/template_glass_pane_post", low("#pane", "#pane", "#edge")),
		Map.entry("block/template_glass_pane_side", low("#pane", "#pane", "#edge")),
		Map.entry("block/template_torch", cross("#torch", -1)),
		Map.entry("block/template_torch_wall", cross("#torch", -1)),
		Map.entry("block/rail_flat", low("#rail", "#rail", "#rail")),
		Map.entry("block/template_rail_raised_ne", low("#rail", "#rail", "#rail")),
		Map.entry("block/template_rail_raised_sw", low("#rail", "#rail", "#rail")),
		Map.entry("block/thin_block", low("#texture", "#texture", "#texture"))
	);

	/** {@link JarModels}'s faces from a vanilla parent, through {@code add}; false if it isn't listed. */
	static boolean faces(String path, FaceSink out) {
		List<Face> faces = PARENTS.get(path.startsWith("minecraft:") ? path.substring(10) : path);
		if (faces == null) {
			return false;
		}
		for (Face face : faces) {
			out.add(face.direction, face.cull, face.texture, face.tint);
		}
		return true;
	}

	/** Takes one face. */
	interface FaceSink {
		void add(String direction, String cull, String texture, int tint);
	}
}

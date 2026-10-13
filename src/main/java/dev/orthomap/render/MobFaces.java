package dev.orthomap.render;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import org.jspecify.annotations.Nullable;

/**
 * Mob faces for the radar, cut from each mob's own texture, so variants and resource packs show.
 * Only mobs whose face is one clean rectangle of their texture are listed; the rest stay dots.
 * Rectangles are read from the 26.3 models: a head box at texOffs(u, v) with size w x h x d has
 * its front face at (u + d, v + d), w x h.
 */
public final class MobFaces {
	/**
	 * Where a face is in a texture of the given size. {@code hat} is an outer layer drawn over the
	 * face (zombie and skeleton hats, villager hoods); {@code feature} is a nose or snout that
	 * sticks out of the face, drawn at {@code (dx, dy)} in face pixels.
	 */
	record Face(int texW, int texH, int u, int v, int w, int h, @Nullable Piece hat, @Nullable Piece feature) {
		Face(int texW, int texH, int u, int v, int w, int h) {
			this(texW, texH, u, v, w, h, null, null);
		}
	}

	record Piece(int u, int v, int w, int h, int dx, int dy) {
	}

	/** An adult face, and a baby's when babies have their own texture layout (or null for a dot). */
	private record Faces(Face adult, @Nullable Face baby) {
	}

	private static final Map<EntityType<?>, Faces> FACES = new HashMap<>();
	/** Texture per entity, refreshed now and then: working it out builds a render state. */
	private static final Map<Integer, Identifier> TEXTURES = new HashMap<>();
	private static long texturesTick = Long.MIN_VALUE;

	private static final Piece HUMANOID_HAT = new Piece(40, 8, 8, 8, 0, 0);
	private static final Piece BABY_ZOMBIE_HAT = new Piece(41, 9, 6, 6, 0, 0);
	private static final Piece VILLAGER_HAT = new Piece(40, 8, 8, 10, 0, 0);
	/** Villager-style noses stick out from the lower middle of a 10-pixel-tall face. */
	private static final Piece VILLAGER_NOSE = new Piece(26, 2, 2, 3, 3, 7);

	static {
		Face zombie = new Face(64, 64, 8, 8, 8, 8, HUMANOID_HAT, null);
		Face babyZombie = new Face(64, 64, 9, 9, 6, 6, BABY_ZOMBIE_HAT, null);
		for (EntityType<?> type : new EntityType<?>[] {EntityTypes.ZOMBIE, EntityTypes.HUSK, EntityTypes.DROWNED}) {
			FACES.put(type, new Faces(zombie, babyZombie));
		}
		FACES.put(EntityTypes.ZOMBIE_VILLAGER, new Faces(
			new Face(64, 64, 8, 8, 8, 10, VILLAGER_HAT, VILLAGER_NOSE),
			new Face(64, 64, 7, 7, 8, 8, new Piece(7, 38, 8, 8, 0, 0), null)));
		Face skeleton = new Face(64, 32, 8, 8, 8, 8, HUMANOID_HAT, null);
		for (EntityType<?> type : new EntityType<?>[] {EntityTypes.SKELETON, EntityTypes.STRAY, EntityTypes.WITHER_SKELETON, EntityTypes.BOGGED}) {
			FACES.put(type, new Faces(skeleton, null));
		}
		FACES.put(EntityTypes.CREEPER, new Faces(new Face(64, 32, 8, 8, 8, 8), null));
		Face spider = new Face(64, 32, 40, 12, 8, 8);
		FACES.put(EntityTypes.SPIDER, new Faces(spider, null));
		FACES.put(EntityTypes.CAVE_SPIDER, new Faces(spider, null));
		FACES.put(EntityTypes.ENDERMAN, new Faces(new Face(64, 32, 8, 8, 8, 8), null));
		FACES.put(EntityTypes.WITCH, new Faces(new Face(64, 128, 8, 8, 8, 10, null, VILLAGER_NOSE), null));
		FACES.put(EntityTypes.BLAZE, new Faces(new Face(64, 32, 8, 8, 8, 8), null));
		// Piglin snouts: a 4x4 box from the face's middle, rows 4 to 8 of 8.
		Face piglin = new Face(64, 64, 8, 8, 10, 8, null, new Piece(32, 2, 4, 4, 3, 4));
		Face babyPiglin = new Face(64, 64, 7, 7, 9, 6);
		FACES.put(EntityTypes.PIGLIN, new Faces(piglin, babyPiglin));
		FACES.put(EntityTypes.PIGLIN_BRUTE, new Faces(piglin, null));
		FACES.put(EntityTypes.ZOMBIFIED_PIGLIN, new Faces(piglin, babyPiglin));
		Face illager = new Face(64, 64, 8, 8, 8, 10, null, VILLAGER_NOSE);
		for (EntityType<?> type : new EntityType<?>[] {EntityTypes.PILLAGER, EntityTypes.VINDICATOR, EntityTypes.EVOKER}) {
			FACES.put(type, new Faces(illager, null));
		}
		FACES.put(EntityTypes.RAVAGER, new Faces(new Face(128, 128, 16, 16, 16, 20), null));
		FACES.put(EntityTypes.VILLAGER, new Faces(
			new Face(64, 64, 8, 8, 8, 10, VILLAGER_HAT, VILLAGER_NOSE),
			new Face(64, 64, 7, 7, 8, 8, new Piece(7, 37, 8, 8, 0, 0), null)));
		FACES.put(EntityTypes.WANDERING_TRADER, new Faces(new Face(64, 64, 8, 8, 8, 10, VILLAGER_HAT, VILLAGER_NOSE), null));
		FACES.put(EntityTypes.IRON_GOLEM, new Faces(new Face(128, 128, 8, 8, 8, 10, null, VILLAGER_NOSE), null));
		// Cow muzzles sit under the eyes, rows 5 to 8 of 8.
		Faces cow = new Faces(new Face(64, 64, 6, 6, 8, 8, null, new Piece(2, 34, 6, 3, 1, 5)), new Face(64, 64, 5, 23, 6, 6));
		FACES.put(EntityTypes.COW, cow);
		FACES.put(EntityTypes.MOOSHROOM, cow);
		// The pig's snout covers the middle of its face, rows 4 to 7 of 8.
		FACES.put(EntityTypes.PIG, new Faces(new Face(64, 64, 8, 8, 8, 8, null, new Piece(17, 17, 4, 3, 2, 4)), new Face(32, 32, 6, 21, 7, 6)));
		FACES.put(EntityTypes.SHEEP, new Faces(new Face(64, 32, 8, 8, 6, 6), new Face(64, 32, 5, 5, 5, 5)));
		// The chicken's beak is across rows 2 to 4 of its 6-pixel-tall face.
		FACES.put(EntityTypes.CHICKEN, new Faces(new Face(64, 32, 3, 3, 4, 6, null, new Piece(16, 2, 4, 2, 0, 2)), new Face(16, 16, 4, 4, 4, 4)));
		FACES.put(EntityTypes.WOLF, new Faces(new Face(64, 32, 4, 4, 6, 6), new Face(32, 32, 5, 17, 6, 5)));
		FACES.put(EntityTypes.CAT, new Faces(new Face(64, 32, 5, 5, 5, 4), new Face(32, 32, 4, 4, 5, 4)));
		FACES.put(EntityTypes.FOX, new Faces(new Face(48, 32, 7, 11, 8, 6), new Face(32, 32, 5, 5, 6, 5)));
		FACES.put(EntityTypes.RABBIT, new Faces(new Face(64, 64, 5, 21, 5, 5), new Face(32, 32, 4, 4, 5, 4)));
		FACES.put(EntityTypes.POLAR_BEAR, new Faces(new Face(128, 64, 7, 7, 7, 7), new Face(64, 64, 4, 4, 6, 5)));
		FACES.put(EntityTypes.PANDA, new Faces(new Face(64, 64, 9, 15, 13, 10), new Face(64, 64, 5, 5, 7, 6)));
		FACES.put(EntityTypes.ALLAY, new Faces(new Face(32, 32, 5, 5, 5, 5), null));
		FACES.put(EntityTypes.BREEZE, new Faces(new Face(32, 32, 8, 8, 8, 8), null));
		FACES.put(EntityTypes.CREAKING, new Faces(new Face(64, 64, 6, 6, 6, 10), null));
		FACES.put(EntityTypes.DOLPHIN, new Faces(new Face(64, 64, 6, 6, 8, 7), new Face(64, 64, 4, 4, 6, 5)));
		FACES.put(EntityTypes.BEE, new Faces(new Face(64, 64, 10, 10, 7, 7), new Face(32, 32, 5, 5, 4, 4)));
		FACES.put(EntityTypes.AXOLOTL, new Faces(new Face(64, 64, 5, 6, 8, 5), new Face(32, 32, 4, 12, 6, 3)));
		FACES.put(EntityTypes.STRIDER, new Faces(new Face(64, 128, 16, 16, 16, 14), new Face(32, 32, 8, 8, 7, 7)));
	}

	private MobFaces() {
	}

	/** Whether this entity has a face icon; if not the radar draws a dot. */
	public static boolean has(Entity entity) {
		return face(entity) != null;
	}

	/** Whether this entity's face can be drawn now: it has one, and its renderer gives a texture. For tests. */
	public static boolean canDraw(Entity entity) {
		return face(entity) != null && texture(entity) != null;
	}

	private static @Nullable Face face(Entity entity) {
		Faces faces = FACES.get(entity.getType());
		if (faces == null) {
			return null;
		}
		return entity instanceof LivingEntity living && living.isBaby() ? faces.baby() : faces.adult();
	}

	/**
	 * Draws an entity's face centred on (x, y), fitted in a square {@code size} across, keeping
	 * the face's proportions. Returns false if it has no face icon.
	 */
	public static boolean draw(GuiGraphicsExtractor graphics, Entity entity, float x, float y, float size, int color) {
		Face face = face(entity);
		Identifier texture = face == null ? null : texture(entity);
		if (face == null || texture == null) {
			return false;
		}
		float scale = size / Math.max(face.w(), face.h());
		float w = face.w() * scale;
		float h = face.h() * scale;
		graphics.pose().pushMatrix();
		graphics.pose().translate(x - w / 2, y - h / 2);
		graphics.pose().scale(scale);
		blit(graphics, texture, face, face.u(), face.v(), face.w(), face.h(), 0, 0, color);
		if (face.feature() != null) {
			Piece p = face.feature();
			blit(graphics, texture, face, p.u(), p.v(), p.w(), p.h(), p.dx(), p.dy(), color);
		}
		if (face.hat() != null) {
			Piece p = face.hat();
			blit(graphics, texture, face, p.u(), p.v(), p.w(), p.h(), p.dx(), p.dy(), color);
		}
		graphics.pose().popMatrix();
		return true;
	}

	private static void blit(GuiGraphicsExtractor graphics, Identifier texture, Face face, int u, int v, int w, int h, int dx, int dy, int color) {
		graphics.blit(RenderPipelines.GUI_TEXTURED, texture, dx, dy, u, v, w, h, w, h, face.texW(), face.texH(), color);
	}

	/** The texture the game draws this entity with, from its renderer. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static @Nullable Identifier texture(Entity entity) {
		Minecraft mc = Minecraft.getInstance();
		long tick = mc.level == null ? 0 : mc.level.getGameTime();
		if (tick - texturesTick > 40 || tick < texturesTick) {
			// Variants (a sheared sheep, an angry wolf) change now and then; look again every 2 s.
			TEXTURES.clear();
			texturesTick = tick;
		}
		return TEXTURES.computeIfAbsent(entity.getId(), id -> {
			EntityRenderer<?, ?> renderer = mc.getEntityRenderDispatcher().getRenderer(entity);
			if (!(renderer instanceof LivingEntityRenderer living)) {
				return null;
			}
			try {
				LivingEntityRenderState state = (LivingEntityRenderState) ((LivingEntityRenderer) living).createRenderState((LivingEntity) entity, 1.0F);
				return ((LivingEntityRenderer) living).getTextureLocation(state);
			} catch (RuntimeException e) {
				return null;
			}
		});
	}
}

package dev.diorama.waypoint;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;

/**
 * The camera of the last rendered frame, as the world was actually drawn. Projecting through it
 * puts a waypoint's label exactly over its spot, whatever the bobbing, tilt or field of view.
 */
public final class WorldProjection {
	private static final Matrix4f WORLD = new Matrix4f();
	private static @Nullable Vec3 camera;

	private WorldProjection() {
	}

	public static void capture(Matrix4fc projection, Matrix4fc viewRotation, Vec3 cameraPos) {
		WORLD.set(projection).mul(viewRotation);
		camera = cameraPos;
	}

	/**
	 * Screen position of a world point in a {@code width} by {@code height} GUI, or null when
	 * it's behind the camera or no frame has been drawn yet.
	 */
	public static float @Nullable [] project(double x, double y, double z, float width, float height) {
		Vec3 cam = camera;
		if (cam == null) {
			return null;
		}
		Vector4f clip = WORLD.transform(new Vector4f((float) (x - cam.x), (float) (y - cam.y), (float) (z - cam.z), 1));
		if (clip.w <= 0.05F) {
			return null;
		}
		float ndcX = clip.x / clip.w;
		float ndcY = clip.y / clip.w;
		return new float[] {(ndcX + 1) / 2 * width, (1 - ndcY) / 2 * height};
	}

	public static @Nullable Vec3 camera() {
		return camera;
	}
}

package dev.diorama.bake;

/** Running average of the quads that make up one face direction. */
final class FaceColor {
	private float ur, ug, ub, uw;
	private float tr, tg, tb, tw;
	private float coverage;
	private int count;
	/** The first tint layer a quad asked for, or -1. */
	private int tintIndex = -1;

	void add(float[] avg, int tintIndex) {
		float w = avg[3];
		if (w <= 0) {
			return;
		}
		if (tintIndex >= 0) {
			if (this.tintIndex < 0) {
				this.tintIndex = tintIndex;
			}
			this.tr += avg[0] * w;
			this.tg += avg[1] * w;
			this.tb += avg[2] * w;
			this.tw += w;
		} else {
			this.ur += avg[0] * w;
			this.ug += avg[1] * w;
			this.ub += avg[2] * w;
			this.uw += w;
		}
		this.coverage = Math.max(this.coverage, w);
		this.count++;
	}

	boolean isEmpty() {
		return this.count == 0;
	}

	float coverage() {
		return this.coverage;
	}

	int tintIndex() {
		return this.tintIndex;
	}

	int untinted() {
		return this.uw == 0 ? 0 : pack(this.ur / this.uw, this.ug / this.uw, this.ub / this.uw);
	}

	int tinted() {
		return this.tw == 0 ? 0 : pack(this.tr / this.tw, this.tg / this.tw, this.tb / this.tw);
	}

	float tintWeight() {
		float total = this.uw + this.tw;
		return total == 0 ? 0 : this.tw / total;
	}

	static int pack(float r, float g, float b) {
		return (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
	}

	private static int clamp255(float v) {
		return Math.max(0, Math.min(255, Math.round(v * 255)));
	}
}

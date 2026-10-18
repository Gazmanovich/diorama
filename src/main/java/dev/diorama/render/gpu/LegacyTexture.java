//? if <1.21.6 {
/*package dev.diorama.render.gpu;

// A MapTexture that ImmediateCanvas binds by hand before 1.21.6.
interface LegacyTexture extends MapTexture {
	// Sets the texture's sampling and puts it in a shader texture slot for the next draw.
	void bind(int slot, Sampling sampling);
}
*///?}

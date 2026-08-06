package net.irisshaders.iris.gl;

public class IrisLimits {
	/**
	 * The maximum number of color textures that a shader pack can write to and read from in gbuffer and composite
	 * programs.
	 * <p>
	 * It's not recommended to raise this higher than 16 until code for avoiding allocation of unused color textures
	 * is implemented.
	 */
	public static final int MAX_COLOR_BUFFERS = 32;


	public static final boolean VK_CONFORMANCE = false;

	/**
	 * Returns the maximum number of texture units available.
	 * Metal supports at least 16 texture units per shader stage.
	 */
	public static int getMaxTextureUnits() {
		return 16;
	}
}

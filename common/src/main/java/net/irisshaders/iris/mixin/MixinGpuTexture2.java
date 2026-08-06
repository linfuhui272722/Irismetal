package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.textures.GpuTexture;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.mixinterface.GpuTextureInterface;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(GpuTexture.class)
public abstract class MixinGpuTexture2 implements GpuTextureInterface {
	@Override
	public int iris$getGlId() {
		// Metal 模式下返回 -1，表示没有 GL ID
		if (IrisRenderSystem.isUsingMetalBackend()) {
			return -1;
		}
		throw new AssertionError("iris$getGlId should be implemented by MixinGpuTexture");
	}

	@Override
	public void iris$markMipmapNonLinear() {
		throw new AssertionError("iris$markMipmapNonLinear should be implemented by MixinGpuTexture");
	}
}

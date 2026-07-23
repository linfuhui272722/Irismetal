package net.irisshaders.iris.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.metal.IrisMetalDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Metal-only RenderSystem mixin。
 *
 * <p>当 metallum 将后端切换为 Metal 时，{@code RenderSystem.getDevice()} 返回的
 * 设备的 {@code backendName()} 为 "Metal"。本 mixin 在后端初始化完成后触发
 * Iris Metal 设备的初始化。</p>
 *
 * <p>本 mixin 仅在 {@code IrisMixinPlugin.usingMetal == true} 时应用
 * （由 MetalOnly_ 前缀和 IrisMixinPlugin.shouldApplyMixin 保证）。</p>
 */
@Mixin(RenderSystem.class)
public class MetalOnly_RenderSystemMixin {

	@Inject(method = "initBackend", at = @At("RETURN"))
	private static void iris_metal$onBackendInit(CallbackInfo ci) {
		try {
			String backendName = RenderSystem.getDevice().getDeviceInfo().backendName();
			if ("Metal".equals(backendName)) {
				IrisMetalDevice.get(); // 触发 Metal 设备初始化
			}
		} catch (Throwable ignored) {
			// Metal 设备不可用，保持回退
		}
	}
}

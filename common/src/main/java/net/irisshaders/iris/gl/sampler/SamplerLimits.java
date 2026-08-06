package net.irisshaders.iris.gl.sampler;

import com.mojang.blaze3d.opengl.GlStateManager;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.mixin.IrisMixinPlugin;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL45C;

public class SamplerLimits {
        private static SamplerLimits instance;
        private final int maxTextureUnits;
        private final int maxDrawBuffers;
        private final int maxShaderStorageUnits;

        private SamplerLimits() {
                // Metal 模式下使用默认值，因为此时没有 GL 上下文
                if (IrisMixinPlugin.usingMetal) {
                        this.maxTextureUnits = 16;  // Metal 最低要求
                        this.maxDrawBuffers = 8;
                        this.maxShaderStorageUnits = 0;  // Metal SSBO 需要额外支持
                } else {
                        this.maxTextureUnits = GlStateManager._getInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS);
                        this.maxDrawBuffers = GlStateManager._getInteger(GL20C.GL_MAX_DRAW_BUFFERS);
                        this.maxShaderStorageUnits = IrisRenderSystem.supportsSSBO() ? GlStateManager._getInteger(GL45C.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS) : 0;
                }
        }

        public static SamplerLimits get() {
                if (instance == null) {
                        instance = new SamplerLimits();
                }

                return instance;
        }

        public int getMaxTextureUnits() {
                return maxTextureUnits;
        }

        public int getMaxDrawBuffers() {
                return maxDrawBuffers;
        }

        public int getMaxShaderStorageUnits() {
                return maxShaderStorageUnits;
        }
}

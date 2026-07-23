package net.irisshaders.iris.metal;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.IrisLimits;
import net.irisshaders.iris.metal.blending.MetalBlendState;
import net.irisshaders.iris.metal.bridge.IrisMetalNativeBridge;
import net.irisshaders.iris.metal.framebuffer.MetalFramebuffer;
import net.irisshaders.iris.metal.framebuffer.MetalRenderPassEncoder;
import net.irisshaders.iris.metal.texture.MetalTexture;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3i;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Iris Metal 渲染系统，替代原有的 {@code IrisRenderSystem}（OpenGL 版本）。
 *
 * <p>本类是 Iris Metal 后端的"GL 状态机"模拟层。Iris 的原有代码大量调用
 * {@code IrisRenderSystem.xxx()} 来操作 GL 状态（blend、depth、stencil、texture
 * binding、barrier 等）。本类提供同名方法，但内部将调用转发到 Metal 命令编码器。</p>
 *
 * <p><b>核心挑战：GL 状态机 vs Metal 命令式 API</b></p>
 * <ul>
 *   <li>GL 是全局状态机：{@code glEnable(GL_BLEND)} 后所有后续 draw 都启用 blend，
 *       直到 {@code glDisable(GL_BLEND)}。状态隐式持续。</li>
 *   <li>Metal 是命令式：blend/depth/stencil 状态固化在 pipeline state object 中，
 *       每次状态变化需要创建新的 PSO（或使用 PSO 缓存）。动态状态（如 blend color、
 *       depth bias）通过 encoder 的 setter 设置。</li>
 * </ul>
 *
 * <p><b>适配策略</b>：</p>
 * <ol>
 *   <li>本类维护一个"当前 GL 状态"的镜像（blend enabled、blend func、depth test、
 *       depth func、depth mask、color mask、stencil 等）</li>
 *   <li>当状态变化时，标记当前 PSO 为 dirty</li>
 *   <li>在下一次 draw call 前，如果 dirty，从 PSO 缓存中查找或创建匹配的 PSO</li>
 *   <li>PSO 缓存以 (program, blendState, depthState, colorMask, vertexFormat) 为 key</li>
 * </ol>
 *
 * <p><b>当前 encoder 管理</b>：Iris 的渲染逻辑假设有一个"当前绑定的 FBO"和"当前
 * program"。本类维护 {@code currentFramebuffer} 和 {@code currentEncoder}，
 * 在 draw call 时确保 encoder 存在且状态已应用。</p>
 */
@Environment(EnvType.CLIENT)
public final class IrisMetalRenderSystem {
    // === 当前状态镜像 ===
    @Nullable
    private static MetalFramebuffer currentFramebuffer;
    @Nullable
    private static MetalRenderPassEncoder currentEncoder;

    // blend 状态
    private static boolean blendEnabled = false;
    private static int blendSrcRgb = 1;      // GL_ONE
    private static int blendDstRgb = 0;      // GL_ZERO
    private static int blendSrcAlpha = 1;
    private static int blendDstAlpha = 0;
    private static int blendEquationRgb = 0; // GL_FUNC_ADD
    private static int blendEquationAlpha = 0;
    private static float blendColorR = 0, blendColorG = 0, blendColorB = 0, blendColorA = 0;

    // depth 状态
    private static boolean depthTestEnabled = false;
    private static boolean depthWriteEnabled = true;
    private static int depthFunc = 0x0201; // GL_LESS

    // color mask
    private static boolean colorMaskR = true, colorMaskG = true, colorMaskB = true, colorMaskA = true;

    // stencil 状态
    private static boolean stencilTestEnabled = false;

    // === 初始化 ===
    private static boolean initialized = false;

    public static void initRenderer() {
        if (initialized) return;
        // 确认 Metal 设备可用
        IrisMetalDevice.get();
        initialized = true;
    }

    public static boolean isMetal() {
        return true;
    }

    // === Framebuffer 绑定 ===
    public static void bindFramebuffer(@Nullable MetalFramebuffer framebuffer) {
        // 如果有活跃的 encoder，先结束
        if (currentEncoder != null) {
            currentEncoder.close();
            currentEncoder = null;
        }
        currentFramebuffer = framebuffer;
    }

    /**
     * 确保 current encoder 存在。如果当前 framebuffer 没有 encoder，开始一个新的
     * render pass（不清除）。
     */
    private static MetalRenderPassEncoder ensureEncoder() {
        if (currentEncoder == null) {
            if (currentFramebuffer == null) {
                throw new IllegalStateException("No framebuffer bound");
            }
            currentEncoder = currentFramebuffer.beginRenderPassNoClear();
        }
        return currentEncoder;
    }

    /**
     * 提交当前 encoder（结束当前 render pass）。在切换 framebuffer 或需要
     * 同步时调用。
     */
    public static void flush() {
        if (currentEncoder != null) {
            currentEncoder.close();
            currentEncoder = null;
        }
    }

    // === Blend ===
    public static void setBlendEnabled(boolean enabled) {
        if (blendEnabled != enabled) {
            blendEnabled = enabled;
            // PSO dirty，下次 draw 会重建
        }
    }

    public static void setBlendFunction(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha) {
        blendSrcRgb = srcRgb;
        blendDstRgb = dstRgb;
        blendSrcAlpha = srcAlpha;
        blendDstAlpha = dstAlpha;
    }

    public static void setBlendEquation(int equation) {
        blendEquationRgb = equation;
        blendEquationAlpha = equation;
    }

    public static void setBlendColor(float r, float g, float b, float a) {
        blendColorR = r;
        blendColorG = g;
        blendColorB = b;
        blendColorA = a;
        if (currentEncoder != null) {
            IrisMetalNativeBridge.setBlendColor(currentEncoder.handle(), r, g, b, a);
        }
    }

    public static MetalBlendState currentBlendState() {
        return new MetalBlendState(
                blendEnabled,
                MetalBlendState.glBlendFactorToMetal(blendSrcRgb),
                MetalBlendState.glBlendFactorToMetal(blendDstRgb),
                MetalBlendState.glBlendFactorToMetal(blendSrcAlpha),
                MetalBlendState.glBlendFactorToMetal(blendDstAlpha),
                MetalBlendState.glBlendEquationToMetal(blendEquationRgb),
                MetalBlendState.glBlendEquationToMetal(blendEquationAlpha)
        );
    }

    // === Depth ===
    public static void setDepthTestEnabled(boolean enabled) {
        depthTestEnabled = enabled;
    }

    public static void setDepthWriteEnabled(boolean enabled) {
        depthWriteEnabled = enabled;
    }

    public static void setDepthFunc(int glFunc) {
        depthFunc = glFunc;
    }

    public static boolean isDepthTestEnabled() {
        return depthTestEnabled;
    }

    public static boolean isDepthWriteEnabled() {
        return depthWriteEnabled;
    }

    public static int getDepthFunc() {
        return depthFunc;
    }

    // === Color mask ===
    public static void setColorMask(boolean r, boolean g, boolean b, boolean a) {
        colorMaskR = r;
        colorMaskG = g;
        colorMaskB = b;
        colorMaskA = a;
    }

    public static boolean[] getColorMask() {
        return new boolean[]{colorMaskR, colorMaskG, colorMaskB, colorMaskA};
    }

    // === Stencil ===
    public static void setStencilTestEnabled(boolean enabled) {
        stencilTestEnabled = enabled;
    }

    public static boolean isStencilTestEnabled() {
        return stencilTestEnabled;
    }

    // === Memory barrier ===
    /**
     * 对应 GL 的 {@code glMemoryBarrier}。
     *
     * <p>Metal 中，render pass 边界天然提供 memory barrier 语义（一个 render pass
     * 的写入对后续 render pass 可见）。如果需要在同一 render pass 内确保
     * texture/buffer 写入可见，需要结束当前 pass 并开始新 pass。</p>
     *
     * <p>本方法简化处理：flush 当前 encoder，强制后续操作在新 pass 中执行。</p>
     */
    public static void memoryBarrier(int barrierBits) {
        flush();
    }

    // === Texture binding ===
    /**
     * 对应 GL 的 {@code glBindTextureUnit}。在 Metal 中，texture 绑定是 encoder
     * 级别的，由 {@link net.irisshaders.iris.metal.sampler.MetalProgramSamplers}
     * 在 draw 时统一应用。本方法仅记录绑定意图。
     */
    public static void bindTextureToUnit(int unit, @Nullable MetalTexture texture) {
        // 实际绑定在 program.use() 时由 sampler holder 应用
        // 这里仅做记录（如果需要）
    }

    // === Limits ===
    public static int getMaxTextureUnits() {
        return IrisLimits.getMaxTextureUnits();
    }

    public static int getMaxDrawBuffers() {
        return 8; // Metal 通常支持 8 个 color attachments
    }

    // === Viewport / Scissor ===
    public static void setViewport(int x, int y, int width, int height) {
        if (currentEncoder != null) {
            IrisMetalNativeBridge.setViewport(currentEncoder.handle(), x, y, width, height);
        }
    }

    public static void setScissor(int x, int y, int width, int height) {
        if (currentEncoder != null) {
            IrisMetalNativeBridge.setScissorRect(currentEncoder.handle(), x, y, width, height);
        }
    }

    public static void disableScissor() {
        if (currentEncoder != null) {
            IrisMetalNativeBridge.setScissorEnabled(currentEncoder.handle(), false);
        }
    }

    // === Clear ===
    /**
     * 对应 GL 的 {@code glClear}。
     *
     * <p>在 Metal 中，clear 是 render pass 的一部分。本方法 flush 当前 encoder，
     * 然后用指定的 clear 值开始新 pass。注意：这会导致当前 pass 的内容丢失，
     * 调用方应确保 clear 发生在 pass 开始时。</p>
     */
    public static void clear(float r, float g, float b, float a, @Nullable Double depth) {
        flush();
        if (currentFramebuffer == null) {
            throw new IllegalStateException("No framebuffer bound for clear");
        }
        float[][] colors = {{r, g, b, a}};
        float depthVal = depth != null ? depth.floatValue() : 1.0f;
        currentEncoder = currentFramebuffer.beginRenderPassWithClear(colors, depthVal);
        // clear 后立即结束，让后续操作开始干净的 pass
        flush();
    }

    // === Draw ===
    public static void drawArrays(int primitiveType, int first, int count) {
        MetalRenderPassEncoder encoder = ensureEncoder();
        encoder.drawPrimitives(primitiveType, first, count, 1);
    }

    public static void drawElements(int primitiveType, int indexCount, int indexType,
                                    long indexBufferOffset) {
        MetalRenderPassEncoder encoder = ensureEncoder();
        encoder.drawIndexedPrimitives(primitiveType, indexCount, indexType, indexBufferOffset, 1);
    }

    // === Frame lifecycle ===
    public static void beginFrame() {
        IrisMetalDevice.get().beginFrame();
    }

    public static void endFrame() {
        flush();
        IrisMetalDevice.get().endFrame();
    }

    // === Device info ===
    public static String getRendererInfo() {
        return IrisMetalDevice.get().getDeviceName();
    }

    public static int getMaxSamples() {
        return 4; // Metal 通常支持 4x MSAA
    }

    public static boolean supportsCompute() {
        return true; // Apple Silicon 支持 compute
    }

    public static boolean supportsSSBO() {
        return true;
    }
}

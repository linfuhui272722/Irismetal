package net.irisshaders.iris.metal.framebuffer;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.metal.IrisMetalDevice;
import net.irisshaders.iris.metal.bridge.IrisMetalNativeBridge;
import net.irisshaders.iris.metal.texture.MetalTexture;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/**
 * Metal 帧缓冲区实现，对应 Iris 原有的 {@code GlFramebuffer}。
 *
 * <p>本类管理一组颜色附件和可选的深度/模板附件，用于光影的渲染目标。</p>
 *
 * <p><b>与 GL FBO 的关键差异</b>：</p>
 * <ul>
 *   <li>GL 的 FBO 是一个可变容器，可以随时 {@code glFramebufferTexture2D} 添加/移除附件。
 *       Metal 没有独立的 FBO 对象概念，渲染目标在创建 {@code MTLRenderPassDescriptor}
 *       时通过设置 {@code colorAttachments[i].texture} 指定。</li>
 *   <li>因此本类不持有 Metal 原生对象，而是维护附件列表，在 {@link #beginRenderPass()}
 *       时动态创建 {@code MTLRenderPassDescriptor} 并编码到命令缓冲区。</li>
 *   <li>GL 的 {@code glDrawBuffers} 控制哪些颜色附件可写。Metal 中所有已设置 texture 的
 *       color attachment 默认都可写，通过 {@code MTLRenderPassColorAttachmentDescriptor.blendLevel}
 *       和 pipeline state 的 color attachment 写掩码控制。</li>
 * </ul>
 *
 * <p><b>清除策略</b>：GL 的 {@code glClear} 在 FBO 绑定后调用。Metal 中清除操作是
 * render pass 的一部分，在 {@code MTLRenderPassDescriptor} 中通过设置
 * {@code loadAction = MTLLoadActionClear} 和 {@code clearColor} 实现。本类缓存清除
 * 请求，在 {@link #beginRenderPass()} 时应用。</p>
 */
@Environment(EnvType.CLIENT)
public final class MetalFramebuffer implements AutoCloseable {
    private final List<ColorAttachment> colorAttachments;
    @Nullable
    private MetalTexture depthAttachment;
    @Nullable
    private MetalTexture stencilAttachment;
    private boolean destroyed;

    public MetalFramebuffer() {
        this.colorAttachments = new ArrayList<>();
        this.depthAttachment = null;
        this.stencilAttachment = null;
    }

    /**
     * 添加颜色附件。
     *
     * @param index    附件索引（0-7，对应 GL 的 GL_COLOR_ATTACHMENT0-7）
     * @param texture  纹理
     * @param mipLevel mip 层
     * @param layer    3D/Cube 层（2D 纹理传 0）
     */
    public void addColorAttachment(int index, MetalTexture texture, int mipLevel, int layer) {
        ensureNotDestroyed();
        while (colorAttachments.size() <= index) {
            colorAttachments.add(null);
        }
        colorAttachments.set(index, new ColorAttachment(texture, mipLevel, layer));
    }

    public void removeColorAttachment(int index) {
        ensureNotDestroyed();
        if (index < colorAttachments.size()) {
            colorAttachments.set(index, null);
        }
    }

    public void addDepthAttachment(MetalTexture texture) {
        ensureNotDestroyed();
        this.depthAttachment = texture;
    }

    public void addStencilAttachment(MetalTexture texture) {
        ensureNotDestroyed();
        this.stencilAttachment = texture;
    }

    public boolean hasDepthAttachment() {
        return depthAttachment != null;
    }

    public int getColorAttachmentCount() {
        int count = 0;
        for (ColorAttachment a : colorAttachments) {
            if (a != null) count++;
        }
        return count;
    }

    @Nullable
    public MetalTexture getColorAttachment(int index) {
        if (index >= colorAttachments.size()) return null;
        ColorAttachment a = colorAttachments.get(index);
        return a != null ? a.texture : null;
    }

    @Nullable
    public MetalTexture getDepthAttachment() {
        return depthAttachment;
    }

    /**
     * 开始一个渲染 pass。返回的 {@link MetalRenderPassEncoder} 用于编码绘制命令。
     *
     * <p>本方法会：</p>
     * <ol>
     *   <li>创建 {@code MTLRenderPassDescriptor}</li>
     *   <li>为每个颜色附件设置 texture、loadAction、storeAction、clearColor</li>
     *   <li>为深度附件设置 texture、loadAction、storeAction、clearDepth</li>
     *   <li>从当前命令缓冲区创建 {@code MTLRenderCommandEncoder}</li>
     * </ol>
     *
     * @param clearColors 每个颜色附件的清除颜色（null 表示不清除，保留内容）
     * @param clearDepth  深度清除值（null 表示不清除）
     * @param clearStencil 模板清除值（-1 表示不清除）
     * @return 渲染命令编码器
     */
    public MetalRenderPassEncoder beginRenderPass(
            @Nullable float[][] clearColors,
            @Nullable Float clearDepth,
            int clearStencil) {
        ensureNotDestroyed();
        MemorySegment buffer = IrisMetalDevice.get().currentCommandBuffer();
        MemorySegment passDescriptor = IrisMetalNativeBridge.createRenderPassDescriptor();

        // 设置颜色附件
        for (int i = 0; i < colorAttachments.size(); i++) {
            ColorAttachment att = colorAttachments.get(i);
            if (att == null) continue;

            float[] clearColor = (clearColors != null && i < clearColors.length) ? clearColors[i] : null;
            IrisMetalNativeBridge.setRenderPassColorAttachment(
                    passDescriptor, i, att.texture.handle(), att.mipLevel, att.layer,
                    clearColor != null, clearColor, true);
        }

        // 设置深度附件
        if (depthAttachment != null) {
            IrisMetalNativeBridge.setRenderPassDepthAttachment(
                    passDescriptor, depthAttachment.handle(),
                    clearDepth != null, clearDepth != null ? clearDepth : 0f, true);
        }

        // 设置模板附件
        if (stencilAttachment != null) {
            IrisMetalNativeBridge.setRenderPassStencilAttachment(
                    passDescriptor, stencilAttachment.handle(),
                    clearStencil >= 0, clearStencil, true);
        }

        MemorySegment encoder = IrisMetalNativeBridge.makeRenderCommandEncoder(buffer, passDescriptor);
        IrisMetalNativeBridge.releaseObject(passDescriptor);

        return new MetalRenderPassEncoder(encoder);
    }

    /**
     * 简便方法：不清除任何附件，开始渲染 pass（保留现有内容）。
     */
    public MetalRenderPassEncoder beginRenderPassNoClear() {
        return beginRenderPass(null, null, -1);
    }

    /**
     * 简便方法：清除所有颜色附件和深度附件。
     */
    public MetalRenderPassEncoder beginRenderPassWithClear(float[][] clearColors, float clearDepth) {
        return beginRenderPass(clearColors, clearDepth, -1);
    }

    @Override
    public void close() {
        if (!destroyed) {
            destroyed = true;
            colorAttachments.clear();
            depthAttachment = null;
            stencilAttachment = null;
        }
    }

    private void ensureNotDestroyed() {
        if (destroyed) {
            throw new IllegalStateException("Framebuffer already destroyed");
        }
    }

    private static final class ColorAttachment {
        final MetalTexture texture;
        final int mipLevel;
        final int layer;

        ColorAttachment(MetalTexture texture, int mipLevel, int layer) {
            this.texture = texture;
            this.mipLevel = mipLevel;
            this.layer = layer;
        }
    }
}

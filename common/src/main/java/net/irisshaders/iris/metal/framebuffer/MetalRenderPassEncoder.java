package net.irisshaders.iris.metal.framebuffer;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.metal.bridge.IrisMetalNativeBridge;
import net.irisshaders.iris.metal.program.MetalCompiledProgram;
import net.irisshaders.iris.metal.buffer.MetalBuffer;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

/**
 * Metal 渲染命令编码器封装，对应 GL 的绘制命令 + 状态设置。
 *
 * <p>本类封装一个 {@code MTLRenderCommandEncoder}，提供与 Iris 原有 GL 调用
 * 对应的方法。由于 Metal 的状态模型与 GL 不同（Metal 使用 pipeline state object
 * + vertex descriptor + bind groups，GL 使用即时状态机），本类内部维护当前
 * pipeline state、vertex/index buffer、bind group 等状态。</p>
 *
 * <p><b>状态管理</b>：</p>
 * <ul>
 *   <li>{@link #setPipeline(MetalCompiledProgram)} 设置渲染管线状态（shader + blend + depth）</li>
 *   <li>{@link #setVertexBuffer}/{@link #setIndexBuffer} 设置顶点/索引缓冲区</li>
 *   <li>{@link #setBindGroup} 设置 uniform/texture/sampler 绑定组</li>
 *   <li>{@link #setViewport}/{@link #setScissor} 设置视口/裁剪</li>
 *   <li>{@link #drawPrimitives}/{@link #drawIndexedPrimitives} 绘制</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class MetalRenderPassEncoder implements AutoCloseable {
    private final MemorySegment handle;
    private boolean closed;

    MetalRenderPassEncoder(MemorySegment handle) {
        this.handle = handle;
    }

    public MemorySegment handle() {
        return handle;
    }

    /**
     * 设置渲染管线状态。
     */
    public void setPipeline(MetalCompiledProgram pipeline) {
        ensureOpen();
        IrisMetalNativeBridge.setRenderPipelineState(handle, pipeline.pipelineStateHandle());
    }

    /**
     * 设置视口。
     */
    public void setViewport(int x, int y, int width, int height, double near, double far) {
        ensureOpen();
        IrisMetalNativeBridge.setViewport(handle, x, y, width, height, (float) near, (float) far);
    }

    /**
     * 设置裁剪矩形。
     */
    public void setScissorRect(int x, int y, int width, int height) {
        ensureOpen();
        IrisMetalNativeBridge.setScissorRect(handle, x, y, width, height);
    }

    /**
     * 设置深度测试/写入状态。
     */
    public void setDepthState(boolean depthTest, boolean depthWrite, int depthCompareFunction) {
        ensureOpen();
        IrisMetalNativeBridge.setDepthState(handle, depthTest ? 1 : 0, depthWrite ? 1 : 0, depthCompareFunction);
    }

    /**
     * 设置模板状态。
     */
    public void setStencilState(boolean stencilTest, int stencilCompareFunction,
                                int stencilReference, int stencilReadMask, int stencilWriteMask,
                                int stencilFailureOperation, int depthFailureOperation,
                                int depthStencilPassOperation) {
        ensureOpen();
        IrisMetalNativeBridge.setStencilState(handle, stencilTest ? 1 : 0, stencilCompareFunction,
                stencilReference, stencilReadMask, stencilWriteMask,
                stencilFailureOperation, depthFailureOperation, depthStencilPassOperation);
    }

    /**
     * 设置颜色写入掩码。
     */
    public void setColorWriteMask(int attachmentIndex, boolean red, boolean green, boolean blue, boolean alpha) {
        ensureOpen();
        int mask = (red ? 1 : 0) | (green ? 2 : 0) | (blue ? 4 : 0) | (alpha ? 8 : 0);
        IrisMetalNativeBridge.setColorWriteMask(handle, attachmentIndex, mask);
    }

    /**
     * 设置顶点缓冲区。
     *
     * @param index  绑定点（对应 GL 的 attribute index）
     * @param buffer 缓冲区
     * @param offset 字节偏移
     */
    public void setVertexBuffer(int index, MetalBuffer buffer, long offset) {
        ensureOpen();
        IrisMetalNativeBridge.setVertexBuffer(handle, index, buffer.handle(), offset);
    }

    /**
     * 设置索引缓冲区。
     */
    public void setIndexBuffer(MetalBuffer buffer, int indexType) {
        ensureOpen();
        IrisMetalNativeBridge.setIndexBuffer(handle, buffer.handle(), indexType);
    }

    /**
     * 绑定纹理到 vertex shader 的 texture slot。
     */
    public void setVertexTexture(int slot, MemorySegment texture) {
        ensureOpen();
        IrisMetalNativeBridge.setVertexTexture(handle, slot, texture);
    }

    /**
     * 绑定纹理到 fragment shader 的 texture slot。
     */
    public void setFragmentTexture(int slot, MemorySegment texture) {
        ensureOpen();
        IrisMetalNativeBridge.setFragmentTexture(handle, slot, texture);
    }

    /**
     * 绑定 sampler 到 vertex shader 的 sampler slot。
     */
    public void setVertexSamplerState(int slot, MemorySegment sampler) {
        ensureOpen();
        IrisMetalNativeBridge.setVertexSamplerState(handle, slot, sampler);
    }

    /**
     * 绑定 sampler 到 fragment shader 的 sampler slot。
     */
    public void setFragmentSamplerState(int slot, MemorySegment sampler) {
        ensureOpen();
        IrisMetalNativeBridge.setFragmentSamplerState(handle, slot, sampler);
    }

    /**
     * 绑定 uniform buffer 到 vertex shader。
     */
    public void setVertexBytes(int slot, byte[] data) {
        ensureOpen();
        IrisMetalNativeBridge.setVertexBytes(handle, slot, data, data.length);
    }

    /**
     * 绑定 uniform buffer 到 fragment shader。
     */
    public void setFragmentBytes(int slot, byte[] data) {
        ensureOpen();
        IrisMetalNativeBridge.setFragmentBytes(handle, slot, data, data.length);
    }

    /**
     * 绑定 uniform buffer 到 vertex shader（通过 buffer 对象）。
     */
    public void setVertexBufferObject(int slot, MetalBuffer buffer, long offset) {
        ensureOpen();
        IrisMetalNativeBridge.setVertexBufferObject(handle, slot, buffer.handle(), offset);
    }

    /**
     * 绑定 uniform buffer 到 fragment shader（通过 buffer 对象）。
     */
    public void setFragmentBufferObject(int slot, MetalBuffer buffer, long offset) {
        ensureOpen();
        IrisMetalNativeBridge.setFragmentBufferObject(handle, slot, buffer.handle(), offset);
    }

    /**
     * 绘制非索引图元。
     *
     * @param primitiveType 图元类型（0=Triangle, 1=TriangleStrip, 2=Line, 3=LineStrip, 4=Point）
     * @param vertexStart   起始顶点
     * @param vertexCount   顶点数量
     * @param instanceCount 实例数量
     */
    public void drawPrimitives(int primitiveType, int vertexStart, int vertexCount, int instanceCount) {
        ensureOpen();
        IrisMetalNativeBridge.drawPrimitives(handle, primitiveType, vertexStart, vertexCount, instanceCount);
    }

    /**
     * 绘制索引图元。
     *
     * @param primitiveType  图元类型
     * @param indexCount     索引数量
     * @param indexType      索引类型（0=UInt16, 1=UInt32）
     * @param indexBufferOffset 索引缓冲区偏移
     * @param instanceCount  实例数量
     */
    public void drawIndexedPrimitives(int primitiveType, int indexCount, int indexType,
                                      long indexBufferOffset, int instanceCount) {
        ensureOpen();
        IrisMetalNativeBridge.drawIndexedPrimitives(handle, primitiveType, indexCount, indexType,
                indexBufferOffset, instanceCount);
    }

    /**
     * 结束编码。必须在使用完毕后调用。
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            IrisMetalNativeBridge.endEncoding(handle);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Render pass encoder already closed");
        }
    }
}

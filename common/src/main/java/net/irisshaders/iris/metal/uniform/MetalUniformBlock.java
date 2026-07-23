package net.irisshaders.iris.metal.uniform;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.metal.bridge.IrisMetalNativeBridge;
import net.irisshaders.iris.metal.buffer.MetalBuffer;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Metal uniform block 管理，对应 Iris 原有的 {@code ProgramUniforms}。
 *
 * <p><b>GL uniform vs Metal uniform 的根本差异</b>：</p>
 * <ul>
 *   <li>GL：每个 uniform 单独通过 {@code glUniform*} 设置，驱动自动管理 uniform
 *       内存的打包和上传。</li>
 *   <li>Metal：uniform 必须打包到 buffer 中，通过 {@code setVertexBytes} /
 *       {@code setFragmentBytes}（小数据）或 {@code setVertexBuffer} /
 *       {@code setFragmentBuffer}（大数据）上传。shader 中通过
 *       {@code [[buffer(N)]]} 访问。</li>
 * </ul>
 *
 * <p>本类负责：</p>
 * <ol>
 *   <li>在 pipeline 创建时，通过 SPIRV-Cross 反射获取所有 uniform 的名称、类型、
 *       偏移量、大小，构建 uniform 布局表</li>
 *   <li>运行时，将 Iris 设置的 uniform 值写入一个 CPU 端的 {@link ByteBuffer}</li>
 *   <li>在 draw call 前，将 buffer 上传到 GPU 并绑定到正确的 buffer slot</li>
 * </ol>
 *
 * <p><b>Buffer slot 约定</b>（与 native bridge 和 MSL 输出协调）：</p>
 * <ul>
 *   <li>Slot 0：vertex uniform buffer（{@code IrisUniforms}）</li>
 *   <li>Slot 1：fragment uniform buffer（{@code IrisUniforms}，如果与 vertex 不同）</li>
 *   <li>Slot 2-7：sampler/textures</li>
 *   <li>Slot 8+：SSBO / image</li>
 * </ul>
 *
 * <p><b>性能优化</b>：对于小于 4KB 的 uniform 数据，使用
 * {@code setVertexBytes}/{@code setFragmentBytes} 直接内联，避免创建 buffer。
 * Iris 的 uniform 通常在几百字节到几 KB，适合用这种方式。</p>
 */
@Environment(EnvType.CLIENT)
public final class MetalUniformBlock implements AutoCloseable {
    private static final int MAX_INLINE_BYTES = 4096;

    private final Map<String, UniformEntry> uniforms;
    private final ByteBuffer dataBuffer;
    private final int totalSize;
    private boolean dirty;
    private boolean destroyed;

    private MetalUniformBlock(Map<String, UniformEntry> uniforms, int totalSize) {
        this.uniforms = uniforms;
        this.totalSize = totalSize;
        this.dataBuffer = ByteBuffer.allocateDirect(totalSize).order(java.nio.ByteOrder.nativeOrder());
        this.dirty = true;
    }

    /**
     * 从 pipeline state 反射 uniform 布局。
     *
     * <p>实际实现中，这需要在 GLSL→MSL 转换阶段（SPIRV-Cross）保存反射信息，
     * 因为 Metal 不提供运行时反射 API（除非用 {@code MTLPipelineReflection}，
     * 但它只在 pipeline 创建时可用且 API 有限）。</p>
     *
     * <p>本方法假设 SPIRV-Cross 阶段已经将 uniform 信息通过 native bridge 传出，
     * 存储在 pipeline state 的关联数据中。</p>
     */
    public static MetalUniformBlock reflectFromPipeline(
            MemorySegment pipelineState, MemorySegment vertexFunction, MemorySegment fragmentFunction) {
        // 通过 native bridge 获取反射信息
        // native 层在编译 MSL 时已经通过 SPIRV-Cross 反射了 uniform
        String[] uniformNames = IrisMetalNativeBridge.getPipelineUniformNames(pipelineState);
        int[] uniformOffsets = IrisMetalNativeBridge.getPipelineUniformOffsets(pipelineState);
        int[] uniformSizes = IrisMetalNativeBridge.getPipelineUniformSizes(pipelineState);
        int[] uniformStages = IrisMetalNativeBridge.getPipelineUniformStages(pipelineState);

        if (uniformNames == null || uniformNames.length == 0) {
            return new MetalUniformBlock(new HashMap<>(), 0);
        }

        Map<String, UniformEntry> map = new HashMap<>();
        int maxSize = 0;
        for (int i = 0; i < uniformNames.length; i++) {
            map.put(uniformNames[i], new UniformEntry(
                    uniformNames[i], uniformOffsets[i], uniformSizes[i], uniformStages[i]));
            maxSize = Math.max(maxSize, uniformOffsets[i] + uniformSizes[i]);
        }

        // 对齐到 16 字节（Metal buffer 要求）
        maxSize = (maxSize + 15) & ~15;

        return new MetalUniformBlock(map, maxSize);
    }

    /**
     * 设置一个 uniform 的值。
     *
     * @param name  uniform 名称
     * @param value 值的字节表示（已按 uniform 类型打包）
     */
    public void setUniform(String name, ByteBuffer value) {
        UniformEntry entry = uniforms.get(name);
        if (entry == null) {
            return; // 静默忽略不存在的 uniform（与 GL 行为一致）
        }
        int oldPos = dataBuffer.position();
        dataBuffer.position(entry.offset);
        value.rewind();
        int len = Math.min(value.remaining(), entry.size);
        dataBuffer.put(value.array(), value.arrayOffset() + value.position(), len);
        dataBuffer.position(oldPos);
        dirty = true;
    }

    public boolean hasUniform(String name) {
        return uniforms.containsKey(name);
    }

    public int uniformSize(String name) {
        UniformEntry e = uniforms.get(name);
        return e != null ? e.size : 0;
    }

    /**
     * 将 uniform 数据绑定到 render encoder。
     *
     * @param encoder render pass encoder 句柄
     */
    public void bind(MemorySegment encoder) {
        if (totalSize == 0) {
            return;
        }
        if (totalSize <= MAX_INLINE_BYTES) {
            // 小数据：直接内联
            IrisMetalNativeBridge.setVertexBytes(encoder, 0, dataBuffer, totalSize);
            IrisMetalNativeBridge.setFragmentBytes(encoder, 0, dataBuffer, totalSize);
        } else {
            // 大数据：创建临时 buffer 上传
            // 注意：实际实现应使用持久化 buffer + 双缓冲，避免每帧分配
            MemorySegment device = net.irisshaders.iris.metal.IrisMetalDevice.get().deviceHandle();
            MemorySegment buffer = IrisMetalNativeBridge.createBuffer(device, dataBuffer, totalSize, 0);
            IrisMetalNativeBridge.setVertexBufferObject(encoder, 0, buffer, 0);
            IrisMetalNativeBridge.setFragmentBufferObject(encoder, 0, buffer, 0);
            IrisMetalNativeBridge.releaseObject(buffer);
        }
        dirty = false;
    }

    public boolean isDirty() {
        return dirty;
    }

    @Override
    public void close() {
        if (!destroyed) {
            destroyed = true;
            // direct buffer 由 GC 回收，无需显式释放
        }
    }

    private static final class UniformEntry {
        final String name;
        final int offset;
        final int size;
        final int stage; // 0=vertex, 1=fragment, 2=both

        UniformEntry(String name, int offset, int size, int stage) {
            this.name = name;
            this.offset = offset;
            this.size = size;
            this.stage = stage;
        }
    }
}

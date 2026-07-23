package net.irisshaders.iris.metal.buffer;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.metal.IrisMetalDevice;
import net.irisshaders.iris.metal.bridge.IrisMetalNativeBridge;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/**
 * Metal GPU buffer，对应 Iris 原有的 SSBO / VBO / IBO 封装。
 *
 * <p>本类封装一个 {@code MTLBuffer}，用于：</p>
 * <ul>
 *   <li>顶点缓冲区（VBO）</li>
 *   <li>索引缓冲区（IBO）</li>
 *   <li>Shader Storage Buffer（SSBO，对应 GL 的 {@code GL_SHADER_STORAGE_BUFFER}）</li>
 *   <li>Uniform Buffer（UBO，对应 GL 的 {@code GL_UNIFORM_BUFFER}）</li>
 * </ul>
 *
 * <p><b>存储模式</b>：</p>
 * <ul>
 *   <li>{@code MTLStorageModeShared}（0）：CPU/GPU 共享内存，适合频繁更新的数据。
 *       Iris 的 SSBO/UBO 通常用此模式。</li>
 *   <li>{@code MTLStorageModePrivate}（1）：GPU 私有内存，CPU 无法直接访问，需要 blit。
 *       适合静态顶点/索引数据。</li>
 *   <li>{@code MTLStorageModeManaged}（2）：托管内存，CPU 修改后需 {@code didModifyRange}。
 *       Apple Silicon 上等同于 Shared。</li>
 * </ul>
 *
 * <p><b>资源生命周期</b>：Metal buffer 通过引用计数管理，{@link #close()} 释放引用。
 * 注意 buffer 可能在 GPU 命令队列中正在使用，释放前应确保 GPU 完成相关命令
 * （通过 fence 或 waitUntilCompleted）。</p>
 */
@Environment(EnvType.CLIENT)
public final class MetalBuffer implements AutoCloseable {
    private final MemorySegment handle;
    private final long size;
    private final int storageMode;
    private boolean destroyed;

    public static final int STORAGE_SHARED = 0;
    public static final int STORAGE_PRIVATE = 1;
    public static final int STORAGE_MANAGED = 2;

    /**
     * 创建一个指定大小的空 buffer。
     *
     * @param size        字节数
     * @param storageMode 存储模式
     * @param cpuWrite    是否需要 CPU 写入（影响 resource options）
     */
    public MetalBuffer(long size, int storageMode, boolean cpuWrite) {
        this.size = size;
        this.storageMode = storageMode;
        MemorySegment device = IrisMetalDevice.get().deviceHandle();
        this.handle = IrisMetalNativeBridge.createBuffer(device, null, size,
                storageModeToResourceOptions(storageMode, cpuWrite));
    }

    /**
     * 用初始数据创建 buffer。
     */
    public MetalBuffer(ByteBuffer data, int storageMode, boolean cpuWrite) {
        this.size = data.remaining();
        this.storageMode = storageMode;
        MemorySegment device = IrisMetalDevice.get().deviceHandle();
        this.handle = IrisMetalNativeBridge.createBuffer(device, data, size,
                storageModeToResourceOptions(storageMode, cpuWrite));
    }

    private static int storageModeToResourceOptions(int storageMode, boolean cpuWrite) {
        // MTLResourceOptions
        // 0 = StorageModeShared
        // 256 = StorageModeManaged
        // 4096 = StorageModePrivate
        // CPU cache mode: 0 = default, 64 = writeCombined
        switch (storageMode) {
            case STORAGE_SHARED:
                return cpuWrite ? 64 : 0;
            case STORAGE_MANAGED:
                return 256;
            case STORAGE_PRIVATE:
                return 4096;
            default:
                return 0;
        }
    }

    public MemorySegment handle() {
        return handle;
    }

    public long size() {
        return size;
    }

    /**
     * 上传数据到 buffer（仅 Shared/Managed 模式）。
     */
    public void upload(ByteBuffer data) {
        if (storageMode == STORAGE_PRIVATE) {
            throw new IllegalStateException("Cannot upload directly to private storage buffer");
        }
        IrisMetalNativeBridge.uploadBufferData(handle, data, Math.min(data.remaining(), size));
    }

    /**
     * 获取 buffer 的 CPU 可访问指针（仅 Shared 模式）。
     *
     * @return native 内存段的地址，或 null（Private 模式）
     */
    @Nullable
    public MemorySegment contents() {
        if (storageMode == STORAGE_PRIVATE) {
            return null;
        }
        return IrisMetalNativeBridge.getBufferContents(handle);
    }

    @Override
    public void close() {
        if (!destroyed) {
            destroyed = true;
            IrisMetalNativeBridge.releaseObject(handle);
        }
    }
}

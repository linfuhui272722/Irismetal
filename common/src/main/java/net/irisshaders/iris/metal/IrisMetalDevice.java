package net.irisshaders.iris.metal;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.metal.bridge.IrisMetalNativeBridge;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;

/**
 * Iris Metal 设备与命令队列管理器。
 *
 * <p>本类是 Iris Metal 后端的单例入口，负责：</p>
 * <ul>
 *   <li>获取系统默认 Metal 设备（与 metallum 共享同一个 {@code MTLCreateSystemDefaultDevice} 单例）</li>
 *   <li>创建 Iris 专属的命令队列（{@code MTLCommandQueue}）</li>
 *   <li>管理每帧的命令缓冲区生命周期</li>
 *   <li>提供设备句柄供其他 Metal 模块使用</li>
 * </ul>
 *
 * <p><b>与 metallum 的协作</b>：metallum 在初始化时已经创建了 Metal 设备并接管了
 * vanilla/Sodium 的渲染。Iris 不能直接拿到 metallum 的 {@code MetalDevice} 句柄
 * （它是 package-private），但 Metal 的系统默认设备是进程内单例，因此 Iris 调用
 * {@code MTLCreateSystemDefaultDevice()} 会得到同一个设备对象。这样 Iris 的渲染
 * 结果可以与 vanilla/Sodium 共享同一块 GPU 内存空间，但 Iris 拥有独立的命令队列，
 * 避免与 metallum 的命令编码互相干扰。</p>
 *
 * <p><b>命令提交策略</b>：Iris 的光影渲染 pass（gbuffers / composite / final / shadow）
 * 在每帧的特定阶段执行。本类采用"延迟提交"策略——Iris 在一个命令缓冲区内编码完所有
 * pass 后，由 {@link #endFrame()} 统一提交，确保 Metal 命令的执行顺序与 Iris 期望的
 * GL 同步语义一致。</p>
 */
@Environment(EnvType.CLIENT)
public final class IrisMetalDevice {
    private static IrisMetalDevice instance;

    private final MemorySegment device;
    private final String deviceName;
    private final MemorySegment commandQueue;

    @Nullable
    private MemorySegment currentCommandBuffer;
    private int frameDepth = 0;

    private IrisMetalDevice() {
        IrisMetalNativeBridge.ensureLoaded();
        if (!IrisMetalNativeBridge.isAvailable()) {
            throw new IllegalStateException("Iris Metal backend is not available on this platform");
        }
        this.device = IrisMetalNativeBridge.createSystemDefaultDevice();
        if (IrisMetalNativeBridge.isNullHandle(device)) {
            throw new IllegalStateException("Failed to create system default Metal device");
        }
        this.deviceName = IrisMetalNativeBridge.copyDeviceName(device);
        this.commandQueue = IrisMetalNativeBridge.deviceMakeCommandQueue(device);
        if (IrisMetalNativeBridge.isNullHandle(commandQueue)) {
            IrisMetalNativeBridge.releaseObject(device);
            throw new IllegalStateException("Failed to create Metal command queue");
        }
        Iris.logger.info("[Iris-Metal] Initialized on device: {}", deviceName);
    }

    /**
     * 获取 Iris Metal 设备单例。首次调用时初始化。
     */
    public static synchronized IrisMetalDevice get() {
        if (instance == null) {
            instance = new IrisMetalDevice();
        }
        return instance;
    }

    /**
     * @return Metal 后端是否已初始化可用。
     */
    public static boolean isInitialized() {
        return instance != null;
    }

    /**
     * 尝试初始化 Metal 后端，失败返回 false（不抛异常）。用于在启动时探测。
     */
    public static boolean tryInitialize() {
        try {
            get();
            return true;
        } catch (Throwable t) {
            Iris.logger.warn("[Iris-Metal] Failed to initialize Metal backend: {}", t.getMessage());
            return false;
        }
    }

    public MemorySegment deviceHandle() {
        return device;
    }

    public String deviceName() {
        return deviceName;
    }

    public MemorySegment commandQueueHandle() {
        return commandQueue;
    }

    /**
     * 开始一帧的渲染编码。返回当前帧的命令缓冲区，Iris 的各 pass 在此缓冲区内编码。
     *
     * <p>本方法可嵌套调用（{@code frameDepth} 计数），只有最外层调用会真正创建命令缓冲区。</p>
     */
    public MemorySegment beginFrame() {
        if (frameDepth == 0) {
            currentCommandBuffer = IrisMetalNativeBridge.commandQueueMakeCommandBuffer(commandQueue);
            if (IrisMetalNativeBridge.isNullHandle(currentCommandBuffer)) {
                throw new IllegalStateException("Failed to create command buffer for frame");
            }
        }
        frameDepth++;
        return currentCommandBuffer;
    }

    /**
     * 获取当前帧的命令缓冲区（必须在 {@link #beginFrame()} 之后调用）。
     */
    public MemorySegment currentCommandBuffer() {
        if (currentCommandBuffer == null || IrisMetalNativeBridge.isNullHandle(currentCommandBuffer)) {
            throw new IllegalStateException("No active command buffer; beginFrame() must be called first");
        }
        return currentCommandBuffer;
    }

    /**
     * 结束一帧的渲染编码。最外层调用时提交命令缓冲区并等待完成（保持 GL 同步语义）。
     */
    public void endFrame() {
        if (frameDepth <= 0) {
            throw new IllegalStateException("endFrame() called without matching beginFrame()");
        }
        frameDepth--;
        if (frameDepth == 0) {
            MemorySegment buffer = currentCommandBuffer;
            currentCommandBuffer = null;
            IrisMetalNativeBridge.commandBufferCommit(buffer);
            // 为保持与 OpenGL 的同步语义，等待命令完成。
            // 注意：这会牺牲一些性能，但 Iris 的渲染逻辑假设命令是同步完成的。
            // 后续可优化为 fence-based 异步等待。
            IrisMetalNativeBridge.commandBufferWaitUntilCompleted(buffer);
            IrisMetalNativeBridge.releaseObject(buffer);
        }
    }

    /**
     * 销毁设备与命令队列。仅在 Iris 卸载光影时调用。
     */
    public synchronized void destroy() {
        if (currentCommandBuffer != null && !IrisMetalNativeBridge.isNullHandle(currentCommandBuffer)) {
            IrisMetalNativeBridge.releaseObject(currentCommandBuffer);
            currentCommandBuffer = null;
        }
        if (!IrisMetalNativeBridge.isNullHandle(commandQueue)) {
            IrisMetalNativeBridge.releaseObject(commandQueue);
        }
        if (!IrisMetalNativeBridge.isNullHandle(device)) {
            IrisMetalNativeBridge.releaseObject(device);
        }
        instance = null;
    }
}

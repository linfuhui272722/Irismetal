package net.irisshaders.iris.metal.bridge;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.Iris;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Iris Metal 原生桥接层。
 *
 * <p>本类仿照 metallum 的 {@code MetalNativeBridge} 实现，负责通过 JNI/FFM 调用
 * macOS 上的 Metal 框架（MetalKit / QuartzCore / Foundation）。所有 Metal 对象
 * 句柄均以 {@link MemorySegment}（原生指针）形式在 Java 与原生层之间传递。</p>
 *
 * <p><b>重要说明</b>：本类只能在 macOS + Apple Silicon 环境下加载对应的
 * {@code libiris_metal.dylib}。在非 macOS 环境下，{@link #ensureLoaded()} 会抛出
 * {@link IllegalStateException}，调用方应据此回退到 OpenGL 路径或禁用 Iris。</p>
 *
 * <p>原生库 {@code libiris_metal.dylib} 的源码见
 * {@code src/main/native/IrisMetalNative.swift}，编译方式见同目录 README。</p>
 *
 * <p>与 metallum 的关系：metallum 已经为 vanilla + Sodium 实现了 Metal 后端，
 * 其 {@code MetalDevice} / {@code MetalBackend} / {@code MetalNativeBridge} 提供了
 * MC 26.2 {@code GpuBackend} 抽象的 Metal 实现。Iris 无法复用 metallum 的设备
 * 句柄（metallum 的 {@code MetalDevice} 是 package-private），因此 Iris 需要自己
 * 持有一个 Metal 设备与命令队列。本桥接层在 metallum 已初始化的 Metal 设备之上
 * 获取同一个系统默认设备（{@code MTLCreateSystemDefaultDevice} 返回单例），
 * 从而与 metallum 共享 GPU 设备但拥有独立的命令队列与渲染管线。</p>
 */
@Environment(EnvType.CLIENT)
public final class IrisMetalNativeBridge {
    private static final String RESOURCE_PATH = "/natives/macos/libiris_metal.dylib";
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT;
    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;
    private static final Linker LINKER = Linker.nativeLinker();

    private static volatile boolean initialized = false;
    private static volatile boolean available = false;

    // ===== 设备与命令队列 =====
    private static MethodHandle createSystemDefaultDevice;
    private static MethodHandle copyDeviceName;
    private static MethodHandle deviceMakeCommandQueue;
    private static MethodHandle commandQueueMakeCommandBuffer;
    private static MethodHandle commandBufferCommit;
    private static MethodHandle commandBufferWaitUntilCompleted;
    private static MethodHandle commandBufferIsCompleted;
    private static MethodHandle commandBufferPushDebugGroup;
    private static MethodHandle commandBufferPopDebugGroup;
    private static MethodHandle commandBufferMakeRenderCommandEncoder;
    private static MethodHandle commandBufferMakeBlitCommandEncoder;
    private static MethodHandle commandBufferMakeComputeCommandEncoder;
    private static MethodHandle commandEncoderEndEncoding;

    // ===== 纹理 =====
    private static MethodHandle createTexture2D;
    private static MethodHandle createTexture3D;
    private static MethodHandle createTextureCube;
    private static MethodHandle textureReplaceRegion;
    private static MethodHandle textureGetBytes;
    private static MethodHandle releaseObject;

    // ===== Buffer =====
    private static MethodHandle createBuffer;
    private static MethodHandle bufferContents;
    private static MethodHandle bufferReplaceRegion;

    // ===== Render Pipeline (编译后的 MTLRenderPipelineState) =====
    private static MethodHandle compileRenderPipeline;
    private static MethodHandle compileComputePipeline;
    private static MethodHandle renderEncoderSetRenderPipelineState;
    private static MethodHandle renderEncoderSetDepthStencilState;
    private static MethodHandle renderEncoderSetDepthBias;
    private static MethodHandle renderEncoderSetFrontFacingWinding;
    private static MethodHandle renderEncoderSetCullMode;
    private static MethodHandle renderEncoderSetTriangleFillMode;
    private static MethodHandle renderEncoderSetBuffer;
    private static MethodHandle renderEncoderSetBufferOffset;
    private static MethodHandle renderEncoderSetTexture;
    private static MethodHandle renderEncoderSetSamplerState;
    private static MethodHandle renderEncoderSetScissorRect;
    private static MethodHandle renderEncoderSetViewport;
    private static MethodHandle renderEncoderSetBlendColor;
    private static MethodHandle renderEncoderSetColorWriteMask;
    private static MethodHandle renderEncoderDrawPrimitives;
    private static MethodHandle renderEncoderDrawIndexedPrimitives;
    private static MethodHandle renderEncoderDrawPrimitivesInstanced;
    private static MethodHandle renderEncoderDrawIndexedPrimitivesInstanced;

    // ===== Compute =====
    private static MethodHandle computeEncoderSetComputePipelineState;
    private static MethodHandle computeEncoderSetBuffer;
    private static MethodHandle computeEncoderSetTexture;
    private static MethodHandle computeEncoderSetSamplerState;
    private static MethodHandle computeEncoderDispatchThreadgroups;

    // ===== DepthStencil =====
    private static MethodHandle makeDepthStencilState;

    // ===== Sampler =====
    private static MethodHandle makeSamplerState;

    // ===== Blit =====
    private static MethodHandle blitCopyBufferToBuffer;
    private static MethodHandle blitCopyBufferToTexture;
    private static MethodHandle blitCopyTextureToTexture;
    private static MethodHandle blitCopyTextureToBuffer;
    private static MethodHandle blitGenerateMipmaps;

    // ===== SPIRV-Cross shader 编译（GLSL→SPIRV→MSL）=====
    private static MethodHandle compileGlslToMsl;
    private static MethodHandle getCompiledMslSource;
    private static MethodHandle getCompiledMslError;
    private static MethodHandle freeCompiledShader;

    private IrisMetalNativeBridge() {
    }

    /**
     * 加载原生库并解析所有符号。仅在 macOS 上可成功。
     *
     * <p>本方法线程安全，可被多次调用。</p>
     */
    public static synchronized void ensureLoaded() {
        if (initialized) {
            return;
        }
        initialized = true;

        String osName = System.getProperty("os.name", "").toLowerCase();
        if (!osName.contains("mac")) {
            Iris.logger.warn("Iris Metal backend requires macOS, current OS: {}. Metal backend disabled.", osName);
            available = false;
            return;
        }

        try {
            Path tempLib = Files.createTempFile("iris-metal-native-", ".dylib");
            tempLib.toFile().deleteOnExit();
            try (InputStream stream = IrisMetalNativeBridge.class.getResourceAsStream(RESOURCE_PATH)) {
                if (stream == null) {
                    throw new IllegalStateException("Missing native library resource: " + RESOURCE_PATH);
                }
                Files.copy(stream, tempLib, StandardCopyOption.REPLACE_EXISTING);
            }

            SymbolLookup lookup = SymbolLookup.libraryLookup(tempLib, Arena.global());
            resolveAll(lookup);
            available = true;
            Iris.logger.info("Iris Metal native bridge loaded successfully.");
        } catch (Throwable t) {
            Iris.logger.error("Failed to load Iris Metal native bridge, Metal backend disabled", t);
            available = false;
        }
    }

    /**
     * @return Metal 后端是否可用（已成功加载原生库且运行在 macOS 上）。
     */
    public static boolean isAvailable() {
        if (!initialized) {
            ensureLoaded();
        }
        return available;
    }

    private static void resolveAll(SymbolLookup lookup) {
        // 设备与命令队列
        createSystemDefaultDevice = downcall(lookup, "iris_metal_create_system_default_device",
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        copyDeviceName = downcall(lookup, "iris_metal_copy_device_name",
                FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG));
        deviceMakeCommandQueue = downcall(lookup, "iris_metal_MTLDevice_makeCommandQueue",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        commandQueueMakeCommandBuffer = downcall(lookup, "iris_metal_MTLCommandQueue_makeCommandBuffer",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        commandBufferCommit = downcall(lookup, "iris_metal_MTLCommandBuffer_commit",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        commandBufferWaitUntilCompleted = downcallWithoutCritical(lookup, "iris_metal_MTLCommandBuffer_waitUntilCompleted",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        commandBufferIsCompleted = downcall(lookup, "iris_metal_MTLCommandBuffer_isCompleted",
                FunctionDescriptor.of(INT, ValueLayout.ADDRESS));
        commandBufferPushDebugGroup = downcall(lookup, "iris_metal_MTLCommandBuffer_pushDebugGroup",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        commandBufferPopDebugGroup = downcall(lookup, "iris_metal_MTLCommandBuffer_popDebugGroup",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        commandBufferMakeRenderCommandEncoder = downcall(lookup, "iris_metal_MTLCommandBuffer_makeRenderCommandEncoder",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        DOUBLE, DOUBLE, INT, FLOAT, FLOAT, FLOAT, FLOAT, INT, DOUBLE));
        commandBufferMakeBlitCommandEncoder = downcall(lookup, "iris_metal_MTLCommandBuffer_makeBlitCommandEncoder",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        commandBufferMakeComputeCommandEncoder = downcall(lookup, "iris_metal_MTLCommandBuffer_makeComputeCommandEncoder",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        commandEncoderEndEncoding = downcall(lookup, "iris_metal_MTLCommandEncoder_endEncoding",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

        // 纹理
        createTexture2D = downcall(lookup, "iris_metal_create_texture_2d",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, LONG, LONG, LONG, LONG, LONG, INT, INT, ValueLayout.ADDRESS));
        createTexture3D = downcall(lookup, "iris_metal_create_texture_3d",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, LONG, LONG, LONG, LONG, INT, INT, ValueLayout.ADDRESS));
        createTextureCube = downcall(lookup, "iris_metal_create_texture_cube",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, LONG, LONG, LONG, INT, INT, ValueLayout.ADDRESS));
        textureReplaceRegion = downcall(lookup, "iris_metal_texture_replace_region",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, INT, LONG));
        textureGetBytes = downcall(lookup, "iris_metal_texture_get_bytes",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, INT, LONG));
        releaseObject = downcall(lookup, "iris_metal_release_object",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

        // Buffer
        createBuffer = downcall(lookup, "iris_metal_create_buffer",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
        bufferContents = downcall(lookup, "iris_metal_buffer_contents",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        bufferReplaceRegion = downcall(lookup, "iris_metal_buffer_replace_region",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG));

        // Render Pipeline
        compileRenderPipeline = downcall(lookup, "iris_metal_compile_render_pipeline",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS));
        compileComputePipeline = downcall(lookup, "iris_metal_compile_compute_pipeline",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        renderEncoderSetRenderPipelineState = downcall(lookup, "iris_metal_renderEncoder_setRenderPipelineState",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        renderEncoderSetDepthStencilState = downcall(lookup, "iris_metal_renderEncoder_setDepthStencilState",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        renderEncoderSetDepthBias = downcall(lookup, "iris_metal_renderEncoder_setDepthBias",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, FLOAT, FLOAT, FLOAT));
        renderEncoderSetFrontFacingWinding = downcall(lookup, "iris_metal_renderEncoder_setFrontFacingWinding",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT));
        renderEncoderSetCullMode = downcall(lookup, "iris_metal_renderEncoder_setCullMode",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT));
        renderEncoderSetTriangleFillMode = downcall(lookup, "iris_metal_renderEncoder_setTriangleFillMode",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT));
        renderEncoderSetBuffer = downcall(lookup, "iris_metal_renderEncoder_setBuffer",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, INT));
        renderEncoderSetBufferOffset = downcall(lookup, "iris_metal_renderEncoder_setBufferOffset",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, INT));
        renderEncoderSetTexture = downcall(lookup, "iris_metal_renderEncoder_setTexture",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
        renderEncoderSetSamplerState = downcall(lookup, "iris_metal_renderEncoder_setSamplerState",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
        renderEncoderSetScissorRect = downcall(lookup, "iris_metal_renderEncoder_setScissorRect",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, LONG));
        renderEncoderSetViewport = downcall(lookup, "iris_metal_renderEncoder_setViewport",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, DOUBLE, DOUBLE, DOUBLE, DOUBLE, DOUBLE, DOUBLE, LONG, LONG));
        renderEncoderSetBlendColor = downcall(lookup, "iris_metal_renderEncoder_setBlendColor",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, FLOAT, FLOAT, FLOAT, FLOAT));
        renderEncoderSetColorWriteMask = downcall(lookup, "iris_metal_renderEncoder_setColorWriteMask",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT));
        renderEncoderDrawPrimitives = downcall(lookup, "iris_metal_renderEncoder_drawPrimitives",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT, LONG, LONG, LONG));
        renderEncoderDrawIndexedPrimitives = downcall(lookup, "iris_metal_renderEncoder_drawIndexedPrimitives",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT, ValueLayout.ADDRESS, LONG, LONG, LONG));
        renderEncoderDrawPrimitivesInstanced = downcall(lookup, "iris_metal_renderEncoder_drawPrimitivesInstanced",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT, LONG, LONG, LONG, LONG));
        renderEncoderDrawIndexedPrimitivesInstanced = downcall(lookup, "iris_metal_renderEncoder_drawIndexedPrimitivesInstanced",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG));

        // Compute
        computeEncoderSetComputePipelineState = downcall(lookup, "iris_metal_computeEncoder_setComputePipelineState",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        computeEncoderSetBuffer = downcall(lookup, "iris_metal_computeEncoder_setBuffer",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, INT));
        computeEncoderSetTexture = downcall(lookup, "iris_metal_computeEncoder_setTexture",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
        computeEncoderSetSamplerState = downcall(lookup, "iris_metal_computeEncoder_setSamplerState",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
        computeEncoderDispatchThreadgroups = downcall(lookup, "iris_metal_computeEncoder_dispatchThreadgroups",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG));

        // DepthStencil
        makeDepthStencilState = downcall(lookup, "iris_metal_MTLDevice_makeDepthStencilState",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, INT, INT, INT, INT, INT, INT, INT, INT));

        // Sampler
        makeSamplerState = downcall(lookup, "iris_metal_MTLDevice_makeSamplerState",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, INT, INT, INT, INT, INT, INT, FLOAT, FLOAT, FLOAT, FLOAT, INT, INT));

        // Blit
        blitCopyBufferToBuffer = downcall(lookup, "iris_metal_blitEncoder_copyBufferToBuffer",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG, LONG));
        blitCopyBufferToTexture = downcall(lookup, "iris_metal_blitEncoder_copyBufferToTexture",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG));
        blitCopyTextureToTexture = downcall(lookup, "iris_metal_blitEncoder_copyTextureToTexture",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG));
        blitCopyTextureToBuffer = downcall(lookup, "iris_metal_blitEncoder_copyTextureToBuffer",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG));
        blitGenerateMipmaps = downcall(lookup, "iris_metal_blitEncoder_generateMipmaps",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        // SPIRV-Cross shader 编译
        compileGlslToMsl = downcall(lookup, "iris_metal_compile_glsl_to_msl",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        getCompiledMslSource = downcall(lookup, "iris_metal_get_compiled_msl_source",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        getCompiledMslError = downcall(lookup, "iris_metal_get_compiled_msl_error",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        freeCompiledShader = downcall(lookup, "iris_metal_free_compiled_shader",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    }

    private static MethodHandle downcall(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        MethodHandle handle = lookup.find(name).map(symbol -> LINKER.downcallHandle(symbol, desc)).orElse(null);
        if (handle == null) {
            throw new IllegalStateException("Missing native symbol: " + name);
        }
        return handle;
    }

    private static MethodHandle downcallWithoutCritical(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        // 阻塞型调用不使用 critical linkage，避免在等待期间持有 critical 区域
        MethodHandle handle = lookup.find(name).map(symbol -> LINKER.downcallHandle(symbol, desc, Linker.Option.critical(false))).orElse(null);
        if (handle == null) {
            throw new IllegalStateException("Missing native symbol: " + name);
        }
        return handle;
    }

    // ===== 句柄工具 =====
    public static boolean isNullHandle(MemorySegment handle) {
        return handle == null || handle.address() == 0;
    }

    public static void releaseObject(MemorySegment handle) {
        if (isNullHandle(handle)) {
            return;
        }
        try {
            releaseObject.invoke(handle);
        } catch (Throwable t) {
            Iris.logger.error("Failed to release Metal object", t);
        }
    }

    // ===== 设备与命令队列 =====
    public static MemorySegment createSystemDefaultDevice() {
        try {
            return (MemorySegment) createSystemDefaultDevice.invoke();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create system default Metal device", t);
        }
    }

    public static String copyDeviceName(MemorySegment device) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(256);
            int len = (int) copyDeviceName.invoke(device, buf, 255L);
            if (len <= 0) {
                return "Unknown Metal Device";
            }
            return buf.reinterpret(len).getString(0);
        } catch (Throwable t) {
            return "Unknown Metal Device";
        }
    }

    public static MemorySegment deviceMakeCommandQueue(MemorySegment device) {
        try {
            return (MemorySegment) deviceMakeCommandQueue.invoke(device);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create Metal command queue", t);
        }
    }

    public static MemorySegment commandQueueMakeCommandBuffer(MemorySegment queue) {
        try {
            return (MemorySegment) commandQueueMakeCommandBuffer.invoke(queue);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create Metal command buffer", t);
        }
    }

    public static void commandBufferCommit(MemorySegment buffer) {
        try {
            commandBufferCommit.invoke(buffer);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to commit Metal command buffer", t);
        }
    }

    public static void commandBufferWaitUntilCompleted(MemorySegment buffer) {
        try {
            commandBufferWaitUntilCompleted.invoke(buffer);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to wait for Metal command buffer", t);
        }
    }

    public static boolean commandBufferIsCompleted(MemorySegment buffer) {
        try {
            return (int) commandBufferIsCompleted.invoke(buffer) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void commandBufferPushDebugGroup(MemorySegment buffer, String name) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment nameSeg = arena.allocateUtf8String(name);
            commandBufferPushDebugGroup.invoke(buffer, nameSeg);
        } catch (Throwable t) {
            Iris.logger.debug("pushDebugGroup failed", t);
        }
    }

    public static void commandBufferPopDebugGroup(MemorySegment buffer) {
        try {
            commandBufferPopDebugGroup.invoke(buffer);
        } catch (Throwable t) {
            Iris.logger.debug("popDebugGroup failed", t);
        }
    }

    /**
     * 创建一个 render command encoder，绑定指定的颜色附件与深度/模板附件。
     *
     * @param buffer            命令缓冲区
     * @param colorTextures     颜色附件纹理数组（可为空元素表示不使用该附件）
     * @param depthTexture      深度附件纹理（可为 null）
     * @param clearColorValues  颜色清除值（RGBA double 数组，长度 = 颜色附件数）
     * @param clearDepth        深度清除值
     * @param loadAction        加载动作（0=Load, 1=Clear, 2=DontCare）
     * @param storeAction       存储动作（0=Store, 1=DontCare, 2=MsaaResolve）
     * @return render command encoder 句柄
     */
    public static MemorySegment makeRenderCommandEncoder(MemorySegment buffer,
                                                          MemorySegment[] colorTextures,
                                                          @Nullable MemorySegment depthTexture,
                                                          double[] clearColorValues,
                                                          double clearDepth,
                                                          int loadAction,
                                                          int storeAction) {
        try (Arena arena = Arena.ofConfined()) {
            // 将颜色附件数组打包为原生指针数组
            MemorySegment colorArray = arena.allocate(ValueLayout.ADDRESS, colorTextures.length);
            for (int i = 0; i < colorTextures.length; i++) {
                colorArray.setAtIndex(ValueLayout.ADDRESS, i, colorTextures[i] != null ? colorTextures[i] : MemorySegment.NULL);
            }
            // 颜色清除值：每个附件 4 个 double (RGBA)
            MemorySegment clearColors = arena.allocate(DOUBLE, clearColorValues.length);
            for (int i = 0; i < clearColorValues.length; i++) {
                clearColors.setAtIndex(DOUBLE, i, clearColorValues[i]);
            }
            return (MemorySegment) commandBufferMakeRenderCommandEncoder.invoke(buffer, colorArray, depthTexture != null ? depthTexture : MemorySegment.NULL,
                    clearColors, clearDepth, (double) storeAction, loadAction,
                    0f, 0f, 0f, 0f, colorTextures.length, 0.0);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create render command encoder", t);
        }
    }

    public static MemorySegment makeBlitCommandEncoder(MemorySegment buffer) {
        try {
            return (MemorySegment) commandBufferMakeBlitCommandEncoder.invoke(buffer);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create blit command encoder", t);
        }
    }

    public static MemorySegment makeComputeCommandEncoder(MemorySegment buffer) {
        try {
            return (MemorySegment) commandBufferMakeComputeCommandEncoder.invoke(buffer);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create compute command encoder", t);
        }
    }

    public static void endEncoding(MemorySegment encoder) {
        try {
            commandEncoderEndEncoding.invoke(encoder);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to end encoding", t);
        }
    }

    // ===== 纹理 =====
    public static MemorySegment createTexture2D(MemorySegment device, int pixelFormat, long width, long height,
                                                 long depthOrLayers, long mipLevels, long sampleCount,
                                                 int usage, int storageMode, String label) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment labelSeg = arena.allocateUtf8String(label);
            return (MemorySegment) createTexture2D.invoke(device, pixelFormat, width, height, depthOrLayers, mipLevels, sampleCount, usage, storageMode, labelSeg);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create 2D texture", t);
        }
    }

    public static MemorySegment createTexture3D(MemorySegment device, int pixelFormat, long width, long height,
                                                 long depth, long mipLevels, int usage, int storageMode, String label) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment labelSeg = arena.allocateUtf8String(label);
            return (MemorySegment) createTexture3D.invoke(device, pixelFormat, width, height, depth, mipLevels, usage, storageMode, labelSeg);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create 3D texture", t);
        }
    }

    public static MemorySegment createTextureCube(MemorySegment device, int pixelFormat, long size, long mipLevels,
                                                   int usage, int storageMode, String label) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment labelSeg = arena.allocateUtf8String(label);
            return (MemorySegment) createTextureCube.invoke(device, pixelFormat, size, mipLevels, usage, storageMode, labelSeg);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create cube texture", t);
        }
    }

    public static void textureReplaceRegion(MemorySegment texture, MemorySegment data, long dataLength,
                                             long slice, long level, long originX, long originY, long originZ,
                                             long width, long height, long depth, int bytesPerRow, long bytesPerImage) {
        try {
            textureReplaceRegion.invoke(texture, data, dataLength, slice, level, originX, originY, originZ, width, height, depth, bytesPerRow, bytesPerImage);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to replace texture region", t);
        }
    }

    public static void textureGetBytes(MemorySegment texture, MemorySegment out, long outLength,
                                        long slice, long level, long originX, long originY, long originZ,
                                        long width, long height, long depth, int bytesPerRow, long bytesPerImage) {
        try {
            textureGetBytes.invoke(texture, out, outLength, slice, level, originX, originY, originZ, width, height, depth, bytesPerRow, bytesPerImage);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to read texture bytes", t);
        }
    }

    // ===== Buffer =====
    public static MemorySegment createBuffer(MemorySegment device, long length, int options) {
        try {
            return (MemorySegment) createBuffer.invoke(device, length, options);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create Metal buffer", t);
        }
    }

    public static MemorySegment bufferContents(MemorySegment buffer) {
        try {
            return (MemorySegment) bufferContents.invoke(buffer);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to get buffer contents", t);
        }
    }

    public static void bufferReplaceRegion(MemorySegment buffer, MemorySegment data, long offset, long length) {
        try {
            bufferReplaceRegion.invoke(buffer, data, offset, length);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to replace buffer region", t);
        }
    }

    // ===== Render Pipeline 编译 =====
    /**
     * 编译一个 MSL 顶点+片段着色器为 MTLRenderPipelineState。
     *
     * @param device          Metal 设备
     * @param vertexMslSource 顶点 MSL 源码（UTF-8 C 字符串）
     * @param fragmentMslSource 片段 MSL 源码
     * @param vertexFunctionName 顶点函数名（如 "vs_main"）
     * @param fragmentFunctionName 片段函数名（如 "fs_main"）
     * @param label           pipeline 标签
     * @return 编译后的 MTLRenderPipelineState 句柄，失败返回 null
     */
    public static MemorySegment compileRenderPipeline(MemorySegment device, String vertexMslSource, String fragmentMslSource,
                                                       String vertexFunctionName, String fragmentFunctionName, String label) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment vSrc = arena.allocateUtf8String(vertexMslSource);
            MemorySegment fSrc = arena.allocateUtf8String(fragmentMslSource);
            MemorySegment vName = arena.allocateUtf8String(vertexFunctionName);
            MemorySegment fName = arena.allocateUtf8String(fragmentFunctionName);
            MemorySegment labelSeg = arena.allocateUtf8String(label);
            // 将 vName/fName/label 打包进一个结构体指针传给原生层
            MemorySegment names = arena.allocate(ValueLayout.ADDRESS, 3);
            names.setAtIndex(ValueLayout.ADDRESS, 0, vName);
            names.setAtIndex(ValueLayout.ADDRESS, 1, fName);
            names.setAtIndex(ValueLayout.ADDRESS, 2, labelSeg);
            return (MemorySegment) compileRenderPipeline.invoke(device, vSrc, fSrc, names, 0L, MemorySegment.NULL);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to compile render pipeline: " + t.getMessage(), t);
        }
    }

    public static MemorySegment compileComputePipeline(MemorySegment device, String mslSource, String functionName, String label) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocateUtf8String(mslSource);
            MemorySegment name = arena.allocateUtf8String(functionName);
            MemorySegment labelSeg = arena.allocateUtf8String(label);
            MemorySegment names = arena.allocate(ValueLayout.ADDRESS, 2);
            names.setAtIndex(ValueLayout.ADDRESS, 0, name);
            names.setAtIndex(ValueLayout.ADDRESS, 1, labelSeg);
            return (MemorySegment) compileComputePipeline.invoke(device, src, names);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to compile compute pipeline: " + t.getMessage(), t);
        }
    }

    // ===== Render Encoder 设置 =====
    public static void renderEncoderSetRenderPipelineState(MemorySegment encoder, MemorySegment pipeline) {
        try {
            renderEncoderSetRenderPipelineState.invoke(encoder, pipeline);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetDepthStencilState(MemorySegment encoder, MemorySegment state) {
        try {
            renderEncoderSetDepthStencilState.invoke(encoder, state);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetDepthBias(MemorySegment encoder, float bias, float slopeScale, float clamp) {
        try {
            renderEncoderSetDepthBias.invoke(encoder, bias, slopeScale, clamp);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetFrontFacingWinding(MemorySegment encoder, int winding) {
        try {
            renderEncoderSetFrontFacingWinding.invoke(encoder, winding);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetCullMode(MemorySegment encoder, int mode) {
        try {
            renderEncoderSetCullMode.invoke(encoder, mode);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetTriangleFillMode(MemorySegment encoder, int mode) {
        try {
            renderEncoderSetTriangleFillMode.invoke(encoder, mode);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetBuffer(MemorySegment encoder, MemorySegment buffer, long offset, long length, int index) {
        try {
            renderEncoderSetBuffer.invoke(encoder, buffer, offset, length, index);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetBufferOffset(MemorySegment encoder, long offset, long length, int index) {
        try {
            renderEncoderSetBufferOffset.invoke(encoder, offset, length, index);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetTexture(MemorySegment encoder, MemorySegment texture, long level, int index) {
        try {
            renderEncoderSetTexture.invoke(encoder, texture, level, index);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetSamplerState(MemorySegment encoder, MemorySegment sampler, long lod, int index) {
        try {
            renderEncoderSetSamplerState.invoke(encoder, sampler, lod, index);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetScissorRect(MemorySegment encoder, long x, long y, long width, long height) {
        try {
            renderEncoderSetScissorRect.invoke(encoder, x, y, width, height);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetViewport(MemorySegment encoder, double originX, double originY, double width,
                                                 double height, double znear, double zfar, long scissorX, long scissorY) {
        try {
            renderEncoderSetViewport.invoke(encoder, originX, originY, width, height, znear, zfar, scissorX, scissorY);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetBlendColor(MemorySegment encoder, float r, float g, float b, float a) {
        try {
            renderEncoderSetBlendColor.invoke(encoder, r, g, b, a);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetColorWriteMask(MemorySegment encoder, int mask) {
        try {
            renderEncoderSetColorWriteMask.invoke(encoder, mask);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderDrawPrimitives(MemorySegment encoder, int primitiveType, long vertexStart, long vertexCount, long instanceCount) {
        try {
            renderEncoderDrawPrimitives.invoke(encoder, primitiveType, vertexStart, vertexCount, instanceCount);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderDrawIndexedPrimitives(MemorySegment encoder, int primitiveType, MemorySegment indexBuffer,
                                                           long indexCount, long indexStart, long instanceCount) {
        try {
            renderEncoderDrawIndexedPrimitives.invoke(encoder, primitiveType, indexBuffer, indexCount, indexStart, instanceCount);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    // ===== Compute Encoder =====
    public static void computeEncoderSetComputePipelineState(MemorySegment encoder, MemorySegment pipeline) {
        try {
            computeEncoderSetComputePipelineState.invoke(encoder, pipeline);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void computeEncoderSetBuffer(MemorySegment encoder, MemorySegment buffer, long offset, long length, int index) {
        try {
            computeEncoderSetBuffer.invoke(encoder, buffer, offset, length, index);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void computeEncoderSetTexture(MemorySegment encoder, MemorySegment texture, long level, int index) {
        try {
            computeEncoderSetTexture.invoke(encoder, texture, level, index);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void computeEncoderSetSamplerState(MemorySegment encoder, MemorySegment sampler, long lod, int index) {
        try {
            computeEncoderSetSamplerState.invoke(encoder, sampler, lod, index);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void computeEncoderDispatchThreadgroups(MemorySegment encoder, long groupsX, long groupsY, long groupsZ,
                                                           long threadsX, long threadsY, long threadsZ) {
        try {
            computeEncoderDispatchThreadgroups.invoke(encoder, groupsX, groupsY, groupsZ, threadsX, threadsY, threadsZ);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    // ===== DepthStencil =====
    public static MemorySegment makeDepthStencilState(MemorySegment device, int depthCompare, int depthWriteEnabled,
                                                       int frontStencilCompare, int frontStencilWriteMask, int frontStencilReadMask,
                                                       int frontStencilFailure, int frontStencilDepthFailure, int frontStencilPass,
                                                       int backStencilCompare) {
        try {
            return (MemorySegment) makeDepthStencilState.invoke(device, depthCompare, depthWriteEnabled,
                    frontStencilCompare, frontStencilWriteMask, frontStencilReadMask,
                    frontStencilFailure, frontStencilDepthFailure, frontStencilPass, backStencilCompare);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    // ===== Sampler =====
    public static MemorySegment makeSamplerState(MemorySegment device, int minFilter, int magFilter, int mipFilter,
                                                  int sAddressMode, int tAddressMode, int rAddressMode,
                                                  int compareFunction, float lodMinClamp, float lodMaxClamp,
                                                  float maxAnisotropy, int normalizedCoords, int supportArgumentBuffers) {
        try {
            return (MemorySegment) makeSamplerState.invoke(device, minFilter, magFilter, mipFilter,
                    sAddressMode, tAddressMode, rAddressMode, compareFunction,
                    lodMinClamp, lodMaxClamp, maxAnisotropy, normalizedCoords, supportArgumentBuffers);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    // ===== Blit =====
    public static void blitCopyBufferToBuffer(MemorySegment encoder, MemorySegment src, long srcOffset,
                                               MemorySegment dst, long dstOffset, long size) {
        try {
            blitCopyBufferToBuffer.invoke(encoder, src, srcOffset, dst, dstOffset, size);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void blitCopyBufferToTexture(MemorySegment encoder, MemorySegment src, long srcOffset,
                                                MemorySegment dst, long dstSlice, long dstLevel, long dstOriginX,
                                                long dstOriginY, long dstOriginZ, long width, long height, long depth,
                                                long bytesPerRow, long bytesPerImage) {
        try {
            blitCopyBufferToTexture.invoke(encoder, src, srcOffset, dst, dstSlice, dstLevel, dstOriginX, dstOriginY, dstOriginZ, width, height, depth, bytesPerRow, bytesPerImage);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void blitCopyTextureToTexture(MemorySegment encoder, MemorySegment src, MemorySegment dst,
                                                 long srcSlice, long srcLevel, long srcOriginX, long srcOriginY, long srcOriginZ,
                                                 long dstSlice, long dstLevel, long size) {
        try {
            blitCopyTextureToTexture.invoke(encoder, src, dst, srcSlice, srcLevel, srcOriginX, srcOriginY, srcOriginZ, dstSlice, dstLevel, size);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void blitCopyTextureToBuffer(MemorySegment encoder, MemorySegment src, MemorySegment dst,
                                                long srcSlice, long srcLevel, long srcOriginX, long srcOriginY, long srcOriginZ,
                                                long width, long height, long depth, long bytesPerRow, long bytesPerImage) {
        try {
            blitCopyTextureToBuffer.invoke(encoder, src, dst, srcSlice, srcLevel, srcOriginX, srcOriginY, srcOriginZ, width, height, depth, bytesPerRow, bytesPerImage);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void blitGenerateMipmaps(MemorySegment encoder, MemorySegment texture) {
        try {
            blitGenerateMipmaps.invoke(encoder, texture);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    // ===== SPIRV-Cross shader 编译 =====
    /**
     * 将 GLSL 源码编译为 MSL。
     *
     * <p>本方法调用原生层的 SPIRV-Cross，执行 GLSL → SPIR-V → MSL 的完整转换。
     * 转换过程仿照 metallum 的 {@code MetalCrossShaderCompiler}，但针对 Iris 的
     * 光影 shader 做了以下适配：</p>
     * <ul>
     *   <li>保留 Iris 注入的 {@code #define} 宏（通过 source 传入已预处理源码）</li>
     *   <li>将 GLSL 的 {@code gl_FragData} / 多渲染目标输出映射到 MSL 的
     *       {@code [[color(N)]]} 限定符</li>
     *   <li>将 GLSL 的 sampler2D 拆分为 MSL 的 texture + sampler（Metal 是分离的）</li>
     *   <li>将 GLSL 的 uniform block 映射为 MSL 的 {@code [[buffer(N)]]}</li>
     * </ul>
     *
     * @param glslSource   已预处理的 GLSL 源码
     * @param stage        0=Vertex, 1=Fragment, 2=Compute, 3=Geometry(降级)
     * @param entryPoint   入口函数名（通常为 "main"）
     * @return 编译结果句柄，通过 {@link #getCompiledMslSource} 获取 MSL 源码
     */
    public static MemorySegment compileGlslToMsl(String glslSource, int stage, String entryPoint) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = arena.allocateUtf8String(glslSource);
            MemorySegment entry = arena.allocateUtf8String(entryPoint);
            MemorySegment errorOut = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment result = (MemorySegment) compileGlslToMsl.invoke(src, entry, stage, MemorySegment.NULL, errorOut);
            MemorySegment errorSeg = errorOut.get(ValueLayout.ADDRESS, 0);
            if (!isNullHandle(errorSeg)) {
                String error = errorSeg.reinterpret(8192).getString(0);
                throw new RuntimeException("GLSL→MSL compilation failed: " + error);
            }
            return result;
        } catch (Throwable t) {
            throw new RuntimeException("GLSL→MSL compilation failed", t);
        }
    }

    public static String getCompiledMslSource(MemorySegment compiledHandle) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment srcSeg = (MemorySegment) getCompiledMslSource.invoke(compiledHandle);
            if (isNullHandle(srcSeg)) {
                return "";
            }
            // 读取 C 字符串
            long len = srcSeg.reinterpret(Long.MAX_VALUE).byteSize();
            return srcSeg.getString(0);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to get compiled MSL source", t);
        }
    }

    public static String getCompiledMslError(MemorySegment compiledHandle) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment errSeg = (MemorySegment) getCompiledMslError.invoke(compiledHandle);
            if (isNullHandle(errSeg)) {
                return null;
            }
            return errSeg.getString(0);
        } catch (Throwable t) {
            return null;
        }
    }

    public static void freeCompiledShader(MemorySegment compiledHandle) {
        try {
            freeCompiledShader.invoke(compiledHandle);
        } catch (Throwable t) {
            Iris.logger.debug("freeCompiledShader failed", t);
        }
    }
}

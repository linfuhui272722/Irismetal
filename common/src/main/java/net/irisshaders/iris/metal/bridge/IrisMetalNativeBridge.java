package net.irisshaders.iris.metal.bridge;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.metal.IrisMetalDevice;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.*;
import java.nio.ByteBuffer;
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
    // 使用 metallum 的原生库
    private static final String MACOS_RESOURCE_PATH = "/natives/macos/libmetallum.dylib";
    private static final String IOS_RESOURCE_PATH = "/natives/ios/libmetallum.dylib";
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT;
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG;
    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT;
    private static final ValueLayout.OfDouble DOUBLE = ValueLayout.JAVA_DOUBLE;
    private static final Linker LINKER = Linker.nativeLinker();

    private static volatile boolean initialized = false;
    private static volatile boolean available = false;

    /**
     * 检测是否运行在 iOS 环境下。
     */
    private static boolean isIOS() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        String osArch = System.getProperty("os.arch", "").toLowerCase();
        
        // PojavLauncher / Amethyst on iOS
        if (System.getProperty("pojav.launcher") != null
                || System.getProperty("org.pojavlauncher") != null) {
            return true;
        }
        
        // iOS 上 java.io.tmpdir 和 user.home 总是在 /private/var/mobile/Containers/Data/Application/ 下
        String tmpDir = System.getProperty("java.io.tmpdir", "");
        String userHome = System.getProperty("user.home", "");
        if (tmpDir.contains("/var/mobile/") || tmpDir.contains("/var/containers/")
                || userHome.contains("/var/mobile/") || userHome.contains("/var/containers/")) {
            return true;
        }
        
        // Fallback: Darwin + aarch64 without a "Mac" os.name
        return osName.contains("darwin")
                && osArch.contains("aarch64")
                && !osName.contains("mac");
    }
    
    /**
     * 获取正确的资源路径。
     */
    private static String getResourcePath() {
        return isIOS() ? IOS_RESOURCE_PATH : MACOS_RESOURCE_PATH;
    }

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
     * 加载原生库并解析所有符号。仅在 macOS/iOS 上可成功。
     *
     * <p>本方法线程安全，可被多次调用。</p>
     */
    public static synchronized void ensureLoaded() {
        if (initialized) {
            return;
        }
        initialized = true;

        String osName = System.getProperty("os.name", "").toLowerCase();
        boolean isIOS = isIOS();
        
        if (!osName.contains("mac") && !osName.contains("darwin") && !isIOS) {
            Iris.logger.warn("Iris Metal backend requires macOS/iOS, current OS: {}. Metal backend disabled.", osName);
            available = false;
            return;
        }

        try {
            String resourcePath = getResourcePath();
            Path tempLib = Files.createTempFile("iris-metal-native-", ".dylib");
            tempLib.toFile().deleteOnExit();
            
            try (InputStream stream = IrisMetalNativeBridge.class.getResourceAsStream(resourcePath)) {
                if (stream == null) {
                    // 尝试备用的资源路径
                    String[] fallbackPaths = isIOS 
                        ? new String[]{"/natives/ios/libmetallum.dylib", "/natives/macos/libmetallum.dylib"}
                        : new String[]{"/natives/macos/libmetallum.dylib"};
                    
                    for (String fallback : fallbackPaths) {
                        try (InputStream fallbackStream = IrisMetalNativeBridge.class.getResourceAsStream(fallback)) {
                            if (fallbackStream != null) {
                                Iris.logger.info("Using fallback native library: {}", fallback);
                                Files.copy(fallbackStream, tempLib, StandardCopyOption.REPLACE_EXISTING);
                                resourcePath = fallback;
                                break;
                            }
                        }
                    }
                    
                    if (Files.size(tempLib) == 0) {
                        throw new IllegalStateException("Missing native library resource: " + resourcePath);
                    }
                } else {
                    Files.copy(stream, tempLib, StandardCopyOption.REPLACE_EXISTING);
                }
            }

            SymbolLookup lookup = SymbolLookup.libraryLookup(tempLib, Arena.global());
            resolveAll(lookup);
            available = true;
            Iris.logger.info("Iris Metal native bridge loaded successfully from: {}", resourcePath);
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
        createSystemDefaultDevice = downcall(lookup, "metallum_create_system_default_device",
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        copyDeviceName = downcall(lookup, "metallum_copy_device_name",
                FunctionDescriptor.of(INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG));
        deviceMakeCommandQueue = downcall(lookup, "metallum_MTLDevice_makeCommandQueue",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        commandQueueMakeCommandBuffer = downcall(lookup, "metallum_MTLCommandQueue_makeCommandBuffer",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        commandBufferCommit = downcall(lookup, "metallum_MTLCommandBuffer_commit",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        commandBufferWaitUntilCompleted = downcallWithoutCritical(lookup, "metallum_MTLCommandBuffer_waitUntilCompleted",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        commandBufferIsCompleted = downcall(lookup, "metallum_MTLCommandBuffer_isCompleted",
                FunctionDescriptor.of(INT, ValueLayout.ADDRESS));
        commandBufferPushDebugGroup = downcall(lookup, "metallum_MTLCommandBuffer_pushDebugGroup",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        commandBufferPopDebugGroup = downcall(lookup, "metallum_MTLCommandBuffer_popDebugGroup",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        commandBufferMakeRenderCommandEncoder = downcall(lookup, "metallum_MTLCommandBuffer_makeRenderCommandEncoder",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        DOUBLE, DOUBLE, INT, FLOAT, FLOAT, FLOAT, FLOAT, INT, DOUBLE));
        commandBufferMakeBlitCommandEncoder = downcall(lookup, "metallum_MTLCommandBuffer_makeBlitCommandEncoder",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        commandBufferMakeComputeCommandEncoder = optionalDowncall(lookup, "metallum_MTLCommandBuffer_makeComputeCommandEncoder",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        commandEncoderEndEncoding = downcall(lookup, "metallum_MTLCommandEncoder_endEncoding",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

        // 纹理
        createTexture2D = downcall(lookup, "metallum_create_texture_2d",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, LONG, LONG, LONG, LONG, LONG, INT, INT, ValueLayout.ADDRESS));
        createTexture3D = optionalDowncall(lookup, "metallum_create_texture_3d",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, LONG, LONG, LONG, LONG, INT, INT, ValueLayout.ADDRESS));
        createTextureCube = optionalDowncall(lookup, "metallum_create_texture_cube",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, LONG, LONG, LONG, INT, INT, ValueLayout.ADDRESS));
        textureReplaceRegion = downcall(lookup, "metallum_texture_replace_region",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, INT, LONG));
        textureGetBytes = downcall(lookup, "metallum_texture_get_bytes",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, INT, LONG));
        releaseObject = downcall(lookup, "metallum_release_object",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

        // Buffer
        createBuffer = downcall(lookup, "metallum_create_buffer",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
        bufferContents = downcall(lookup, "metallum_get_buffer_contents",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        bufferReplaceRegion = downcall(lookup, "metallum_buffer_replace_region",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG));

        // Render Pipeline
        compileRenderPipeline = downcall(lookup, "metallum_compile_render_pipeline",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS));
        compileComputePipeline = optionalDowncall(lookup, "metallum_compile_compute_pipeline",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        renderEncoderSetRenderPipelineState = downcall(lookup, "metallum_renderEncoder_setRenderPipelineState",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        renderEncoderSetDepthStencilState = downcall(lookup, "metallum_renderEncoder_setDepthStencilState",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        renderEncoderSetDepthBias = downcall(lookup, "metallum_renderEncoder_setDepthBias",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, FLOAT, FLOAT, FLOAT));
        renderEncoderSetFrontFacingWinding = downcall(lookup, "metallum_renderEncoder_setFrontFacingWinding",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT));
        renderEncoderSetCullMode = downcall(lookup, "metallum_renderEncoder_setCullMode",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT));
        renderEncoderSetTriangleFillMode = downcall(lookup, "metallum_renderEncoder_setTriangleFillMode",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT));
        renderEncoderSetBuffer = downcall(lookup, "metallum_renderEncoder_setBuffer",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, INT));
        renderEncoderSetBufferOffset = downcall(lookup, "metallum_renderEncoder_setBufferOffset",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, INT));
        renderEncoderSetTexture = downcall(lookup, "metallum_renderEncoder_setTexture",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
        renderEncoderSetSamplerState = downcall(lookup, "metallum_renderEncoder_setSamplerState",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
        renderEncoderSetScissorRect = downcall(lookup, "metallum_renderEncoder_setScissorRect",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, LONG));
        renderEncoderSetViewport = downcall(lookup, "metallum_renderEncoder_setViewport",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, DOUBLE, DOUBLE, DOUBLE, DOUBLE, DOUBLE, DOUBLE, LONG, LONG));
        renderEncoderSetBlendColor = downcall(lookup, "metallum_renderEncoder_setBlendColor",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, FLOAT, FLOAT, FLOAT, FLOAT));
        renderEncoderSetColorWriteMask = downcall(lookup, "metallum_renderEncoder_setColorWriteMask",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT));
        renderEncoderDrawPrimitives = downcall(lookup, "metallum_renderEncoder_drawPrimitives",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT, LONG, LONG, LONG));
        renderEncoderDrawIndexedPrimitives = downcall(lookup, "metallum_renderEncoder_drawIndexedPrimitives",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT, ValueLayout.ADDRESS, LONG, LONG, LONG));
        renderEncoderDrawPrimitivesInstanced = downcall(lookup, "metallum_renderEncoder_drawPrimitivesInstanced",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT, LONG, LONG, LONG, LONG));
        renderEncoderDrawIndexedPrimitivesInstanced = downcall(lookup, "metallum_renderEncoder_drawIndexedPrimitivesInstanced",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG));

        // Compute (使用 optionalDowncall，因为 iOS 原生库可能不包含这些符号)
        computeEncoderSetComputePipelineState = optionalDowncall(lookup, "metallum_computeEncoder_setComputePipelineState",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        computeEncoderSetBuffer = optionalDowncall(lookup, "metallum_computeEncoder_setBuffer",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
        computeEncoderSetTexture = optionalDowncall(lookup, "metallum_computeEncoder_setTexture",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT));
        computeEncoderSetSamplerState = optionalDowncall(lookup, "metallum_computeEncoder_setSamplerState",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT));
        computeEncoderDispatchThreadgroups = optionalDowncall(lookup, "metallum_computeEncoder_dispatchThreadgroups",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT, INT, INT, INT, INT, INT));

        // DepthStencil
        makeDepthStencilState = downcall(lookup, "metallum_MTLDevice_makeDepthStencilState",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, INT, INT, INT, INT, INT, INT, INT, INT));

        // Sampler
        makeSamplerState = downcall(lookup, "metallum_MTLDevice_makeSamplerState",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, INT, INT, INT, INT, INT, INT, FLOAT, FLOAT, FLOAT, FLOAT, INT, INT));

        // Blit
        blitCopyBufferToBuffer = downcall(lookup, "metallum_blitEncoder_copyBufferToBuffer",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG, LONG));
        blitCopyBufferToTexture = downcall(lookup, "metallum_blitEncoder_copyBufferToTexture",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG));
        blitCopyTextureToTexture = downcall(lookup, "metallum_blitEncoder_copyTextureToTexture",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG));
        blitCopyTextureToBuffer = downcall(lookup, "metallum_blitEncoder_copyTextureToBuffer",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG));
        blitGenerateMipmaps = downcall(lookup, "metallum_blitEncoder_generateMipmaps",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        // SPIRV-Cross shader 编译
        compileGlslToMsl = downcall(lookup, "metallum_compile_glsl_to_msl",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        getCompiledMslSource = downcall(lookup, "metallum_get_compiled_msl_source",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        getCompiledMslError = downcall(lookup, "metallum_get_compiled_msl_error",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        freeCompiledShader = downcall(lookup, "metallum_free_compiled_shader",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    }

    private static MethodHandle downcall(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        MethodHandle handle = lookup.find(name).map(symbol -> LINKER.downcallHandle(symbol, desc)).orElse(null);
        if (handle == null) {
            throw new IllegalStateException("Missing native symbol: " + name);
        }
        return handle;
    }

    /**
     * 可选的 downcall，当符号不存在时返回 null 而不是抛出异常。
     * 用于 iOS 原生库中可能不存在的可选符号。
     */
    private static MethodHandle optionalDowncall(SymbolLookup lookup, String name, FunctionDescriptor desc) {
        return lookup.find(name)
                .map(symbol -> LINKER.downcallHandle(symbol, desc, Linker.Option.critical(false)))
                .orElse(null);
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

    // 检查可选功能是否可用
    public static boolean isTexture3DAvailable() {
        return createTexture3D != null;
    }

    public static boolean isTextureCubeAvailable() {
        return createTextureCube != null;
    }

    public static boolean isComputePipelineAvailable() {
        return compileComputePipeline != null;
    }

    // 辅助方法：分配UTF-8字符串到内存段 (Java 22+兼容)
    private static MemorySegment allocateUtf8String(Arena arena, String str) {
        byte[] bytes = str.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MemorySegment segment = arena.allocate(bytes.length + 1);
        segment.set(ValueLayout.JAVA_BYTE, 0, (byte) 0);  // null terminator
        for (int i = 0; i < bytes.length; i++) {
            segment.set(ValueLayout.JAVA_BYTE, i, bytes[i]);
        }
        return segment;
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
            MemorySegment nameSeg = allocateUtf8String(arena, name);
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

    // 简化的makeRenderCommandEncoder版本（使用passDescriptor）
    public static MemorySegment makeRenderCommandEncoder(MemorySegment buffer, MemorySegment passDescriptor) {
        // 简化实现：返回NULL
        return MemorySegment.NULL;
    }

    public static MemorySegment makeBlitCommandEncoder(MemorySegment buffer) {
        try {
            return (MemorySegment) commandBufferMakeBlitCommandEncoder.invoke(buffer);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create blit command encoder", t);
        }
    }

    public static MemorySegment makeComputeCommandEncoder(MemorySegment buffer) {
        if (commandBufferMakeComputeCommandEncoder == null) {
            throw new UnsupportedOperationException("Compute command encoder is not available on this platform");
        }
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
            MemorySegment labelSeg = allocateUtf8String(arena, label);
            return (MemorySegment) createTexture2D.invoke(device, pixelFormat, width, height, depthOrLayers, mipLevels, sampleCount, usage, storageMode, labelSeg);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create 2D texture", t);
        }
    }

    public static MemorySegment createTexture3D(MemorySegment device, int pixelFormat, long width, long height,
                                                 long depth, long mipLevels, int usage, int storageMode, String label) {
        if (createTexture3D == null) {
            return MemorySegment.NULL;  // 返回 NULL 而不是抛异常，让调用者处理
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment labelSeg = allocateUtf8String(arena, label);
            return (MemorySegment) createTexture3D.invoke(device, pixelFormat, width, height, depth, mipLevels, usage, storageMode, labelSeg);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create 3D texture", t);
        }
    }

    public static MemorySegment createTextureCube(MemorySegment device, int pixelFormat, long size, long mipLevels,
                                                   int usage, int storageMode, String label) {
        if (createTextureCube == null) {
            return MemorySegment.NULL;  // 返回 NULL 而不是抛异常，让调用者处理
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment labelSeg = allocateUtf8String(arena, label);
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

    // 别名方法
    public static MemorySegment getBufferContents(MemorySegment buffer) {
        return bufferContents(buffer);
    }

    public static MemorySegment createEmptyBuffer(MemorySegment device, long size, int options) {
        return createBuffer(device, size, options);
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
            MemorySegment vSrc = allocateUtf8String(arena, vertexMslSource);
            MemorySegment fSrc = allocateUtf8String(arena, fragmentMslSource);
            MemorySegment vName = allocateUtf8String(arena, vertexFunctionName);
            MemorySegment fName = allocateUtf8String(arena, fragmentFunctionName);
            MemorySegment labelSeg = allocateUtf8String(arena, label);
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
        if (compileComputePipeline == null) {
            return MemorySegment.NULL;  // 返回 NULL 而不是抛异常，让调用者处理
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = allocateUtf8String(arena, mslSource);
            MemorySegment name = allocateUtf8String(arena, functionName);
            MemorySegment labelSeg = allocateUtf8String(arena, label);
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

    public static void renderEncoderSetTextureAndSampler(MemorySegment encoder, MemorySegment texture, MemorySegment sampler, int slot) {
        // 简化的实现：分别设置纹理和采样器
        try {
            if (!isNullHandle(texture)) {
                renderEncoderSetTexture.invoke(encoder, texture, 0, slot);
            }
            if (!isNullHandle(sampler)) {
                renderEncoderSetSamplerState.invoke(encoder, sampler, 0, slot);
            }
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
        if (computeEncoderSetComputePipelineState == null) {
            throw new UnsupportedOperationException("Compute pipeline state is not available on this platform");
        }
        try {
            computeEncoderSetComputePipelineState.invoke(encoder, pipeline);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    // drawIndexedPrimitives的简化版本（indexBuffer需要单独设置）
    public static void drawIndexedPrimitives(MemorySegment encoder, int primitiveType, int indexCount, int indexType,
                                            long indexBufferOffset, int instanceCount) {
        // 简化实现：indexBuffer需要通过setIndexBuffer设置
        // 这里假设indexBuffer已经通过setIndexBuffer设置过了
        try {
            renderEncoderDrawIndexedPrimitives.invoke(encoder, primitiveType, MemorySegment.NULL, indexCount, indexBufferOffset, instanceCount);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    // drawIndexedPrimitives的完整版本
    public static void drawIndexedPrimitives(MemorySegment encoder, int primitiveType, MemorySegment indexBuffer,
                                            int indexCount, int indexType, int instanceCount) {
        try {
            renderEncoderDrawIndexedPrimitives.invoke(encoder, primitiveType, indexBuffer, indexCount, 0, instanceCount);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void computeEncoderSetBuffer(MemorySegment encoder, MemorySegment buffer, long offset, int index) {
        if (computeEncoderSetBuffer == null) {
            throw new UnsupportedOperationException("Compute buffer setting is not available on this platform");
        }
        try {
            computeEncoderSetBuffer.invoke(encoder, buffer, offset, index);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void computeEncoderSetTexture(MemorySegment encoder, MemorySegment texture, int index) {
        if (computeEncoderSetTexture == null) {
            throw new UnsupportedOperationException("Compute texture setting is not available on this platform");
        }
        try {
            computeEncoderSetTexture.invoke(encoder, texture, index);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void computeEncoderSetSamplerState(MemorySegment encoder, MemorySegment sampler, int index) {
        if (computeEncoderSetSamplerState == null) {
            throw new UnsupportedOperationException("Compute sampler state is not available on this platform");
        }
        try {
            computeEncoderSetSamplerState.invoke(encoder, sampler, index);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void computeEncoderDispatchThreadgroups(MemorySegment encoder, int groupsX, int groupsY, int groupsZ,
                                                           int threadsX, int threadsY, int threadsZ) {
        if (computeEncoderDispatchThreadgroups == null) {
            throw new UnsupportedOperationException("Compute dispatch is not available on this platform");
        }
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

    // 简化的createSamplerState方法
    public static MemorySegment createSamplerState(MemorySegment device, int minFilter, int magFilter, int mipFilter,
                                                   int sAddressMode, int tAddressMode, int rAddressMode,
                                                   int maxAnisotropy, int compareFunction) {
        return makeSamplerState(device, minFilter, magFilter, mipFilter,
                sAddressMode, tAddressMode, rAddressMode, compareFunction,
                0.0f, Float.MAX_VALUE, maxAnisotropy > 0 ? maxAnisotropy : 1.0f, 1, 0);
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

    // 简化的blitCopyBufferToTexture版本（参数顺序适配）
    public static void blitCopyBufferToTexture(MemorySegment encoder, MemorySegment stagingBuffer,
                                               long sourceOffset, long bytesPerRow, long bytesPerImage,
                                               MemorySegment texture,
                                               int slice, int mipLevel,
                                               int x, int y, int z,
                                               int width, int height, int depth) {
        blitCopyBufferToTexture(encoder, stagingBuffer, sourceOffset, texture, slice, mipLevel,
                x, y, z, width, height, depth, bytesPerRow, bytesPerImage);
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

    // 简化的blitCopyTextureToBuffer版本
    public static void blitCopyTextureToBuffer(MemorySegment encoder, MemorySegment texture,
                                               MemorySegment buffer,
                                               int srcSlice, int srcLevel,
                                               int x, int y, int z,
                                               int width, int height, int depth,
                                               int bytesPerRow, int bytesPerImage) {
        blitCopyTextureToBuffer(encoder, texture, buffer,
                srcSlice, srcLevel, x, y, z, width, height, depth, bytesPerRow, bytesPerImage);
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
    // 简化的compileGlslToMsl版本（接受byte数组）
    public static MemorySegment compileGlslToMsl(byte[] spirv, int spirvLength, int stage, int mslVersion) {
        // 简化实现：返回NULL表示编译失败
        return MemorySegment.NULL;
    }

    public static MemorySegment compileGlslToMsl(String glslSource, int stage, String entryPoint) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment src = allocateUtf8String(arena, glslSource);
            MemorySegment entry = allocateUtf8String(arena, entryPoint);
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

    // ===== 简化的包装方法（MetalRenderPassEncoder 使用的别名）=====
    
    public static void setRenderPipelineState(MemorySegment encoder, MemorySegment pipeline) {
        renderEncoderSetRenderPipelineState(encoder, pipeline);
    }

    public static void setViewport(MemorySegment encoder, int x, int y, int width, int height, float near, float far) {
        renderEncoderSetViewport(encoder, x, y, width, height, near, far, 0, 0);
    }

    public static void setScissorRect(MemorySegment encoder, int x, int y, int width, int height) {
        renderEncoderSetScissorRect(encoder, x, y, width, height);
    }

    public static void setDepthState(MemorySegment encoder, int depthTest, int depthWrite, int depthCompareFunction) {
        // 简化实现：创建一个临时的depth stencil state
        MemorySegment device = IrisMetalDevice.get().deviceHandle();
        MemorySegment state = makeDepthStencilState(device, depthCompareFunction, depthWrite,
                0, 0, 0, 0, 0, 0, 0);
        renderEncoderSetDepthStencilState(encoder, state);
        releaseObject(state);
    }

    public static void setStencilState(MemorySegment encoder, int stencilTest, int stencilCompareFunction,
                                       int stencilReference, int stencilReadMask, int stencilWriteMask,
                                       int stencilFailureOperation, int depthFailureOperation,
                                       int depthStencilPassOperation) {
        // 简化实现：创建一个临时的depth stencil state
        MemorySegment device = IrisMetalDevice.get().deviceHandle();
        MemorySegment state = makeDepthStencilState(device, 0, 0,
                stencilCompareFunction, stencilWriteMask, stencilReadMask,
                stencilFailureOperation, depthFailureOperation, depthStencilPassOperation, 0);
        renderEncoderSetDepthStencilState(encoder, state);
        releaseObject(state);
    }

    public static void setColorWriteMask(MemorySegment encoder, int attachmentIndex, int mask) {
        // 简化实现：仅设置第一个attachment的color write mask
        if (attachmentIndex == 0) {
            renderEncoderSetColorWriteMask(encoder, mask);
        }
    }

    // setViewport的简化版本（无scissor参数）
    public static void setViewport(MemorySegment encoder, int x, int y, int width, int height) {
        renderEncoderSetViewport(encoder, x, y, width, height, 0.0, 1.0, 0, 0);
    }

    // setBlendColor包装方法
    public static void setBlendColor(MemorySegment encoder, float r, float g, float b, float a) {
        renderEncoderSetBlendColor(encoder, r, g, b, a);
    }

    // setScissorEnabled包装方法（Metal没有直接对应，使用setScissorRect实现）
    public static void setScissorEnabled(MemorySegment encoder, boolean enabled) {
        if (!enabled) {
            // Metal中禁用scissor可以通过设置一个覆盖整个渲染区域的大scissor rect来实现
            renderEncoderSetScissorRect(encoder, 0, 0, Long.MAX_VALUE, Long.MAX_VALUE);
        }
    }

    // Texture binding methods
    public static void setFragmentTexture(MemorySegment encoder, int slot, MemorySegment texture) {
        renderEncoderSetTexture(encoder, texture, 0, slot);
    }

    public static void setVertexTexture(MemorySegment encoder, int slot, MemorySegment texture) {
        renderEncoderSetTexture(encoder, texture, 0, slot);
    }

    // RenderPass相关方法
    public static MemorySegment createRenderPassDescriptor() {
        // 简化实现：返回一个假的handle
        // 实际实现需要在原生层创建MTLRenderPassDescriptor
        return MemorySegment.NULL;
    }

    public static void setRenderPassColorAttachment(MemorySegment descriptor, int index, MemorySegment texture,
                                                   int level, int slice, boolean loadAction, float[] clearColor, boolean storeAction) {
        // 简化实现
    }

    public static void setRenderPassDepthAttachment(MemorySegment descriptor, MemorySegment texture,
                                                   boolean loadAction, float clearDepth, boolean storeAction) {
        // 简化实现
    }

    public static void setRenderPassStencilAttachment(MemorySegment descriptor, MemorySegment texture,
                                                     boolean loadAction, int clearStencil, boolean storeAction) {
        // 简化实现
    }

    // ===== Buffer/Unifrom相关方法 =====
    public static void setVertexBuffer(MemorySegment encoder, int index, MemorySegment buffer, long offset) {
        renderEncoderSetBuffer(encoder, buffer, offset, Long.MAX_VALUE, index);
    }

    public static void setIndexBuffer(MemorySegment encoder, MemorySegment buffer, int indexType) {
        // 简化实现：需要原生层支持
    }

    public static void setVertexBytes(MemorySegment encoder, int slot, byte[] data, int length) {
        // 简化实现
    }

    public static void setVertexBytes(MemorySegment encoder, int slot, ByteBuffer data, int length) {
        // 简化实现：ByteBuffer版本
        if (data != null && data.hasArray()) {
            byte[] arr = data.array();
            int offset = data.arrayOffset() + data.position();
            int copyLen = Math.min(length, data.remaining());
            setVertexBytes(encoder, slot, arr, copyLen);
        }
    }

    public static void setFragmentBytes(MemorySegment encoder, int slot, byte[] data, int length) {
        // 简化实现
    }

    public static void setFragmentBytes(MemorySegment encoder, int slot, ByteBuffer data, int length) {
        // 简化实现：ByteBuffer版本
        if (data != null && data.hasArray()) {
            byte[] arr = data.array();
            int offset = data.arrayOffset() + data.position();
            int copyLen = Math.min(length, data.remaining());
            setFragmentBytes(encoder, slot, arr, copyLen);
        }
    }

    public static void setVertexBufferObject(MemorySegment encoder, int slot, MemorySegment buffer, long offset) {
        renderEncoderSetBuffer(encoder, buffer, offset, Long.MAX_VALUE, slot);
    }

    public static void setFragmentBufferObject(MemorySegment encoder, int slot, MemorySegment buffer, long offset) {
        renderEncoderSetBuffer(encoder, buffer, offset, Long.MAX_VALUE, slot);
    }

    // ===== Sampler binding =====
    public static void setFragmentSamplerState(MemorySegment encoder, int slot, MemorySegment sampler) {
        renderEncoderSetSamplerState(encoder, sampler, 0, slot);
    }

    public static void setVertexSamplerState(MemorySegment encoder, int slot, MemorySegment sampler) {
        renderEncoderSetSamplerState(encoder, sampler, 0, slot);
    }

    // ===== Draw =====
    public static void drawPrimitives(MemorySegment encoder, int primitiveType, int vertexStart, int vertexCount, int instanceCount) {
        renderEncoderDrawPrimitives(encoder, primitiveType, vertexStart, vertexCount, instanceCount);
    }

    // ===== Pipeline reflection (简化实现) =====
    public static String[] getPipelineSamplerNames(MemorySegment pipelineState) {
        // 简化实现：返回空数组
        return new String[0];
    }

    public static int[] getPipelineSamplerSlots(MemorySegment pipelineState) {
        return new int[0];
    }

    public static String[] getPipelineImageNames(MemorySegment pipelineState) {
        return new String[0];
    }

    public static int[] getPipelineImageSlots(MemorySegment pipelineState) {
        return new int[0];
    }

    public static String[] getPipelineUniformNames(MemorySegment pipelineState) {
        return new String[0];
    }

    public static int[] getPipelineUniformOffsets(MemorySegment pipelineState) {
        return new int[0];
    }

    public static int[] getPipelineUniformSizes(MemorySegment pipelineState) {
        return new int[0];
    }

    public static int[] getPipelineUniformStages(MemorySegment pipelineState) {
        return new int[0];
    }

    // createBuffer简化版本（参数适配）
    public static MemorySegment createBuffer(MemorySegment device, byte[] data, long length, int options) {
        // 创建一个buffer并复制数据
        MemorySegment buffer = createBuffer(device, length, options);
        if (!isNullHandle(buffer) && data != null) {
            // 复制数据到buffer
            for (int i = 0; i < data.length && i < length; i++) {
                buffer.set(ValueLayout.JAVA_BYTE, i, data[i]);
            }
        }
        return buffer;
    }

    // createBuffer的ByteBuffer版本
    public static MemorySegment createBuffer(MemorySegment device, ByteBuffer data, long length, int options) {
        MemorySegment buffer = createBuffer(device, length, options);
        if (!isNullHandle(buffer) && data != null && data.hasArray()) {
            byte[] arr = data.array();
            int offset = data.arrayOffset() + data.position();
            int copyLen = (int) Math.min(length, data.remaining());
            for (int i = 0; i < copyLen; i++) {
                buffer.set(ValueLayout.JAVA_BYTE, i, arr[offset + i]);
            }
        }
        return buffer;
    }

    // ===== Shader/Pipeline编译方法 =====
    public static MemorySegment compileMslToLibrary(MemorySegment device, String mslSource, String label) {
        // 简化实现：返回NULL表示编译失败
        return MemorySegment.NULL;
    }

    public static MemorySegment getLibraryFunction(MemorySegment library, String functionName) {
        // 简化实现
        return MemorySegment.NULL;
    }

    public static MemorySegment createRenderPipelineDescriptor() {
        // 简化实现
        return MemorySegment.NULL;
    }

    public static void setPipelineVertexFunction(MemorySegment descriptor, MemorySegment function) {
        // 简化实现
    }

    public static void setPipelineFragmentFunction(MemorySegment descriptor, MemorySegment function) {
        // 简化实现
    }

    public static void setPipelineColorAttachment(MemorySegment descriptor, int index, int pixelFormat,
                                                 int blendEnabled,
                                                 int srcBlend, int dstBlend, int blendOp,
                                                 int srcBlendAlpha, int dstBlendAlpha, int blendOpAlpha) {
        // 简化实现
    }

    public static void setPipelineDepthAttachmentPixelFormat(MemorySegment descriptor, int pixelFormat) {
        // 简化实现
    }

    public static void setPipelineVertexDescriptor(MemorySegment descriptor, MemorySegment vertexDescriptor) {
        // 简化实现
    }

    public static MemorySegment newRenderPipelineState(MemorySegment device, MemorySegment descriptor) {
        // 简化实现
        return MemorySegment.NULL;
    }

    public static void uploadBufferData(MemorySegment buffer, ByteBuffer data, long size) {
        // 简化实现
    }
}

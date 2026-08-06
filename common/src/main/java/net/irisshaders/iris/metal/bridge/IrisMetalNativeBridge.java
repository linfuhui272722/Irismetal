package net.irisshaders.iris.metal.bridge;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.metal.IrisMetalDevice;
import net.irisshaders.iris.metal.shader.SPIRVToMslConverter;
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
    private static MethodHandle createShaderFunction;
    private static MethodHandle renderPipelineDescriptorCreate;
    private static MethodHandle renderPipelineDescriptorSetCompiledFunctions;
    private static MethodHandle renderPipelineDescriptorSetVertexDescriptor;
    private static MethodHandle renderPipelineDescriptorSetAttachmentFormats;
    private static MethodHandle renderPipelineDescriptorSetColorAttachmentFormat;
    private static MethodHandle renderPipelineDescriptorSetDepthStencilFormats;
    private static MethodHandle renderPipelineDescriptorSetColorAttachmentBlendState;
    private static MethodHandle renderEncoderSetRenderPipelineState;
    private static MethodHandle renderEncoderSetDepthStencilState;
    private static MethodHandle renderEncoderSetDepthBias;
    private static MethodHandle renderEncoderSetFrontFacingWinding;
    private static MethodHandle renderEncoderSetCullMode;
    private static MethodHandle renderEncoderSetTriangleFillMode;
    private static MethodHandle renderEncoderSetBuffer;
    private static MethodHandle renderEncoderSetBufferOffset;
    private static MethodHandle renderEncoderSetTexture;
    private static MethodHandle renderEncoderSetTextureAndSampler;  // metallum 的组合方法
    private static MethodHandle renderEncoderSetSamplerState;      // 设为 null，将在 Java 层模拟
    private static MethodHandle renderEncoderSetScissorRect;
    private static MethodHandle renderEncoderSetViewport;           // 设为 null，metallum 不支持
    private static MethodHandle renderEncoderSetBlendColor;         // 设为 null，metallum 不支持
    private static MethodHandle renderEncoderSetColorWriteMask;     // 设为 null，metallum 不支持
    private static MethodHandle renderEncoderDrawPrimitives;
    private static MethodHandle renderEncoderDrawIndexedPrimitives;
    private static MethodHandle renderEncoderDrawPrimitivesInstanced;    // 设为 null，将在 Java 层模拟
    private static MethodHandle renderEncoderDrawIndexedPrimitivesInstanced;  // 设为 null，将在 Java 层模拟

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
        // Note: metallum_create_texture_2d 不需要 device 参数，它内部自己获取系统默认设备
        // 参数顺序: pixelFormat, width, height, depthOrLayers, mipLevels, cubeCompatible, usage, storageMode, label
        createTexture2D = downcall(lookup, "metallum_create_texture_2d",
                FunctionDescriptor.of(ValueLayout.ADDRESS, INT, LONG, LONG, LONG, LONG, LONG, INT, INT, ValueLayout.ADDRESS));
        // Note: metallum_create_texture 需要 device 参数，用于创建 3D 和 cube 纹理
        // 参数顺序: device, pixelFormat, width, height, depthOrLayers, mipLevels, dimension, cubeCompatible, usage, storageMode, label
        createTexture3D = optionalDowncall(lookup, "metallum_create_texture",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, LONG, LONG, LONG, LONG, LONG, LONG, INT, INT, ValueLayout.ADDRESS));
        createTextureCube = optionalDowncall(lookup, "metallum_create_texture",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, LONG, LONG, LONG, LONG, LONG, LONG, INT, INT, ValueLayout.ADDRESS));
        textureReplaceRegion = optionalDowncall(lookup, "metallum_texture_replace_region",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, INT, LONG));
        textureGetBytes = optionalDowncall(lookup, "metallum_texture_get_bytes",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, INT, LONG));
        releaseObject = downcall(lookup, "metallum_release_object",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

        // Buffer
        createBuffer = downcall(lookup, "metallum_create_buffer",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
        bufferContents = downcall(lookup, "metallum_get_buffer_contents",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        bufferReplaceRegion = optionalDowncall(lookup, "metallum_buffer_replace_region",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG));

        // Render Pipeline (可选，因为可能使用预编译的 pipeline)
        compileRenderPipeline = optionalDowncall(lookup, "metallum_MTLDevice_makeRenderPipelineState",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        compileComputePipeline = optionalDowncall(lookup, "metallum_MTLDevice_makeComputePipelineState",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        
        // Shader Function (metallum_create_shader_function)
        createShaderFunction = optionalDowncall(lookup, "metallum_create_shader_function",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        
        // Render Pipeline Descriptor
        renderPipelineDescriptorCreate = optionalDowncall(lookup, "metallum_MTLRenderPipelineDescriptor_create",
                FunctionDescriptor.of(ValueLayout.ADDRESS));
        renderPipelineDescriptorSetCompiledFunctions = optionalDowncall(lookup, "metallum_MTLRenderPipelineDescriptor_setCompiledFunctions",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        renderPipelineDescriptorSetVertexDescriptor = optionalDowncall(lookup, "metallum_MTLRenderPipelineDescriptor_setVertexDescriptor",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        renderPipelineDescriptorSetAttachmentFormats = optionalDowncall(lookup, "metallum_MTLRenderPipelineDescriptor_setAttachmentFormats",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT, INT, INT));
        renderPipelineDescriptorSetColorAttachmentFormat = optionalDowncall(lookup, "metallum_MTLRenderPipelineDescriptor_setColorAttachmentFormat",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT, INT));
        renderPipelineDescriptorSetDepthStencilFormats = optionalDowncall(lookup, "metallum_MTLRenderPipelineDescriptor_setDepthStencilFormats",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT, INT));
        renderPipelineDescriptorSetColorAttachmentBlendState = optionalDowncall(lookup, "metallum_MTLRenderPipelineDescriptor_setColorAttachmentBlendState",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT, INT, INT, INT, INT, INT, INT, INT, INT));

        // Render Encoder (使用 optionalDowncall 以兼容不同的 metallum 版本)
        renderEncoderSetRenderPipelineState = optionalDowncall(lookup, "metallum_MTLRenderCommandEncoder_setRenderPipelineState",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        renderEncoderSetDepthStencilState = optionalDowncall(lookup, "metallum_MTLRenderCommandEncoder_setDepthStencilState",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        renderEncoderSetDepthBias = optionalDowncall(lookup, "metallum_MTLRenderCommandEncoder_setDepthBias",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, FLOAT, FLOAT, FLOAT));
        renderEncoderSetFrontFacingWinding = optionalDowncall(lookup, "metallum_MTLRenderCommandEncoder_setFrontFacingWinding",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT));
        renderEncoderSetCullMode = optionalDowncall(lookup, "metallum_MTLRenderCommandEncoder_setCullMode",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT));
        renderEncoderSetTriangleFillMode = optionalDowncall(lookup, "metallum_MTLRenderCommandEncoder_setTriangleFillMode",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT));
        renderEncoderSetBuffer = optionalDowncall(lookup, "metallum_MTLRenderCommandEncoder_setBuffer",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, INT));
        renderEncoderSetBufferOffset = optionalDowncall(lookup, "metallum_MTLRenderCommandEncoder_setBufferOffset",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, INT));
        renderEncoderSetTexture = optionalDowncall(lookup, "metallum_MTLRenderCommandEncoder_setTexture",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
        // metallum 有 setTextureAndSampler 组合方法
        renderEncoderSetTextureAndSampler = optionalDowncall(lookup, "metallum_MTLRenderCommandEncoder_setTextureAndSampler",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
        // 注意：metallum 没有单独的 setSamplerState，需要使用 setTextureAndSampler
        // 单独的 sampler 设置将在 Java 层模拟
        renderEncoderSetSamplerState = null; // 不存在，用 setTextureAndSampler 代替
        renderEncoderSetScissorRect = optionalDowncall(lookup, "metallum_MTLRenderCommandEncoder_setScissorRect",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, LONG, LONG, LONG, LONG));
        // setViewport 不存在于 metallum，将在 Java 层跳过
        renderEncoderSetViewport = null;
        // setBlendColor 不存在于 metallum，将在 Java 层跳过
        renderEncoderSetBlendColor = null;
        // setColorWriteMask 不存在于 metallum，将在 Java 层跳过
        renderEncoderSetColorWriteMask = null;
        renderEncoderDrawPrimitives = optionalDowncall(lookup, "metallum_MTLRenderCommandEncoder_drawPrimitives",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT, LONG, LONG, LONG));
        renderEncoderDrawIndexedPrimitives = optionalDowncall(lookup, "metallum_MTLRenderCommandEncoder_drawIndexedPrimitives",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT, ValueLayout.ADDRESS, LONG, LONG, LONG));
        // instanced 绘制不直接支持，需要通过 Java 层模拟
        renderEncoderDrawPrimitivesInstanced = null;
        renderEncoderDrawIndexedPrimitivesInstanced = null;

        // Compute (可选，iOS metallum 可能不包含)
        computeEncoderSetComputePipelineState = optionalDowncall(lookup, "metallum_MTLComputeCommandEncoder_setComputePipelineState",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        computeEncoderSetBuffer = optionalDowncall(lookup, "metallum_MTLComputeCommandEncoder_setBuffer",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, INT));
        computeEncoderSetTexture = optionalDowncall(lookup, "metallum_MTLComputeCommandEncoder_setTexture",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT));
        computeEncoderSetSamplerState = optionalDowncall(lookup, "metallum_MTLComputeCommandEncoder_setSamplerState",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT));
        computeEncoderDispatchThreadgroups = optionalDowncall(lookup, "metallum_MTLComputeCommandEncoder_dispatchThreadgroups",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, INT, INT, INT, INT, INT, INT));

        // DepthStencil (可选)
        makeDepthStencilState = optionalDowncall(lookup, "metallum_MTLDevice_makeDepthStencilState",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, INT, INT, INT, INT, INT, INT, INT, INT));

        // Sampler (可选)
        makeSamplerState = optionalDowncall(lookup, "metallum_create_sampler",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, INT, INT, INT, INT, INT, DOUBLE));

        // Blit (可选)
        blitCopyBufferToBuffer = optionalDowncall(lookup, "metallum_MTLBlitCommandEncoder_copyFromBufferToBuffer",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG, LONG));
        blitCopyBufferToTexture = optionalDowncall(lookup, "metallum_MTLBlitCommandEncoder_copyFromBufferToTexture",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG));
        blitCopyTextureToTexture = optionalDowncall(lookup, "metallum_MTLBlitCommandEncoder_copyFromTextureToTexture",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG));
        blitCopyTextureToBuffer = optionalDowncall(lookup, "metallum_MTLBlitCommandEncoder_copyFromTextureToBuffer",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG, LONG));
        blitGenerateMipmaps = optionalDowncall(lookup, "metallum_MTLBlitCommandEncoder_generateMipmaps",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        // SPIRV-Cross shader 编译 (可选，因为 shader 可能在构建时预编译)
        compileGlslToMsl = optionalDowncall(lookup, "metallum_compile_glsl_to_msl",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        getCompiledMslSource = optionalDowncall(lookup, "metallum_get_compiled_msl_source",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        getCompiledMslError = optionalDowncall(lookup, "metallum_get_compiled_msl_error",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        freeCompiledShader = optionalDowncall(lookup, "metallum_free_compiled_shader",
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

    public static boolean isTextureReplaceRegionAvailable() {
        return textureReplaceRegion != null;
    }

    public static boolean isTextureGetBytesAvailable() {
        return textureGetBytes != null;
    }

    public static boolean isBlitGenerateMipmapsAvailable() {
        return blitGenerateMipmaps != null;
    }

    public static boolean isShaderCompilerAvailable() {
        return compileGlslToMsl != null && getCompiledMslSource != null && getCompiledMslError != null && freeCompiledShader != null;
    }

    public static boolean isBufferReplaceRegionAvailable() {
        return bufferReplaceRegion != null;
    }

    public static boolean isRenderPipelineCompilationAvailable() {
        return compileRenderPipeline != null;
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
    public static MemorySegment getSystemDefaultDevice() {
        try {
            return (MemorySegment) createSystemDefaultDevice.invoke();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to get system default Metal device", t);
        }
    }

    public static String copyDeviceName(MemorySegment device) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment buf = arena.allocate(256);
            int result = (int) copyDeviceName.invoke(device, buf, 255L);
            // Swift returns 0 on success, 1 on error
            if (result != 0) {
                return "Unknown Metal Device";
            }
            // Swift writes the string directly to the buffer
            return buf.getString(0);
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
    // Note: metallum_create_texture_2d 内部自己获取系统默认设备，不需要传入 device
    public static MemorySegment createTexture2D(int pixelFormat, long width, long height,
                                                 long depthOrLayers, long mipLevels, long cubeCompatible,
                                                 int usage, int storageMode, String label) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment labelSeg = allocateUtf8String(arena, label);
            MemorySegment result = (MemorySegment) createTexture2D.invoke(pixelFormat, width, height, depthOrLayers, mipLevels, cubeCompatible, usage, storageMode, labelSeg);
            if (isNullHandle(result)) {
                Iris.logger.warn("createTexture2D returned NULL for {} (format={}, {}x{}, mipLevels={})", 
                    label, pixelFormat, width, height, mipLevels);
            }
            return result;
        } catch (Throwable t) {
            Iris.logger.error("Exception creating 2D texture {}: {}", label, t.getMessage(), t);
            throw new RuntimeException("Failed to create 2D texture", t);
        }
    }

    public static MemorySegment createTexture3D(int pixelFormat, long width, long height,
                                                 long depth, long mipLevels, int usage, int storageMode, String label) {
        if (createTexture3D == null) {
            return MemorySegment.NULL;  // 返回 NULL 而不是抛异常，让调用者处理
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment device = getSystemDefaultDevice();
            MemorySegment labelSeg = allocateUtf8String(arena, label);
            // dimension=3 表示 3D 纹理
            return (MemorySegment) createTexture3D.invoke(device, pixelFormat, width, height, depth, mipLevels, 3, 0, usage, storageMode, labelSeg);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create 3D texture", t);
        }
    }

    public static MemorySegment createTextureCube(int pixelFormat, long size, long mipLevels,
                                                   int usage, int storageMode, String label) {
        if (createTextureCube == null) {
            return MemorySegment.NULL;  // 返回 NULL 而不是抛异常，让调用者处理
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment device = getSystemDefaultDevice();
            MemorySegment labelSeg = allocateUtf8String(arena, label);
            // cubeCompatible=1 表示 cube 纹理, dimension=2 表示 2D
            return (MemorySegment) createTextureCube.invoke(device, pixelFormat, size, 1, mipLevels, 2, 1, usage, storageMode, labelSeg);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create cube texture", t);
        }
    }

    public static void textureReplaceRegion(MemorySegment texture, MemorySegment data, long dataLength,
                                             long slice, long level, long originX, long originY, long originZ,
                                             long width, long height, long depth, int bytesPerRow, long bytesPerImage) {
        if (textureReplaceRegion == null) {
            throw new UnsupportedOperationException("textureReplaceRegion is not available on this platform");
        }
        try {
            textureReplaceRegion.invoke(texture, data, dataLength, slice, level, originX, originY, originZ, width, height, depth, bytesPerRow, bytesPerImage);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to replace texture region", t);
        }
    }

    public static void textureGetBytes(MemorySegment texture, MemorySegment out, long outLength,
                                        long slice, long level, long originX, long originY, long originZ,
                                        long width, long height, long depth, int bytesPerRow, long bytesPerImage) {
        if (textureGetBytes == null) {
            throw new UnsupportedOperationException("textureGetBytes is not available on this platform");
        }
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
        if (bufferReplaceRegion == null) {
            throw new UnsupportedOperationException("bufferReplaceRegion is not available on this platform");
        }
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
        if (compileRenderPipeline == null) {
            throw new UnsupportedOperationException("Render pipeline compilation is not available on this platform");
        }
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
        } catch (UnsupportedOperationException e) {
            throw e;
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
        if (renderEncoderSetRenderPipelineState == null) {
            Iris.logger.warn("renderEncoderSetRenderPipelineState not available, skipping");
            return;
        }
        try {
            renderEncoderSetRenderPipelineState.invoke(encoder, pipeline);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetDepthStencilState(MemorySegment encoder, MemorySegment state) {
        if (renderEncoderSetDepthStencilState == null) {
            Iris.logger.warn("renderEncoderSetDepthStencilState not available, skipping");
            return;
        }
        try {
            renderEncoderSetDepthStencilState.invoke(encoder, state);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetDepthBias(MemorySegment encoder, float bias, float slopeScale, float clamp) {
        if (renderEncoderSetDepthBias == null) {
            return;
        }
        try {
            renderEncoderSetDepthBias.invoke(encoder, bias, slopeScale, clamp);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetFrontFacingWinding(MemorySegment encoder, int winding) {
        if (renderEncoderSetFrontFacingWinding == null) {
            return;
        }
        try {
            renderEncoderSetFrontFacingWinding.invoke(encoder, winding);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetCullMode(MemorySegment encoder, int mode) {
        if (renderEncoderSetCullMode == null) {
            return;
        }
        try {
            renderEncoderSetCullMode.invoke(encoder, mode);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetTriangleFillMode(MemorySegment encoder, int mode) {
        if (renderEncoderSetTriangleFillMode == null) {
            return;
        }
        try {
            renderEncoderSetTriangleFillMode.invoke(encoder, mode);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetBuffer(MemorySegment encoder, MemorySegment buffer, long offset, long length, int index) {
        if (renderEncoderSetBuffer == null) {
            Iris.logger.warn("renderEncoderSetBuffer not available, skipping");
            return;
        }
        try {
            renderEncoderSetBuffer.invoke(encoder, buffer, offset, length, index);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetBufferOffset(MemorySegment encoder, long offset, long length, int index) {
        if (renderEncoderSetBufferOffset == null) {
            return;
        }
        try {
            renderEncoderSetBufferOffset.invoke(encoder, offset, length, index);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetTexture(MemorySegment encoder, MemorySegment texture, long level, int index) {
        if (renderEncoderSetTexture == null) {
            Iris.logger.warn("renderEncoderSetTexture not available, skipping");
            return;
        }
        try {
            renderEncoderSetTexture.invoke(encoder, texture, level, index);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetTextureAndSampler(MemorySegment encoder, MemorySegment texture, MemorySegment sampler, int slot) {
        // 优先使用 metallum 的 setTextureAndSampler 组合方法
        if (renderEncoderSetTextureAndSampler != null) {
            try {
                renderEncoderSetTextureAndSampler.invoke(encoder, texture, sampler, slot);
                return;
            } catch (Throwable t) {
                Iris.logger.debug("setTextureAndSampler failed, falling back", t);
            }
        }
        // 回退：分别设置纹理和采样器
        try {
            if (!isNullHandle(texture) && renderEncoderSetTexture != null) {
                renderEncoderSetTexture.invoke(encoder, texture, 0, slot);
            }
            // 注意：单独的 setSamplerState 不存在于 metallum，采样器需要通过 setTextureAndSampler 设置
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetSamplerState(MemorySegment encoder, MemorySegment sampler, long lod, int index) {
        // metallum 没有单独的 setSamplerState，需要通过 setTextureAndSampler 设置
        // 这个方法将不做任何事情，调用者应该使用 setTextureAndSampler
        if (renderEncoderSetSamplerState == null) {
            Iris.logger.debug("setSamplerState not available on this platform");
            return;
        }
        try {
            renderEncoderSetSamplerState.invoke(encoder, sampler, lod, index);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetScissorRect(MemorySegment encoder, long x, long y, long width, long height) {
        if (renderEncoderSetScissorRect == null) {
            return;
        }
        try {
            renderEncoderSetScissorRect.invoke(encoder, x, y, width, height);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderSetViewport(MemorySegment encoder, double originX, double originY, double width,
                                                 double height, double znear, double zfar, long scissorX, long scissorY) {
        // metallum 不支持 setViewport，跳过
        Iris.logger.debug("setViewport not available on this platform, skipping");
    }

    public static void renderEncoderSetBlendColor(MemorySegment encoder, float r, float g, float b, float a) {
        // metallum 不支持 setBlendColor，跳过
        Iris.logger.debug("setBlendColor not available on this platform, skipping");
    }

    public static void renderEncoderSetColorWriteMask(MemorySegment encoder, int mask) {
        // metallum 不支持 setColorWriteMask，跳过
        Iris.logger.debug("setColorWriteMask not available on this platform, skipping");
    }

    public static void renderEncoderDrawPrimitives(MemorySegment encoder, int primitiveType, long vertexStart, long vertexCount, long instanceCount) {
        if (renderEncoderDrawPrimitives == null) {
            throw new UnsupportedOperationException("drawPrimitives not available on this platform");
        }
        try {
            renderEncoderDrawPrimitives.invoke(encoder, primitiveType, vertexStart, vertexCount, instanceCount);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void renderEncoderDrawIndexedPrimitives(MemorySegment encoder, int primitiveType, MemorySegment indexBuffer,
                                                           long indexCount, long indexStart, long instanceCount) {
        if (renderEncoderDrawIndexedPrimitives == null) {
            throw new UnsupportedOperationException("drawIndexedPrimitives not available on this platform");
        }
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
        if (makeDepthStencilState == null) {
            return MemorySegment.NULL;
        }
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
        if (makeSamplerState == null) {
            return MemorySegment.NULL;
        }
        try {
            // metallum_create_sampler signature:
            // (device, addressModeU, addressModeV, minFilter, magFilter, mipFilter, maxAnisotropy, lodMaxClamp)
            return (MemorySegment) makeSamplerState.invoke(device, sAddressMode, tAddressMode,
                    minFilter, magFilter, mipFilter, (int)maxAnisotropy, (double)lodMaxClamp);
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
        if (blitCopyBufferToBuffer == null) {
            throw new UnsupportedOperationException("blitCopyBufferToBuffer not available on this platform");
        }
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
        if (blitCopyBufferToTexture == null) {
            throw new UnsupportedOperationException("blitCopyBufferToTexture not available on this platform");
        }
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
        if (blitCopyTextureToTexture == null) {
            throw new UnsupportedOperationException("blitCopyTextureToTexture not available on this platform");
        }
        try {
            blitCopyTextureToTexture.invoke(encoder, src, dst, srcSlice, srcLevel, srcOriginX, srcOriginY, srcOriginZ, dstSlice, dstLevel, size);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static void blitCopyTextureToBuffer(MemorySegment encoder, MemorySegment src, MemorySegment dst,
                                                long srcSlice, long srcLevel, long srcOriginX, long srcOriginY, long srcOriginZ,
                                                long width, long height, long depth, long bytesPerRow, long bytesPerImage) {
        if (blitCopyTextureToBuffer == null) {
            throw new UnsupportedOperationException("blitCopyTextureToBuffer not available on this platform");
        }
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
        if (blitGenerateMipmaps == null) {
            Iris.logger.warn("blitGenerateMipmaps is not available on this platform, skipping mipmap generation");
            return;
        }
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
    // 现在使用 SPIRVToMslConverter
    public static MemorySegment compileGlslToMsl(byte[] spirv, int spirvLength, int stage, int mslVersion) {
        try {
            String msl = SPIRVToMslConverter.convert(spirv, mslVersion);
            if (msl != null && !msl.isEmpty()) {
                return allocateUtf8String(Arena.ofAuto(), msl);
            }
        } catch (Exception e) {
            Iris.logger.warn("SPIRV-Cross compilation failed: " + e.getMessage());
        }
        return MemorySegment.NULL;
    }

    public static MemorySegment compileGlslToMsl(String glslSource, int stage, String entryPoint) {
        if (compileGlslToMsl == null) {
            throw new UnsupportedOperationException("GLSL to MSL compiler is not available on this platform");
        }
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
        } catch (UnsupportedOperationException e) {
            throw e;
        } catch (Throwable t) {
            throw new RuntimeException("GLSL→MSL compilation failed", t);
        }
    }

    public static String getCompiledMslSource(MemorySegment compiledHandle) {
        if (getCompiledMslSource == null) {
            throw new UnsupportedOperationException("GLSL to MSL compiler is not available on this platform");
        }
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
        if (getCompiledMslError == null) {
            return null;
        }
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
        if (freeCompiledShader == null) {
            return;
        }
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
        if (createShaderFunction == null || isNullHandle(device)) {
            return MemorySegment.NULL;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment source = allocateUtf8String(arena, mslSource);
            MemorySegment entry = allocateUtf8String(arena, "main0");
            MemorySegment function = (MemorySegment) createShaderFunction.invoke(device, source, entry);
            return function;
        } catch (Throwable t) {
            Iris.logger.warn("Failed to compile MSL to library: " + label, t);
            return MemorySegment.NULL;
        }
    }

    public static MemorySegment getLibraryFunction(MemorySegment library, String functionName) {
        // metallum_create_shader_function 已经返回了编译好的函数，不需要再从 library 获取
        // 如果 library 不是 NULL 但我们需要获取特定名称的函数，这里返回 library 作为函数
        if (isNullHandle(library)) {
            return MemorySegment.NULL;
        }
        // 对于 metallum，编译 shader source 时已经指定了入口点，返回 library 本身即可
        // Metal pipeline 会使用这个作为 function
        return library;
    }

    public static MemorySegment createRenderPipelineDescriptor() {
        if (renderPipelineDescriptorCreate == null) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) renderPipelineDescriptorCreate.invoke();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create render pipeline descriptor", t);
        }
    }

    public static void setPipelineVertexFunction(MemorySegment descriptor, MemorySegment vertexFunction, MemorySegment fragmentFunction) {
        if (renderPipelineDescriptorSetCompiledFunctions == null || isNullHandle(descriptor)) {
            return;
        }
        try {
            // setCompiledFunctions sets both vertex and fragment functions
            renderPipelineDescriptorSetCompiledFunctions.invoke(descriptor, vertexFunction, fragmentFunction);
        } catch (Throwable t) {
            Iris.logger.warn("Failed to set pipeline functions", t);
        }
    }

    public static void setPipelineFragmentFunction(MemorySegment descriptor, MemorySegment function) {
        // Handled by setPipelineVertexFunction which sets both
    }

    public static void setPipelineColorAttachment(MemorySegment descriptor, int index, int pixelFormat,
                                                 int blendEnabled,
                                                 int srcBlend, int dstBlend, int blendOp,
                                                 int srcBlendAlpha, int dstBlendAlpha, int blendOpAlpha) {
        if (renderPipelineDescriptorSetColorAttachmentFormat == null || isNullHandle(descriptor)) {
            return;
        }
        try {
            renderPipelineDescriptorSetColorAttachmentFormat.invoke(descriptor, index, pixelFormat);
            if (blendEnabled != 0 && renderPipelineDescriptorSetColorAttachmentBlendState != null) {
                // writeMask: 0xF = all color components
                renderPipelineDescriptorSetColorAttachmentBlendState.invoke(descriptor, index, 1,
                        srcBlend, dstBlend, blendOp, srcBlendAlpha, dstBlendAlpha, blendOpAlpha, 0xF);
            }
        } catch (Throwable t) {
            Iris.logger.warn("Failed to set color attachment", t);
        }
    }

    public static void setPipelineDepthAttachmentPixelFormat(MemorySegment descriptor, int pixelFormat) {
        if (renderPipelineDescriptorSetDepthStencilFormats == null || isNullHandle(descriptor)) {
            return;
        }
        try {
            // depthFormat = pixelFormat, stencilFormat = 0 (no stencil)
            renderPipelineDescriptorSetDepthStencilFormats.invoke(descriptor, pixelFormat, 0);
        } catch (Throwable t) {
            Iris.logger.warn("Failed to set depth attachment format", t);
        }
    }

    public static void setPipelineVertexDescriptor(MemorySegment descriptor, MemorySegment vertexDescriptor) {
        if (renderPipelineDescriptorSetVertexDescriptor == null || isNullHandle(descriptor) || isNullHandle(vertexDescriptor)) {
            return;
        }
        try {
            renderPipelineDescriptorSetVertexDescriptor.invoke(descriptor, vertexDescriptor);
        } catch (Throwable t) {
            Iris.logger.warn("Failed to set vertex descriptor", t);
        }
    }

    public static MemorySegment newRenderPipelineState(MemorySegment device, MemorySegment descriptor) {
        if (compileRenderPipeline == null || isNullHandle(device) || isNullHandle(descriptor)) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) compileRenderPipeline.invoke(device, descriptor);
        } catch (Throwable t) {
            throw new RuntimeException("Failed to create render pipeline state", t);
        }
    }

    public static void uploadBufferData(MemorySegment buffer, ByteBuffer data, long size) {
        // 简化实现
    }
}

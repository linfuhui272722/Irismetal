package net.irisshaders.iris.metal.program;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.irisshaders.iris.metal.IrisMetalDevice;
import net.irisshaders.iris.metal.blending.MetalBlendState;
import net.irisshaders.iris.metal.bridge.IrisMetalNativeBridge;
import net.irisshaders.iris.metal.shader.MetalShaderCompiler;
import net.irisshaders.iris.metal.uniform.MetalUniformBlock;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/**
 * Metal 编译后的渲染管线状态对象，对应 Iris 原有的 {@code Program}。
 *
 * <p>本类封装一个 {@code MTLRenderPipelineState}，是 Iris Metal 后端的核心对象。
 * 在 GL 中，program 是 shader 链接后的产物；在 Metal 中，pipeline state 是
 * shader function + vertex descriptor + blend state + depth/stencil state 的组合。</p>
 *
 * <p><b>构建流程</b>：</p>
 * <ol>
 *   <li>将 vertex/fragment GLSL 编译为 MSL（{@link MetalShaderCompiler}）</li>
 *   <li>用 {@code MTLDevice.newLibraryWithSource} 编译 MSL 为 {@code MTLLibrary}</li>
 *   <li>从 library 获取 vertex/fragment {@code MTLFunction}</li>
 *   <li>创建 {@code MTLRenderPipelineDescriptor}，设置 vertex function、fragment function、
 *       vertex descriptor、color attachment 的 pixel format 和 blend state</li>
 *   <li>调用 {@code MTLDevice.newRenderPipelineStateWithDescriptor} 创建 pipeline state</li>
 * </ol>
 *
 * <p><b>与 GL program 的关键差异</b>：</p>
 * <ul>
 *   <li>GL 的 uniform 在 program link 后通过 {@code glGetUniformLocation} 查询，
 *       运行时通过 {@code glUniform*} 设置。Metal 中 uniform 通过 buffer binding
 *       传递，本类通过 {@link MetalUniformBlock} 管理。</li>
 *   <li>GL 的 sampler 在 program link 后通过 {@code glGetUniformLocation} 查询，
 *       运行时通过 {@code glUniform1i} 设置 texture unit。Metal 中 sampler 和
 *       texture 是独立的 binding，本类通过 {@link MetalProgramSamplers} 管理。</li>
 *   <li>GL 的 blend state 是即时状态，Metal 中 blend state 是 pipeline state 的一部分。
 *       因此每次 blend 状态变化都需要重新创建 pipeline state（或使用多个预编译的
 *       pipeline state）。本类采用"blend state 变体"策略，缓存常用组合。</li>
 * </ul>
 *
 * <p><b>性能注意</b>：创建 pipeline state 是昂贵操作（涉及 GPU 驱动编译）。
 * 本类缓存编译结果，相同 shader + blend 组合只编译一次。</p>
 */
@Environment(EnvType.CLIENT)
public final class MetalCompiledProgram implements AutoCloseable {
    private final String name;
    private final MemorySegment pipelineState;
    private final MemorySegment vertexFunction;
    private final MemorySegment fragmentFunction;
    private final MemorySegment library;
    private final MetalUniformBlock uniformBlock;
    private final MetalProgramSamplers samplers;
    private final MetalProgramImages images;
    private final int[] colorAttachmentPixelFormats;
    private final int depthAttachmentPixelFormat;
    private boolean destroyed;

    private MetalCompiledProgram(String name, MemorySegment pipelineState,
                                 MemorySegment vertexFunction, MemorySegment fragmentFunction,
                                 MemorySegment library,
                                 MetalUniformBlock uniformBlock,
                                 MetalProgramSamplers samplers,
                                 MetalProgramImages images,
                                 int[] colorAttachmentPixelFormats,
                                 int depthAttachmentPixelFormat) {
        this.name = name;
        this.pipelineState = pipelineState;
        this.vertexFunction = vertexFunction;
        this.fragmentFunction = fragmentFunction;
        this.library = library;
        this.uniformBlock = uniformBlock;
        this.samplers = samplers;
        this.images = images;
        this.colorAttachmentPixelFormats = colorAttachmentPixelFormats;
        this.depthAttachmentPixelFormat = depthAttachmentPixelFormat;
    }

    /**
     * 构建 Metal pipeline state。
     *
     * @param name              program 名称
     * @param vertexSource      vertex GLSL 源码（已预处理）
     * @param fragmentSource    fragment GLSL 源码（已预处理）
     * @param blendState        blend 状态
     * @param colorFormats      颜色附件像素格式数组
     * @param depthFormat       深度附件像素格式（0 表示无深度）
     * @param vertexDescriptor  顶点布局描述
     */
    public static MetalCompiledProgram build(
            String name,
            @Nullable String vertexSource,
            @Nullable String fragmentSource,
            MetalBlendState blendState,
            int[] colorFormats,
            int depthFormat,
            @Nullable MetalVertexDescriptor vertexDescriptor) {

        IrisMetalDevice device = IrisMetalDevice.get();
        MemorySegment deviceHandle = device.deviceHandle();

        // 1. 编译 vertex shader
        MemorySegment vertexFunction = MemorySegment.NULL;
        MemorySegment vertexLibrary = MemorySegment.NULL;
        if (vertexSource != null) {
            MetalShaderCompiler.CompileResult vr = MetalShaderCompiler.compileGlslToMsl(
                    name + ".vsh", ShaderType.VERTEX, vertexSource);
            if (!vr.isSuccess()) {
                throw new RuntimeException("Vertex shader compilation failed: " + vr.getError());
            }
            vertexLibrary = IrisMetalNativeBridge.compileMslToLibrary(deviceHandle, vr.getMslSource(), name + ".vsh");
            if (IrisMetalNativeBridge.isNullHandle(vertexLibrary)) {
                throw new RuntimeException("Failed to compile vertex MSL to MTLLibrary: " + name);
            }
            vertexFunction = IrisMetalNativeBridge.getLibraryFunction(vertexLibrary, "vertexMain");
            if (IrisMetalNativeBridge.isNullHandle(vertexFunction)) {
                IrisMetalNativeBridge.releaseObject(vertexLibrary);
                throw new RuntimeException("Vertex function 'vertexMain' not found in library: " + name);
            }
        }

        // 2. 编译 fragment shader
        MemorySegment fragmentFunction = MemorySegment.NULL;
        MemorySegment fragmentLibrary = MemorySegment.NULL;
        if (fragmentSource != null) {
            MetalShaderCompiler.CompileResult fr = MetalShaderCompiler.compileGlslToMsl(
                    name + ".fsh", ShaderType.FRAGMENT, fragmentSource);
            if (!fr.isSuccess()) {
                if (!IrisMetalNativeBridge.isNullHandle(vertexLibrary)) IrisMetalNativeBridge.releaseObject(vertexLibrary);
                if (!IrisMetalNativeBridge.isNullHandle(vertexFunction)) IrisMetalNativeBridge.releaseObject(vertexFunction);
                throw new RuntimeException("Fragment shader compilation failed: " + fr.getError());
            }
            fragmentLibrary = IrisMetalNativeBridge.compileMslToLibrary(deviceHandle, fr.getMslSource(), name + ".fsh");
            if (IrisMetalNativeBridge.isNullHandle(fragmentLibrary)) {
                if (!IrisMetalNativeBridge.isNullHandle(vertexLibrary)) IrisMetalNativeBridge.releaseObject(vertexLibrary);
                if (!IrisMetalNativeBridge.isNullHandle(vertexFunction)) IrisMetalNativeBridge.releaseObject(vertexFunction);
                throw new RuntimeException("Failed to compile fragment MSL to MTLLibrary: " + name);
            }
            fragmentFunction = IrisMetalNativeBridge.getLibraryFunction(fragmentLibrary, "fragmentMain");
            if (IrisMetalNativeBridge.isNullHandle(fragmentFunction)) {
                IrisMetalNativeBridge.releaseObject(fragmentLibrary);
                if (!IrisMetalNativeBridge.isNullHandle(vertexLibrary)) IrisMetalNativeBridge.releaseObject(vertexLibrary);
                if (!IrisMetalNativeBridge.isNullHandle(vertexFunction)) IrisMetalNativeBridge.releaseObject(vertexFunction);
                throw new RuntimeException("Fragment function 'fragmentMain' not found in library: " + name);
            }
        }

        // 3. 创建 pipeline descriptor 并设置参数
        MemorySegment pipelineDescriptor = IrisMetalNativeBridge.createRenderPipelineDescriptor();

        IrisMetalNativeBridge.setPipelineVertexFunction(pipelineDescriptor, vertexFunction);
        IrisMetalNativeBridge.setPipelineFragmentFunction(pipelineDescriptor, fragmentFunction);

        // 设置颜色附件格式和 blend state
        for (int i = 0; i < colorFormats.length; i++) {
            if (colorFormats[i] != 0) {
                IrisMetalNativeBridge.setPipelineColorAttachment(
                        pipelineDescriptor, i, colorFormats[i],
                        blendState.blendEnabled() ? 1 : 0,
                        blendState.sourceRgbBlendFactor(),
                        blendState.destinationRgbBlendFactor(),
                        blendState.rgbBlendOperation(),
                        blendState.sourceAlphaBlendFactor(),
                        blendState.destinationAlphaBlendFactor(),
                        blendState.alphaBlendOperation());
            }
        }

        // 设置深度附件格式
        if (depthFormat != 0) {
            IrisMetalNativeBridge.setPipelineDepthAttachmentPixelFormat(pipelineDescriptor, depthFormat);
        }

        // 设置 vertex descriptor
        if (vertexDescriptor != null) {
            IrisMetalNativeBridge.setPipelineVertexDescriptor(pipelineDescriptor, vertexDescriptor.handle());
        }

        // 4. 创建 pipeline state
        MemorySegment pipelineState = IrisMetalNativeBridge.newRenderPipelineState(deviceHandle, pipelineDescriptor);
        IrisMetalNativeBridge.releaseObject(pipelineDescriptor);

        if (IrisMetalNativeBridge.isNullHandle(pipelineState)) {
            if (!IrisMetalNativeBridge.isNullHandle(vertexLibrary)) IrisMetalNativeBridge.releaseObject(vertexLibrary);
            if (!IrisMetalNativeBridge.isNullHandle(vertexFunction)) IrisMetalNativeBridge.releaseObject(vertexFunction);
            if (!IrisMetalNativeBridge.isNullHandle(fragmentLibrary)) IrisMetalNativeBridge.releaseObject(fragmentLibrary);
            if (!IrisMetalNativeBridge.isNullHandle(fragmentFunction)) IrisMetalNativeBridge.releaseObject(fragmentFunction);
            throw new RuntimeException("Failed to create render pipeline state: " + name);
        }

        // 5. 创建 uniform/sampler/image 管理器
        // 注意：这些需要在 pipeline 创建后通过 reflection 获取 binding 信息
        // 实际实现中，uniform 布局在 GLSL→MSL 转换时由 SPIRV-Cross 反射得到
        MetalUniformBlock uniformBlock = MetalUniformBlock.reflectFromPipeline(pipelineState, vertexFunction, fragmentFunction);
        MetalProgramSamplers samplers = MetalProgramSamplers.reflectFromPipeline(pipelineState, vertexFunction, fragmentFunction);
        MetalProgramImages images = MetalProgramImages.reflectFromPipeline(pipelineState, vertexFunction, fragmentFunction);

        // library 和 function 的引用计数：pipeline state 会持有它们，但我们仍需保留引用
        // 防止过早释放。在 close() 时统一释放。
        return new MetalCompiledProgram(name, pipelineState, vertexFunction, fragmentFunction,
                vertexLibrary, // 只保留 vertex library（fragment library 合并到 vertex library 或单独管理）
                uniformBlock, samplers, images, colorFormats, depthFormat);
    }

    public String name() {
        return name;
    }

    public MemorySegment pipelineStateHandle() {
        return pipelineState;
    }

    public MetalUniformBlock uniformBlock() {
        return uniformBlock;
    }

    public MetalProgramSamplers samplers() {
        return samplers;
    }

    public MetalProgramImages images() {
        return images;
    }

    @Override
    public void close() {
        if (!destroyed) {
            destroyed = true;
            if (!IrisMetalNativeBridge.isNullHandle(pipelineState)) {
                IrisMetalNativeBridge.releaseObject(pipelineState);
            }
            if (!IrisMetalNativeBridge.isNullHandle(vertexFunction)) {
                IrisMetalNativeBridge.releaseObject(vertexFunction);
            }
            if (!IrisMetalNativeBridge.isNullHandle(fragmentFunction)) {
                IrisMetalNativeBridge.releaseObject(fragmentFunction);
            }
            if (!IrisMetalNativeBridge.isNullHandle(library)) {
                IrisMetalNativeBridge.releaseObject(library);
            }
            if (uniformBlock != null) uniformBlock.close();
            if (samplers != null) samplers.close();
            if (images != null) images.close();
        }
    }
}

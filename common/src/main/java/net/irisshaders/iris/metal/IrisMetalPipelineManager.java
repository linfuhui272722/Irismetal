package net.irisshaders.iris.metal;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.metal.blending.MetalBlendState;
import net.irisshaders.iris.metal.blending.MetalSamplerStateCache;
import net.irisshaders.iris.metal.framebuffer.MetalFramebuffer;
import net.irisshaders.iris.metal.program.MetalCompiledProgram;
import net.irisshaders.iris.metal.program.MetalVertexDescriptor;
import net.irisshaders.iris.metal.shader.MetalShaderCompiler;
import net.irisshaders.iris.metal.texture.MetalTexture;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;

/**
 * Iris Metal pipeline 管理器。
 *
 * <p>本类是 Iris Metal 后端的中央调度器，对应 Iris 原有的
 * {@code PipelineManager} / {@code IrisRenderingPipeline} 的 Metal 实现。</p>
 *
 * <p>职责：</p>
 * <ul>
 *   <li>管理所有编译后的 {@link MetalCompiledProgram}（gbuffers / composite / final / shadow）</li>
 *   <li>管理所有 {@link MetalFramebuffer}（colortex0-7、depthtex、shadow 等）</li>
 *   <li>管理所有 {@link MetalTexture}（光影纹理资源）</li>
 *   <li>协调渲染 pass 的开始/结束、FBO 切换</li>
 *   <li>在 metallum 环境下与 vanilla/Sodium 渲染的衔接</li>
 * </ul>
 *
 * <p><b>与 metallum 的衔接</b>：</p>
 * <p>在 metallum 环境下，vanilla 和 Sodium 的渲染走 Metal 后端。Iris 需要在
 * vanilla 渲染的特定阶段插入光影 pass：</p>
 * <ol>
 *   <li><b>gbuffers 阶段</b>：Iris 接管 vanilla 的 gbuffers 程序，用光影 shader
 *       替换 vanilla shader，将渲染结果写入 Iris 的 colortex FBO</li>
 *   <li><b>shadow pass</b>：在 gbuffers 之前，从光源视角渲染场景到 shadow FBO</li>
 *   <li><b>composite pass</b>：gbuffers 之后，对 colortex 进行后处理（光照、雾、
 *       反射等）</li>
 *   <li><b>final pass</b>：将最终结果合成到屏幕</li>
 * </ol>
 *
 * <p>由于 metallum 已经将 vanilla 渲染切换到 Metal，Iris 的 gbuffers pass 需要
 * 与 metallum 的命令编码协调——在 metallum 的 render pass 结束后，Iris 开始自己
 * 的 render pass；在 Iris 的 composite pass 完成后，将结果 blit 到 metallum 的
 * 最终渲染目标。</p>
 */
@Environment(EnvType.CLIENT)
public final class IrisMetalPipelineManager {
    private static IrisMetalPipelineManager instance;

    private final Map<String, MetalCompiledProgram> programs = new HashMap<>();
    private final Map<String, MetalFramebuffer> framebuffers = new HashMap<>();
    private final Map<String, MetalTexture> textures = new HashMap<>();

    @Nullable
    private MetalFramebuffer activeFramebuffer;
    @Nullable
    private MetalCompiledProgram activeProgram;

    private boolean initialized = false;

    private IrisMetalPipelineManager() {
    }

    public static synchronized IrisMetalPipelineManager get() {
        if (instance == null) {
            instance = new IrisMetalPipelineManager();
        }
        return instance;
    }

    /**
     * 初始化 Metal 后端。在 Iris 加载光影时调用。
     */
    public synchronized void initialize() {
        if (initialized) {
            return;
        }
        IrisMetalDevice.get().ensureLoaded();
        initialized = true;
    }

    /**
     * 编译并注册一个光影 program。
     *
     * @param name          program 名称（如 "gbuffers_terrain"、"composite"）
     * @param vertexSource  vertex shader GLSL 源码（已经过 Iris transformer 处理）
     * @param fragmentSource fragment shader GLSL 源码
     * @param geometrySource geometry shader GLSL 源码（可为 null，Metal 暂不支持）
     * @param vertexDesc    顶点布局描述
     * @param colorFormats  颜色附件的 MTLPixelFormat 数组
     * @param depthFormat   深度附件的 MTLPixelFormat（0 表示无深度）
     * @param blendStates   每个颜色附件的 blend 状态
     * @return 编译后的 program
     */
    public MetalCompiledProgram compileProgram(String name,
                                                @Nullable String vertexSource,
                                                @Nullable String fragmentSource,
                                                @Nullable String geometrySource,
                                                MetalVertexDescriptor vertexDesc,
                                                int[] colorFormats,
                                                int depthFormat,
                                                @Nullable MetalBlendState[] blendStates) {
        // 销毁旧的
        MetalCompiledProgram old = programs.remove(name);
        if (old != null) {
            old.close();
        }

        MetalCompiledProgram program = MetalCompiledProgram.create(
                name, vertexSource, fragmentSource, geometrySource,
                vertexDesc, colorFormats, depthFormat, blendStates);
        programs.put(name, program);
        return program;
    }

    /**
     * 创建一个光影 FBO。
     *
     * @param name           FBO 名称（如 "solid"、"composite"）
     * @param colorTextures  颜色附件纹理
     * @param depthTexture   深度附件纹理（可为 null）
     * @return 创建的 FBO
     */
    public MetalFramebuffer createFramebuffer(String name, MetalTexture[] colorTextures,
                                               @Nullable MetalTexture depthTexture) {
        MetalFramebuffer old = framebuffers.remove(name);
        if (old != null) {
            old.close();
        }
        MetalFramebuffer fbo = new MetalFramebuffer();
        for (MetalTexture tex : colorTextures) {
            fbo.addColorAttachment(tex, 0, 0);
        }
        if (depthTexture != null) {
            fbo.setDepthAttachment(depthTexture);
        }
        framebuffers.put(name, fbo);
        return fbo;
    }

    /**
     * 创建一个光影纹理。
     *
     * @param name           纹理名称（如 "colortex0"、"depthtex0"）
     * @param internalFormat GL 内部格式
     * @param width          宽度
     * @param height         高度
     * @param depth          深度（2D 纹理为 1，3D 纹理为实际深度）
     * @param mipLevels      mipmap 层数（1 表示无 mipmap）
     * @return 创建的纹理
     */
    public MetalTexture createTexture(String name, InternalTextureFormat internalFormat,
                                      int width, int height, int depth, int mipLevels) {
        MetalTexture old = textures.remove(name);
        if (old != null) {
            old.close();
        }
        MetalTexture texture = new MetalTexture(internalFormat, width, height, depth, mipLevels);
        textures.put(name, texture);
        return texture;
    }

    public MetalCompiledProgram getProgram(String name) {
        return programs.get(name);
    }

    public MetalFramebuffer getFramebuffer(String name) {
        return framebuffers.get(name);
    }

    public MetalTexture getTexture(String name) {
        return textures.get(name);
    }

    /**
     * 绑定 FBO 作为当前渲染目标。
     */
    public void bindFramebuffer(@Nullable String name) {
        if (name == null) {
            activeFramebuffer = null;
        } else {
            activeFramebuffer = framebuffers.get(name);
        }
    }

    @Nullable
    public MetalFramebuffer getActiveFramebuffer() {
        return activeFramebuffer;
    }

    /**
     * 绑定 program 为当前使用的 shader。
     */
    public void bindProgram(@Nullable String name) {
        activeProgram = (name != null) ? programs.get(name) : null;
    }

    @Nullable
    public MetalCompiledProgram getActiveProgram() {
        return activeProgram;
    }

    /**
     * 销毁所有资源。在 Iris 卸载光影时调用。
     */
    public synchronized void destroy() {
        for (MetalCompiledProgram program : programs.values()) {
            program.close();
        }
        programs.clear();
        for (MetalFramebuffer fbo : framebuffers.values()) {
            fbo.close();
        }
        framebuffers.clear();
        for (MetalTexture texture : textures.values()) {
            texture.close();
        }
        textures.clear();
        MetalSamplerStateCache.destroyAll();
        activeFramebuffer = null;
        activeProgram = null;
        IrisMetalDevice.get().destroy();
        initialized = false;
    }

    public boolean isInitialized() {
        return initialized;
    }
}

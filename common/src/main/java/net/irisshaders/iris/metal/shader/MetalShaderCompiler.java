package net.irisshaders.iris.metal.shader;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.irisshaders.iris.metal.bridge.IrisMetalNativeBridge;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Metal shader 编译器，负责将 GLSL 源码编译为 Metal Shading Language (MSL)。
 *
 * <p>本类是 Iris Metal 后端的核心组件，直接参考 metallum 的
 * {@code MetalCrossShaderCompiler} 实现。编译流程为：</p>
 * <ol>
 *   <li><b>GLSL → SPIR-V</b>：使用 MC 26.2 自带的 {@code GlslCompiler}
 *       （{@code com.mojang.blaze3d.vulkan.glsl.GlslCompiler}），它内部使用
 *       glslang 将 GLSL 编译为 SPIR-V。这是 vanilla Vulkan 后端也在用的路径。</li>
 *   <li><b>SPIR-V → MSL</b>：使用 SPIRV-Cross（通过 LWJGL 的
 *       {@code org.lwjgl.util.spvc} 绑定）将 SPIR-V 交叉编译为 MSL。</li>
 *   <li><b>MSL → MTLLibrary → MTLFunction</b>：通过 native bridge 调用
 *       {@code MTLDevice.newLibraryWithSource} 编译 MSL，再获取 vertex/fragment
 *       function。</li>
 * </ol>
 *
 * <p><b>与 metallum 的差异</b>：metallum 编译的是 vanilla/Sodium 的简单 shader，
 * uniform 结构固定（Projection/Lighting/Fog/Globals）。Iris 光影的 shader 复杂得多：
 * 大量自定义 uniform、sampler、image、SSBO，且通过 {@code #include} 和宏展开。
 * 因此本编译器需要：</p>
 * <ul>
 *   <li>保留 Iris 已有的 GLSL 预处理（{@code ShaderWorkarounds} / transformer 层），
 *       在 GLSL 层面完成所有变换后再送入本编译器</li>
 *   <li>在 SPIRV-Cross 阶段正确处理 Iris 的资源绑定（uniform buffer / sampler /
 *       image / SSBO），生成对应的 MSL binding</li>
 *   <li>处理 MSL 的 vertex attribute 映射（Iris 的 attribute 布局与 vanilla 不同）</li>
 * </ul>
 *
 * <p><b>MSL 版本</b>：使用 Metal Shading Language 3.0（对应 Metal 3，支持
 * Apple Silicon M1+）。MSL 3.0 支持 barycentric coordinates、mesh shading 等
 * 高级特性，足够覆盖 Iris 光影的需求。</p>
 *
 * <p><b>已知限制</b>（需在 macOS 上实测后修复）：</p>
 * <ul>
 *   <li>GLSL 的 {@code gl_FragData} 需要在预处理阶段转换为 MSL 的多 output
 *       （Iris 的 transformer 层已部分处理）</li>
 *   <li>GLSL 的 {@code textureLod} 在 MSL 中对应 {@code sample()} 的 lod 参数，
 *       SPIRV-Cross 会自动处理</li>
 *   <li>GLSL 的 {@code dFdx}/{@code dFdy} 在 MSL 中对应 {@code dfdx}/{@code dfdy}，
 *       SPIRV-Cross 自动处理</li>
 *   <li>GLSL 的 geometry shader 在 Metal 中需要用 mesh shader 模拟，本版本暂不支持
 *       geometry shader（Iris 光影很少用）</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class MetalShaderCompiler {
    /** MSL 版本 3.0 */
    public static final int MSL_VERSION_3_0 = 0x30000;

    private static final Map<String, MemorySegment> libraryCache = new HashMap<>();
    private static final Map<String, MemorySegment> functionCache = new HashMap<>();

    private MetalShaderCompiler() {
    }

    /**
     * 编译 GLSL 源码为 MSL 源码。
     *
     * <p>本方法只完成 GLSL→SPIRV→MSL 的源码转换，不涉及 Metal 设备。
     * 可在任何平台调用（用于调试 MSL 输出）。</p>
     *
     * @param name       shader 名称（用于错误信息）
     * @param type       shader 类型
     * @param glslSource GLSL 源码（已完成 Iris 预处理和 transformer 变换）
     * @return 编译结果，包含 MSL 源码或错误信息
     */
    public static CompileResult compileGlslToMsl(String name, ShaderType type, String glslSource) {
        // 调试日志：打印 shader 前 500 字符
        if (glslSource != null && glslSource.length() > 0) {
            String preview = glslSource.substring(0, Math.min(500, glslSource.length())).replace("\n", "\\n");
            Iris.logger.info("[Iris-Metal] Compiling {} shader {}: {}...", type, name, preview);
        } else {
            Iris.logger.warn("[Iris-Metal] Empty shader source for {}", name);
            return CompileResult.failure("Empty shader source for " + name);
        }
        
        // 打印完整 shader 内容用于调试
        Iris.logger.info("[Iris-Metal] Full shader content for {}:\n{}", name, glslSource);
        
        // 步骤 1：GLSL → SPIR-V
        // 使用 MC 26.2 的 GlslCompiler（vanilla Vulkan 后端也用这个）
        // 它会处理 GLSL 版本声明、宏定义等
        byte[] spirv;
        try {
            spirv = compileGlslToSpirv(name, type, glslSource);
        } catch (Exception e) {
            return CompileResult.failure("GLSL→SPIRV failed for " + name + ": " + e.getMessage());
        }

        // 步骤 2：SPIR-V → MSL（使用 LWJGL SPIRV-Cross）
        try {
            String msl = SPIRVToMslConverter.convert(spirv, MSL_VERSION_3_0);
            if (msl == null || msl.isEmpty()) {
                return CompileResult.failure("SPIRV-Cross returned empty MSL for " + name);
            }
            return CompileResult.success(msl);
        } catch (Throwable t) {
            return CompileResult.failure("SPIRV-Cross invocation failed for " + name + ": " + t.getMessage());
        }
    }

    /**
     * 将 GLSL 编译为 SPIR-V。
     *
     * <p>使用 MC 26.2 的 {@code GlslCompiler}。该类位于
     * {@code com.mojang.blaze3d.vulkan.glsl} 包，是 Mojang 为 Vulkan 后端引入的
     * GLSL→SPIRV 编译器，内部使用 glslang。</p>
     *
     * <p>注意：{@code GlslCompiler} 期望的 GLSL 是 Vulkan 风格的（{@code #version 450}、
     * 无 {@code gl_FragColor} 等）。Iris 的 transformer 层（{@code LayoutTransformer} 等）
     * 已经将 GLSL 转换为兼容形式。如果未转换，本方法会先做基本适配。</p>
     */
    private static byte[] compileGlslToSpirv(String name, ShaderType type, String glslSource) throws Exception {
        // 适配 GLSL 为 Vulkan 风格（如果 Iris transformer 未完全处理）
        String vulkanGlsl = adaptGlslForVulkan(glslSource, type);

        // 使用反射调用 MC 的 GlslCompiler（避免硬编译依赖）
        // 正确的方法签名：GlslCompiler.createIntermediary(String filename, String source, ShaderType type)
        // 这与 metallum 使用的方式一致
        Class<?> glslCompilerClass = Class.forName("com.mojang.blaze3d.vulkan.glsl.GlslCompiler");
        Class<?> shaderTypeClass = Class.forName("com.mojang.blaze3d.shaders.ShaderType");
        Class<?> intermediaryClass = Class.forName("com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule");

        Object mcShaderType = mapShaderTypeToMc(shaderTypeClass, type);

        // 使用 createIntermediary 方法，与 metallum 一致
        java.lang.reflect.Constructor<?> glslCompilerConstructor = glslCompilerClass.getDeclaredConstructor();
        glslCompilerConstructor.setAccessible(true);
        Object glslCompiler = glslCompilerConstructor.newInstance();

        // 调用 createIntermediary(filename, source, type)
        java.lang.reflect.Method createIntermediary = glslCompilerClass.getMethod(
            "createIntermediary", String.class, String.class, shaderTypeClass);
        
        Object intermediary;
        try {
            intermediary = createIntermediary.invoke(glslCompiler, name + ".glsl", vulkanGlsl, mcShaderType);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            Iris.logger.error("GlslCompiler.createIntermediary failed for {}: {}", name, cause != null ? cause.getMessage() : "null");
            if (cause != null) {
                Iris.logger.error("Exception type: {}", cause.getClass().getName());
            }
            throw new Exception("GlslCompiler.createIntermediary failed", cause);
        }

        if (intermediary == null) {
            throw new Exception("GlslCompiler.createIntermediary returned null for " + name);
        }

        java.lang.reflect.Method getSpirvMethod = intermediaryClass.getMethod("getSpirv");
        java.nio.ByteBuffer spirvBuffer = (java.nio.ByteBuffer) getSpirvMethod.invoke(intermediary);

        if (spirvBuffer == null || !spirvBuffer.hasRemaining()) {
            throw new Exception("GlslCompiler.getSpirv returned null or empty buffer for " + name);
        }

        byte[] spirv = new byte[spirvBuffer.remaining()];
        spirvBuffer.get(spirv);

        // 释放 intermediary shader module
        try {
            java.lang.reflect.Method closeMethod = intermediaryClass.getMethod("close");
            closeMethod.invoke(intermediary);
        } catch (Exception ignored) {
        }

        // 关闭 GlslCompiler
        try {
            java.lang.reflect.Method compilerCloseMethod = glslCompilerClass.getMethod("close");
            compilerCloseMethod.invoke(glslCompiler);
        } catch (Exception ignored) {
        }

        return spirv;
    }

    /**
     * 将 GLSL 源码适配为 Vulkan 风格。
     *
     * <p>Vulkan GLSL 与 OpenGL GLSL 的主要差异：</p>
     * <ul>
     *   <li>{@code #version} 必须为 450 或更高</li>
     *   <li>不能使用 {@code gl_FragColor}，必须声明 {@code layout(location=0) out vec4}</li>
     *   <li>sampler 必须使用 combined sampler（{@code sampler2D}）或 separate
     *       （{@code texture2D} + {@code sampler}）</li>
     *   <li>不能使用 {@code gl_FragData}，必须用显式 output 声明</li>
     * </ul>
     *
     * <p>Iris 的 transformer 层（特别是 {@code LayoutTransformer}）已经处理了大部分
     * 适配。本方法做最后的兜底处理。</p>
     */
    private static String adaptGlslForVulkan(String source, ShaderType type) {
        String result = source;

        // 检查 shader 版本
        if (result.contains("#version 330")) {
            result = result.replace("#version 330 core", "#version 450 core");
            result = result.replace("#version 330", "#version 450 core");
            Iris.logger.info("[Iris-Metal] Upgraded shader from #version 330 to #version 450");
        }

        // Vulkan GLSL 要求 non-opaque uniform 必须在 uniform block 中
        // 为每个顶层 uniform 创建一个单独的 block
        
        // 首先收集所有顶层 uniform
        List<String> topLevelUniforms = new ArrayList<>();
        String[] lines = result.split("\n");
        StringBuilder filteredSource = new StringBuilder();
        int braceDepth = 0;
        
        for (String line : lines) {
            // 计算 brace depth
            for (char c : line.toCharArray()) {
                if (c == '{') braceDepth++;
                if (c == '}') braceDepth--;
            }
            
            // 检查是否是顶层 uniform（不在 block 内，且不是 block 定义）
            if (braceDepth == 0 && line.trim().startsWith("uniform ") && !line.trim().contains("{")) {
                String trimmed = line.trim();
                if (trimmed.endsWith(";")) {
                    String uniformLine = trimmed.substring(0, trimmed.length() - 1).trim();
                    String[] parts = uniformLine.split("\\s+");
                    if (parts.length >= 3) {
                        String uniformType = parts[1];
                        String uniformName = parts[2];
                        topLevelUniforms.add(uniformType + " " + uniformName);
                        Iris.logger.info("[Iris-Metal] Found top-level uniform: {} {}", uniformType, uniformName);
                        continue; // 跳过这行
                    }
                }
            }
            
            filteredSource.append(line).append("\n");
        }
        
        // 为每个顶层 uniform 创建单独的 block
        if (!topLevelUniforms.isEmpty()) {
            StringBuilder newBlocks = new StringBuilder();
            int counter = 0;
            for (String uniform : topLevelUniforms) {
                String[] parts = uniform.split("\\s+");
                String uniformType = parts[0];
                String uniformName = parts[1];
                
                // Block 类型名和实例名使用不同的名字避免冲突
                // 使用带数字后缀的名称确保唯一性
                String blockTypeName = "iris_UniformBlock" + counter;
                String blockVarName = "iris_" + uniformName;
                
                newBlocks.append("layout(std140) uniform ").append(blockTypeName).append(" {\n");
                newBlocks.append("    ").append(uniformType).append(" ").append(uniformName).append(";\n");
                newBlocks.append("} ").append(blockVarName).append(";\n\n");
                
                Iris.logger.info("[Iris-Metal] Created block: layout(std140) uniform {} {{ {} {}; }} {};",
                    blockTypeName, uniformType, uniformName, blockVarName);
                counter++;
            }
            
            // 在第一个 uniform block 之前插入
            String filtered = filteredSource.toString();
            int insertPos = filtered.indexOf("layout(std140) uniform");
            if (insertPos > 0) {
                result = filtered.substring(0, insertPos) + newBlocks.toString() + filtered.substring(insertPos);
            } else {
                // 在 #version 之后插入
                int versionEnd = filtered.indexOf("\n", filtered.indexOf("#version"));
                if (versionEnd > 0) {
                    result = filtered.substring(0, versionEnd + 1) + newBlocks.toString() + filtered.substring(versionEnd + 1);
                } else {
                    result = newBlocks.toString() + filtered;
                }
            }
        } else {
            result = filteredSource.toString();
        }
        
        // 打印转换后的 shader
        String shaderPreview = result.substring(0, Math.min(2000, result.length())).replace("\n", "\\n");
        Iris.logger.info("[Iris-Metal] Adapted shader preview: {}", shaderPreview);

        // gl_FragColor → 显式 output（如果 transformer 未处理）
        if (type == ShaderType.FRAGMENT && result.contains("gl_FragColor")) {
            if (!result.contains("layout(location = 0) out vec4")) {
                result = result.replace(
                        "#version 450 core",
                        "#version 450 core\nlayout(location = 0) out vec4 iris_FragColor;");
            }
            result = result.replace("gl_FragColor", "iris_FragColor");
        }

        return result;
    }

    /**
     * 将 Iris ShaderType 映射为 MC 的 ShaderType 枚举值。
     */
    private static Object mapShaderTypeToMc(Class<?> shaderTypeClass, ShaderType type) throws Exception {
        String enumName;
        switch (type) {
            case VERTEX:
                enumName = "VERTEX";
                break;
            case FRAGMENT:
                enumName = "FRAGMENT";
                break;
            case GEOMETRY:
                enumName = "GEOMETRY";
                break;
            case TESSELATION_CONTROL:
                enumName = "TESSELATION_CONTROL";
                break;
            case TESSELATION_EVAL:
                enumName = "TESSELATION_EVAL";
                break;
            case COMPUTE:
                enumName = "COMPUTE";
                break;
            default:
                throw new IllegalArgumentException("Unsupported shader type: " + type);
        }
        Object[] constants = shaderTypeClass.getEnumConstants();
        for (Object c : constants) {
            if (c.toString().equals(enumName)) {
                return c;
            }
        }
        throw new IllegalStateException("MC ShaderType enum not found: " + enumName);
    }

    /**
     * 将 Iris ShaderType 映射为 MSL shader stage（用于 SPIRV-Cross）。
     *
     * <p>MSL stage 值：0=Vertex, 1=Fragment, 2=Kernel(Compute), 3=Geometry(不支持)</p>
     */
    private static int toMslShaderStage(ShaderType type) {
        switch (type) {
            case VERTEX:
                return 0;
            case FRAGMENT:
                return 1;
            case COMPUTE:
                return 2;
            case GEOMETRY:
            case TESSELATION_CONTROL:
            case TESSELATION_EVAL:
                // Metal 不直接支持 geometry/tessellation shader
                // 需要用 mesh shader 或 post-tessellation vertex shader 模拟
                // 本版本暂不支持，返回 -1 让 native 层报错
                return -1;
            default:
                return -1;
        }
    }

    /**
     * 编译结果。
     */
    public static final class CompileResult {
        private final boolean success;
        @Nullable
        private final String mslSource;
        @Nullable
        private final String error;

        private CompileResult(boolean success, @Nullable String mslSource, @Nullable String error) {
            this.success = success;
            this.mslSource = mslSource;
            this.error = error;
        }

        static CompileResult success(String msl) {
            return new CompileResult(true, msl, null);
        }

        static CompileResult failure(String error) {
            return new CompileResult(false, null, error);
        }

        public boolean isSuccess() {
            return success;
        }

        @Nullable
        public String getMslSource() {
            return mslSource;
        }

        @Nullable
        public String getError() {
            return error;
        }
    }
}

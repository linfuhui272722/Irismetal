package net.irisshaders.iris.metal.shader;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.ShaderType;
import net.irisshaders.iris.metal.bridge.IrisMetalNativeBridge;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import org.lwjgl.util.shaderc.Shaderc;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
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
     * <p>使用 LWJGL 的 shaderc 库编译 GLSL 到 SPIR-V，与 metallum 一致。</p>
     */
    private static byte[] compileGlslToSpirv(String name, ShaderType type, String glslSource) throws Exception {
        // 适配 GLSL 为 Vulkan 风格
        String vulkanGlsl = adaptGlslForVulkan(glslSource, type);

        // 获取 shaderc 的 shader kind
        int shadercKind = getShadercKind(type);

        // 初始化 shaderc
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0) {
            throw new Exception("Failed to initialize shaderc compiler for " + name);
        }

        long options = Shaderc.shaderc_compile_options_initialize();
        if (options == 0) {
            Shaderc.shaderc_compiler_release(compiler);
            throw new Exception("Failed to initialize shaderc options for " + name);
        }

        try {
            // 设置目标环境为 Vulkan
            Shaderc.shaderc_compile_options_set_target_env(
                options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2
            );
            
            // 自动绑定 uniforms 和映射位置
            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
            Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);

            // 编译 GLSL 到 SPIR-V
            long result = Shaderc.shaderc_compile_into_spv(
                compiler, vulkanGlsl, shadercKind, name, "main", options
            );

            try {
                int status = Shaderc.shaderc_result_get_compilation_status(result);
                if (status != Shaderc.shaderc_compilation_status_success) {
                    String errorMessage = Shaderc.shaderc_result_get_error_message(result);
                    Iris.logger.error("Shader compilation failed for {}: {}", name, errorMessage);
                    throw new Exception("Shaderc compilation failed for " + name + ": " + errorMessage);
                }

                ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
                if (bytes == null || bytes.remaining() < 20) {
                    throw new Exception("Shaderc produced empty SPIR-V for " + name);
                }

                byte[] spirv = new byte[bytes.remaining()];
                bytes.get(spirv);
                return spirv;
            } finally {
                Shaderc.shaderc_result_release(result);
            }
        } finally {
            Shaderc.shaderc_compile_options_release(options);
            Shaderc.shaderc_compiler_release(compiler);
        }
    }

    /**
     * 将 ShaderType 映射到 shaderc kind
     */
    private static int getShadercKind(ShaderType type) {
        return switch (type) {
            case VERTEX -> Shaderc.shaderc_glsl_vertex_shader;
            case FRAGMENT -> Shaderc.shaderc_glsl_fragment_shader;
            case GEOMETRY -> Shaderc.shaderc_glsl_geometry_shader;
            case TESSELATION_CONTROL -> Shaderc.shaderc_glsl_tess_control_shader;
            case TESSELATION_EVAL -> Shaderc.shaderc_glsl_tess_evaluation_shader;
            case COMPUTE -> Shaderc.shaderc_glsl_compute_shader;
        };
    }
    /**
     * 将 GLSL 源码适配为 Vulkan 风格。
     *
     * <p>Vulkan GLSL 要求所有 non-opaque uniform 必须在 uniform block 中。
     * 本方法收集所有 loose uniform，创建 MetallumIrisUniforms block，并删除 loose uniform 声明。
     * 在 GLSL 中，block 成员可以直接通过名称访问，所以不需要替换引用。</p>
     */
    private static String adaptGlslForVulkan(String source, ShaderType type) {
        String result = source;

        // 检查 shader 版本
        if (result.contains("#version 330")) {
            result = result.replace("#version 330 core", "#version 450 core");
            result = result.replace("#version 330", "#version 450 core");
            Iris.logger.info("[Iris-Metal] Upgraded shader from #version 330 to #version 450");
        }

        // 收集所有 loose uniform 并创建 MetallumIrisUniforms block
        result = wrapLooseUniforms(result);
        
        // 打印转换后的 shader 预览
        String shaderPreview = result.substring(0, Math.min(1500, result.length())).replace("\n", "\\n");
        Iris.logger.info("[Iris-Metal] Adapted shader preview: {}", shaderPreview);

        return result;
    }
    
    /**
     * 收集所有 loose uniform，创建 MetallumIrisUniforms block，并删除 loose uniform 声明。
     * 参考 metallum 的 MetalIrisShaderCompiler.wrapLooseUniforms 实现。
     * 
     * 注意：sampler/image 类型不能放入 std140 uniform block，必须保留为 loose uniform。
     */
    private static String wrapLooseUniforms(String source) {
        List<String> blockUniforms = new ArrayList<>();
        List<String> samplerUniforms = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        String[] lines = source.split("\n");
        int braceDepth = 0;
        boolean hasExistingMetallumBlock = source.contains("uniform MetallumIrisUniforms");
        
        // 遍历每一行，收集 loose uniform
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            
            // 计算 brace depth
            for (char c : line.toCharArray()) {
                if (c == '{') braceDepth++;
                if (c == '}') braceDepth--;
            }
            
            String trimmed = line.trim();
            
            // 检查是否是顶层的 uniform 声明（不在 block 内，不是 block 定义）
            if (braceDepth == 0 && trimmed.startsWith("uniform ") && !trimmed.contains("{")) {
                String uniformDecl = extractUniformDeclaration(trimmed);
                if (uniformDecl != null && !uniformDecl.isEmpty()) {
                    if (isOpaqueType(trimmed)) {
                        // sampler/image 保留为 loose uniform
                        samplerUniforms.add(uniformDecl);
                        body.append(line).append("\n");
                        Iris.logger.info("[Iris-Metal] Keeping sampler uniform: {}", uniformDecl);
                    } else {
                        // non-opaque 类型放入 block
                        // 不添加到这个 body 中，因为我们会把它们移到 UBO 中
                        blockUniforms.add(uniformDecl);
                        Iris.logger.info("[Iris-Metal] Found block uniform: {}", uniformDecl);
                    }
                }
                continue;
            }
            
            body.append(line).append("\n");
        }
        
        // 如果已经存在 MetallumIrisUniforms block，直接返回
        if (hasExistingMetallumBlock) {
            Iris.logger.info("[Iris-Metal] MetallumIrisUniforms block already exists, skipping uniform wrapping");
            return source;
        }
        
        // 如果没有 block uniform，也直接返回
        if (blockUniforms.isEmpty()) {
            return source;
        }
        
        // 创建 MetallumIrisUniforms block
        StringBuilder block = new StringBuilder();
        block.append("\nlayout(std140) uniform MetallumIrisUniforms {\n");
        for (String uniform : blockUniforms) {
            block.append("    ").append(uniform).append(";\n");
        }
        block.append("} iris_uniforms;\n\n");
        
        // 在 directive prelude 之后插入 block
        String shaderBody = body.toString();
        int insertPos = findDirectivePreludeEnd(shaderBody);
        
        // 确保 insertPos 位置之前有换行符（以便 block 能正确地从新行开始）
        String prefix = shaderBody.substring(0, insertPos);
        if (!prefix.endsWith("\n")) {
            prefix += "\n";
            insertPos = insertPos + 1; // adjust since we added a character
        }
        
        String result = prefix + block.toString() + shaderBody.substring(insertPos);
        
        // 替换 shader body 中对 loose uniform 的直接引用为 iris_uniforms.xxx
        // 注意：只有在 UBO block 之后的位置才需要替换
        for (String uniformDecl : blockUniforms) {
            // uniformDecl 格式是 "类型 名称" 或 "类型 名称[数组大小]"
            String[] parts = uniformDecl.trim().split("\\s+");
            if (parts.length >= 2) {
                String varName = parts[parts.length - 1];
                // 移除数组大小后缀 [x]
                if (varName.contains("[")) {
                    varName = varName.substring(0, varName.indexOf("["));
                }
                
                // 在 UBO block 之后的 shader 代码中替换变量引用
                // UBO block 格式: "\nlayout(...) uniform MetallumIrisUniforms {\n    ...\n} iris_uniforms;\n\n"
                // 找到 "} iris_uniforms;" 所在行的行尾（换行符位置）
                String uboEndMarker = "} iris_uniforms;";
                int uboEndPos = result.indexOf(uboEndMarker);
                if (uboEndPos == -1) {
                    Iris.logger.warn("[Iris-Metal] UBO end marker not found for {}", varName);
                    continue;
                }
                // 找到该行之后的第一个换行符（即下一行的开始）
                int uboEndLinePos = result.indexOf("\n", uboEndPos);
                if (uboEndLinePos == -1) {
                    uboEndLinePos = result.length();
                } else {
                    uboEndLinePos++; // 跳过换行符
                }
                
                String uboBlock = result.substring(0, uboEndLinePos);
                String shaderCode = result.substring(uboEndLinePos);
                
                // 在 shaderCode 中查找并替换
                Iris.logger.info("[Iris-Metal] Before replace: shaderCode length = {}, contains {} = {}", 
                    shaderCode.length(), varName, countOccurrences(shaderCode, varName));
                
                // 调试：检查 shaderCode 的内容
                if (shaderCode.length() > 0) {
                    char firstChar = shaderCode.charAt(0);
                    char lastChar = shaderCode.charAt(shaderCode.length() - 1);
                    Iris.logger.info("[Iris-Metal] shaderCode: first='{}', last='{}', contains sunPathRotation={}", 
                        String.valueOf(firstChar), String.valueOf(lastChar), 
                        shaderCode.contains("sunPathRotation"));
                    // 打印 shaderCode 的前 100 字符
                    int printLen = Math.min(100, shaderCode.length());
                    Iris.logger.info("[Iris-Metal] shaderCode first {} chars: [{}]", printLen, shaderCode.substring(0, printLen));
                } else {
                    Iris.logger.warn("[Iris-Metal] shaderCode is EMPTY!");
                }
                
                String newShaderCode = replaceInShaderCode(shaderCode, varName);
                
                int replaceCount = countOccurrences(shaderCode, varName) - countOccurrences(newShaderCode, varName);
                if (replaceCount > 0) {
                    Iris.logger.info("[Iris-Metal] Replaced {} occurrences of {}", replaceCount, varName);
                }
                
                result = uboBlock + newShaderCode;
            }
        }
        
        // 调试：打印完整结果的最后 1000 字符
        if (result.length() > 1000) {
            String tail = result.substring(result.length() - 1000);
            Iris.logger.info("[Iris-Metal] Result last 1000 chars:\n{}", tail);
        } else {
            Iris.logger.info("[Iris-Metal] Complete result:\n{}", result);
        }
        
        return result;
    }
    
    /**
     * 计算字符串中某个子串出现的次数
     */
    private static int countOccurrences(String str, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = str.indexOf(sub, idx)) != -1) {
            count++;
            idx += sub.length();
        }
        return count;
    }
    
    /**
     * 在 shader 代码中替换 uniform 变量引用
     * 只替换不在 uniform 声明行中的引用
     * 使用简单的字符串替换，但确保只替换完整的单词
     */
    private static String replaceInShaderCode(String shaderCode, String varName) {
        // 简单的正则表达式替换：\bvarName\b 但排除 uniform 声明行
        // 我们逐行处理
        StringBuilder result = new StringBuilder();
        String[] lines = shaderCode.split("\n", -1);
        
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            
            // 检查是否是 uniform 声明行
            String trimmedLine = line.trim();
            if (trimmedLine.startsWith("uniform ") || trimmedLine.startsWith("layout(")) {
                // uniform 声明行，跳过
                result.append(line);
            } else {
                // 替换行中的变量引用（确保是完整单词）
                line = replaceWordInLine(line, varName);
                result.append(line);
            }
            
            if (i < lines.length - 1) {
                result.append("\n");
            }
        }
        
        return result.toString();
    }
    
    /**
     * 在一行代码中替换变量名（确保是完整单词）
     */
    private static String replaceWordInLine(String line, String varName) {
        // 使用正则表达式匹配完整单词
        // 单词边界：前面不是字母、数字、下划线，后面也不是
        String pattern = "(?<![\\w])" + varName + "(?![\\w])";
        return line.replaceAll(pattern, "iris_uniforms." + varName);
    }
    
    /**
     * 检查是否是 opaque 类型（sampler, image, texture 等）
     * Opaque 类型的 uniform 不能放入 std140 uniform block
     */
    private static boolean isOpaqueType(String declaration) {
        // 检查类型部分（第一个关键字）
        String trimmed = declaration.trim();
        if (trimmed.startsWith("uniform ")) {
            trimmed = trimmed.substring(8);
        }
        
        // 获取类型关键字
        String[] parts = trimmed.split("\\s+");
        if (parts.length == 0) return false;
        
        String type = parts[0].toLowerCase();
        
        // Opaque 类型：sampler, image, texture, atomic_uint
        if (type.contains("sampler") || type.contains("image") || type.contains("texture") || type.equals("atomic_uint")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * 从 uniform 声明中提取 "类型 名称" 部分
     */
    private static String extractUniformDeclaration(String declaration) {
        String trimmed = declaration.trim();
        if (trimmed.startsWith("uniform ")) {
            trimmed = trimmed.substring(8);
        }
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.trim();
    }
    
    /**
     * 找到 directive prelude（#version, #extension 等）结束的位置
     */
    private static int findDirectivePreludeEnd(String source) {
        int index = 0;
        int length = source.length();
        while (index < length) {
            int lineEnd = source.indexOf('\n', index);
            if (lineEnd < 0) {
                lineEnd = length;
            }
            String line = source.substring(index, lineEnd).trim();
            if (!line.isEmpty() && !line.startsWith("#")) {
                return index;
            }
            index = lineEnd + 1;
        }
        return index;
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

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
    // MSL 版本：使用 4.0（Metal 4.0），与 metallum 保持一致
    public static final int MSL_VERSION_3_0 = 0x30000;
    public static final int MSL_VERSION_4_0 = 0x40000;

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
            // 使用 MSL 3.0
            Iris.logger.info("[Iris-Metal] About to call SPIRVToMslConverter.convert for {}", name);
            String msl = SPIRVToMslConverter.convert(spirv, MSL_VERSION_3_0);
            Iris.logger.info("[Iris-Metal] SPIRVToMslConverter.convert returned for {}", name);
            if (msl == null || msl.isEmpty()) {
                return CompileResult.failure("SPIRV-Cross returned empty MSL for " + name);
            }
            
            Iris.logger.info("[Iris-Metal] MSL compilation succeeded for {}, MSL length={}", name, msl.length());
            return CompileResult.success(msl);
        } catch (Throwable t) {
            Iris.logger.error("[Iris-Metal] Exception during MSL compilation for " + name, t);
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
        Iris.logger.info("[Iris-Metal] About to adapt GLSL for {}, input length = {}", name, glslSource.length());
        String vulkanGlsl = adaptGlslForVulkan(glslSource, type);
        Iris.logger.info("[Iris-Metal] GLSL adapted for {}, output length = {}", name, vulkanGlsl.length());

        // 获取 shaderc 的 shader kind
        int shadercKind = getShadercKind(type);

        // 初始化 shaderc
        Iris.logger.info("[Iris-Metal] Initializing shaderc compiler for {}", name);
        long compiler = Shaderc.shaderc_compiler_initialize();
        if (compiler == 0) {
            throw new Exception("Failed to initialize shaderc compiler for " + name);
        }
        Iris.logger.info("[Iris-Metal] Shaderc compiler initialized for {}", name);

        Iris.logger.info("[Iris-Metal] Initializing shaderc options for {}", name);
        long options = Shaderc.shaderc_compile_options_initialize();
        if (options == 0) {
            Shaderc.shaderc_compiler_release(compiler);
            throw new Exception("Failed to initialize shaderc options for " + name);
        }
        Iris.logger.info("[Iris-Metal] Shaderc options initialized for {}", name);

        try {
            // 设置目标环境为 Vulkan
            Shaderc.shaderc_compile_options_set_target_env(
                options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2
            );
            
            // 自动绑定 uniforms 和映射位置
            Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
            Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);

            // 编译 GLSL 到 SPIR-V
            Iris.logger.info("[Iris-Metal] About to compile GLSL to SPIR-V for {}, GLSL length = {}", name, vulkanGlsl.length());
            long result = Shaderc.shaderc_compile_into_spv(
                compiler, vulkanGlsl, shadercKind, name, "main", options
            );
            Iris.logger.info("[Iris-Metal] SPIR-V compilation completed for {}, checking status...", name);

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
                Iris.logger.info("[Iris-Metal] SPIR-V generated for {}: {} bytes ({} words)", 
                    name, spirv.length, spirv.length / 4);
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

        // 处理 gl_VertexID - Shaderc 在 #version 330 中需要显式声明
        // 使用 in int gl_VertexID; 声明，Metal 会自动提供
        if (type == ShaderType.VERTEX && result.contains("gl_VertexID")) {
            result = result.replace("gl_VertexID", "iris_VertexID");
            // 在第一个 uniform 块之前添加声明
            int firstUniformPos = result.indexOf("uniform ");
            if (firstUniformPos > 0) {
                result = result.substring(0, firstUniformPos) + 
                         "in int iris_VertexID;\n" + 
                         result.substring(firstUniformPos);
                Iris.logger.info("[Iris-Metal] Renamed gl_VertexID to iris_VertexID declaration");
            }
        }

        // 收集所有 loose uniform 并创建 MetallumIrisUniforms block
        // 注意：wrapLooseUniforms 会处理 dhMaterialId 和 dhRenderDistance 等 uniforms
        // 如果它们已经作为 loose uniforms 存在，会被自动移到 MetallumIrisUniforms 中
        result = wrapLooseUniforms(result);

        // 为 DH (Distant Horizons) shader 声明缺失的 uniforms
        // 在 wrapLooseUniforms 之后检查，因为 loose uniforms 可能已经被处理
        // 注意：只添加那些没有被 wrapLooseUniforms 处理过的 uniforms
        // 检查 MetallumIrisUniforms block 是否已经有这些 uniforms
        int blockStart = result.indexOf("uniform MetallumIrisUniforms");
        int blockEnd = result.indexOf("} iris_uniforms;");
        if (blockStart >= 0 && blockEnd > blockStart) {
            String blockContent = result.substring(blockStart, blockEnd);
            // 检查 dhMaterialId 是否在 block 中
            if (!blockContent.contains("dhMaterialId") && result.contains("dhMaterialId")) {
                int insertPos = blockEnd;
                String dhUniformDecl = "\n    int dhMaterialId;";
                result = result.substring(0, insertPos) + dhUniformDecl + result.substring(insertPos);
                Iris.logger.info("[Iris-Metal] Added dhMaterialId to MetallumIrisUniforms block");
            }
            // 检查 dhRenderDistance 是否在 block 中
            if (!blockContent.contains("dhRenderDistance") && result.contains("dhRenderDistance")) {
                int insertPos = blockEnd;
                String dhUniformDecl = "\n    float dhRenderDistance;";
                result = result.substring(0, insertPos) + dhUniformDecl + result.substring(insertPos);
                Iris.logger.info("[Iris-Metal] Added dhRenderDistance to MetallumIrisUniforms block");
            }
        }
        
        // 打印转换后的 shader 预览（增加长度，并写入文件以避免日志截断）
        int previewLength = Math.min(3000, result.length());
        String shaderPreview = result.substring(0, previewLength).replace("\n", "\\n");
        Iris.logger.info("[Iris-Metal] Adapted shader preview (first {} of {} chars): {}", 
            previewLength, result.length(), shaderPreview);
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
                        // 仍然添加到 body，因为这些 uniform 不在 MetallumIrisUniforms 中
                        body.append(line).append("\n");
                        Iris.logger.info("[Iris-Metal] Keeping sampler uniform: {}", uniformDecl);
                    } else {
                        // non-opaque 类型放入 block
                        // 不添加到这个 body 中，因为我们会把它们移到 UBO 中
                        // 注意：这里我们不追加到 body，相当于从源码中移除了这个 uniform 声明
                        // 一条 uniform 声明可能声明多个变量（如 "float a, b, c"），
                        // 这里拆分为单独的 "类型 名称" 条目，确保后续逐个变量重写引用时不会遗漏。
                        List<String> splitDecls = splitMultiDeclarator(uniformDecl);
                        blockUniforms.addAll(splitDecls);
                        Iris.logger.info("[Iris-Metal] Found block uniform (will be moved to MetallumIrisUniforms): {}", uniformDecl);
                    }
                }
                continue; // 跳过添加这个 uniform 声明到 body
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
                    boolean hasSunPath = shaderCode.contains("sunPathRotation");
                    boolean hasMain = shaderCode.contains("void main()");
                    boolean hasConst = shaderCode.contains("const float");
                    Iris.logger.info("[Iris-Metal] shaderCode check: has sunPathRotation={}, has main()={}, has const={}, starts with newline={}", 
                        hasSunPath, hasMain, hasConst, shaderCode.charAt(0) == '\n');
                    // 打印 shaderCode 的前 200 字符（用 \\n 替换换行符以便日志显示）
                    int printLen = Math.min(200, shaderCode.length());
                    String head = shaderCode.substring(0, printLen).replace("\n", "\\n").replace("\r", "\\r");
                    Iris.logger.info("[Iris-Metal] shaderCode head (first {}): [{}]", printLen, head);
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
        
        // 调试：打印完整结果的最后 1500 字符（用 \\n 替换换行符以便日志显示）
        if (result.length() > 1500) {
            String tail = result.substring(result.length() - 1500).replace("\n", "\\n").replace("\r", "\\r");
            Iris.logger.info("[Iris-Metal] Result last 1500 chars (newlines replaced): [{}]", tail);
        } else {
            String fullResult = result.replace("\n", "\\n").replace("\r", "\\r");
            Iris.logger.info("[Iris-Metal] Complete result (newlines replaced): [{}]", fullResult);
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
     * 在 shader 代码中替换 uniform 变量引用为 iris_uniforms.xxx。
     *
     * <p>处理三类需要保留原样的情况：
     * <ul>
     *   <li>uniform / layout 声明行：这些 loose uniform 已被移入 UBO，原声明已从源码删除，
     *       不应再替换；</li>
     *   <li>同名局部变量声明（如 "float shadowFade = ..."）：局部变量会遮蔽 UBO 中的同名
     *       uniform，若改写成 "float iris_uniforms.shadowFade = ..." 会变成非法语法
     *       （unexpected DOT）；</li>
     *   <li>被同名局部变量遮蔽的作用域内的所有引用：从该局部声明所在花括号块开始，直到该块
     *       结束，varName 都指代局部变量，应保持裸名（由局部声明提供符号），不能改成
     *       iris_uniforms.xxx（否则会引用 UBO 成员，既语义错误，又会因对 uniform 赋值而
     *       编译失败）。</li>
     * </ul>
     */
    private static String replaceInShaderCode(String shaderCode, String varName) {
        StringBuilder result = new StringBuilder();
        String[] lines = shaderCode.split("\n", -1);

        int depth = 0;            // 当前花括号深度（每行起始处的深度）
        int shadowDepth = -1;     // 同名局部变量声明所在深度；-1 表示当前未被遮蔽

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmedLine = line.trim();

            // 先判断这一行处理前的深度是否已经退出遮蔽作用域
            if (shadowDepth >= 0 && depth < shadowDepth) {
                shadowDepth = -1;
            }

            boolean isUniformDecl = trimmedLine.startsWith("uniform ") || trimmedLine.startsWith("layout(");
            boolean isLocalDecl = !isUniformDecl && shadowDepth < 0 && declaresLocalVariable(trimmedLine, varName);

            if (isUniformDecl) {
                // uniform / layout 声明行，跳过
                result.append(line);
            } else if (isLocalDecl) {
                // 声明了同名局部变量：进入遮蔽作用域。
                // 局部变量所在深度 = 当前行起始深度 + 声明 token 之前的净花括号数，
                // 这样即使声明与 { 在同一行也能正确计算其所在块。
                shadowDepth = depth + netBracesBeforeToken(trimmedLine, varName);
                result.append(line);
            } else if (shadowDepth >= 0) {
                // 处于同名局部变量的遮蔽作用域内，保持裸名（指向局部变量）
                result.append(line);
            } else {
                // 替换行中的变量引用（确保是完整单词）
                line = replaceWordInLine(line, varName);
                result.append(line);
            }

            // 更新花括号深度（按本行出现的 { 和 } 个数）
            depth += countChar(line, '{') - countChar(line, '}');

            if (i < lines.length - 1) {
                result.append("\n");
            }
        }

        return result.toString();
    }

    /**
     * 计算一行中某个 token 第一次（作为完整单词）出现位置之前的净花括号数
     * （左括号个数减右括号个数）。用于确定该 token 所处的花括号深度。
     */
    private static int netBracesBeforeToken(String line, String token) {
        String pattern = "(?<![\\w])" + token + "(?![\\w])";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(line);
        if (!m.find()) return 0;
        int end = m.start();
        int opens = 0, closes = 0;
        for (int i = 0; i < end; i++) {
            char c = line.charAt(i);
            if (c == '{') opens++;
            else if (c == '}') closes++;
        }
        return opens - closes;
    }

    /**
     * 统计一行中某个字符出现的次数。
     */
    private static int countChar(String line, char c) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            if (line.charAt(i) == c) count++;
        }
        return count;
    }

    /**
     * 检查一行是否声明了名为 varName 的局部变量。
     * 形如 "float shadowFade = ..."、"vec3 sunVec;"、"int frameCounter = 0, foo;"。
     *
     * <p>关键：只把 varName 当作"被声明的变量名"来识别，而不是出现在赋值右侧/表达式中的引用。
     * 例如 "float time = frameTimeCounter * 1.0f;" 不应把 frameTimeCounter 当成局部声明，
     * 否则会误判进入遮蔽作用域，导致后续所有 uniform 引用都不被改写（undeclared identifier）。
     * 因此先去掉行首 qualifier/precision 与类型关键字得到"声明符列表"，再逐个声明符取
     * '=' 之前的部分作为变量名进行比较。
     */
    private static boolean declaresLocalVariable(String trimmedLine, String varName) {
        if (trimmedLine.isEmpty()) return false;
        // 行首必须是 GLSL 类型关键字（含可选 precision/qualifier 前缀）才可能是变量声明
        if (!isDeclarationStart(trimmedLine)) return false;

        String declList = stripTypeAndQualifiers(trimmedLine);
        if (declList == null || declList.isEmpty()) return false;
        // 按"顶层逗号"拆分声明符（忽略括号/方括号/花括号内的逗号），避免把
        // vec4(a, b, c) 构造函数参数里的逗号当成声明符分隔符——否则构造函数实参
        // "gbufferProjectionInverse[1].y" 会被当成一个声明符，其 '[' 前的名字
        // gbufferProjectionInverse 恰好等于 varName，误判为同名局部声明。
        // 每个声明符形如: name [array] [= initializer]
        // 只取第一个 '=' 之前作为"名字部分"，initializer 里出现的 varName 不算声明。
        List<String> declarators = splitTopLevelCommas(declList);
        for (String d : declarators) {
            String decl = d.trim();
            if (decl.isEmpty()) continue;
            int eq = decl.indexOf('=');
            String namePart = (eq >= 0) ? decl.substring(0, eq) : decl;
            namePart = namePart.trim();
            // 取数组下标之前部分作为变量名
            int lb = namePart.indexOf('[');
            if (lb >= 0) namePart = namePart.substring(0, lb).trim();
            if (namePart.equals(varName)) return true;
        }
        return false;
    }

    /**
     * 按"顶层逗号"拆分字符串，忽略括号 ()、方括号 []、花括号 {} 内的逗号。
     * 用于正确拆分声明符列表，避免把 vec4(a, b, c) 构造函数参数里的逗号当成声明符分隔符。
     */
    private static List<String> splitTopLevelCommas(String s) {
        List<String> result = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (c == ',' && depth == 0) {
                result.add(s.substring(start, i));
                start = i + 1;
            }
        }
        result.add(s.substring(start));
        return result;
    }

    /**
     * 去掉行首的 qualifier/precision 前缀与类型关键字，返回剩余的声明符列表部分。
     * 例如 "const float shadowFade = 1.0f;" -> "shadowFade = 1.0f;"
     * 返回 null 表示无法识别。
     */
    private static String stripTypeAndQualifiers(String trimmedLine) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
            "^\\s*((?:const\\s+|highp\\s+|mediump\\s+|lowp\\s+|in\\s+|out\\s+|inout\\s+)*)([\\w]+)\\b(.*)$"
        ).matcher(trimmedLine);
        if (!m.matches()) return null;
        String rest = m.group(3);
        if (rest == null) return null;
        return rest.trim();
    }

    /**
     * 判断一行的开头是否是 GLSL 类型关键字（即这是一条变量/对象声明语句）。
     * 覆盖 scalar/vector/matrix/整数类型，以及可选的 precision/qualifier 前缀
     * （highp/mediump/lowp/const/in/out/inout 等）。
     * 类型关键字后必须紧跟一个标识符（变量名），而不是 '('（那是函数定义/构造调用）。
     */
    private static boolean isDeclarationStart(String trimmedLine) {
        // 去掉可能的 qualifier 前缀后再取第一个 token 判断类型
        String[] tokens = trimmedLine.split("\\s+");
        int idx = 0;
        // 跳过 precision/qualifier 前缀
        while (idx < tokens.length) {
            String t = tokens[idx];
            if (t.equals("const") || t.equals("highp") || t.equals("mediump") || t.equals("lowp")
                    || t.equals("in") || t.equals("out") || t.equals("inout") || t.equals("uniform")) {
                idx++;
            } else {
                break;
            }
        }
        if (idx >= tokens.length) return false;
        String type = tokens[idx];
        // 处理形如 "ivec3" 或 "vec3[2]" 之类
        String base = type.split("\\[")[0];
        if (!isGlslTypeKeyword(base)) return false;
        // 类型后面必须紧跟一个标识符（变量名），而不是 '('（函数定义/构造调用）
        if (idx + 1 >= tokens.length) return false;
        String after = tokens[idx + 1];
        if (after.startsWith("(")) return false;
        return true;
    }

    /**
     * 判断一个 token 是否是 GLSL 内建类型关键字。
     */
    private static boolean isGlslTypeKeyword(String token) {
        if (token == null || token.isEmpty()) return false;
        String t = token.toLowerCase();
        if (t.equals("float") || t.equals("int") || t.equals("uint") || t.equals("bool")
                || t.equals("double")) return true;
        // vec2/vec3/vec4, ivec*, uvec*, bvec*, dvec*
        if (t.matches("^[iubd]?vec[234]$")) return true;
        // mat2/mat3/mat4, mat2x3... 等
        if (t.matches("^mat[234]([xX][234])?$")) return true;
        return false;
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
     * 将一条 uniform 声明拆分为多个单独的 "类型 名称" 条目。
     * 输入形如 "float a, b, c[3]"，输出为 ["float a", "float b", "float c[3]"]。
     * 也支持 precision/qualifier 前缀，如 "highp float a, b" -> ["highp float a", "highp float b"]。
     * 如果声明只有一个变量，则返回包含单个元素的列表。
     */
    private static List<String> splitMultiDeclarator(String uniformDecl) {
        List<String> result = new ArrayList<>();
        String trimmed = uniformDecl.trim();
        // 按逗号拆分变量名列表。注意：数组大小声明中不会出现裸逗号（如 float a[3]）。
        String[] segments = trimmed.split(",");
        if (segments.length == 0) {
            result.add(trimmed);
            return result;
        }
        // 第一个 segment 形如 "<type> <name1>"（可能含 precision/qualifier，如 "highp float a"），
        // 其余 segment 形如 "<nameN>" 或 "<nameN>[size]"。
        String firstSegment = segments[0].trim();
        String[] firstTokens = firstSegment.split("\\s+");
        if (firstTokens.length < 2) {
            // 无类型前缀的异常情况，原样返回
            for (String s : segments) {
                String t = s.trim();
                if (!t.isEmpty()) result.add(t);
            }
            if (result.isEmpty()) result.add(trimmed);
            return result;
        }
        // 类型前缀 = 除最后一个 token（name1）外的所有 token
        String name1 = firstTokens[firstTokens.length - 1];
        StringBuilder typePrefix = new StringBuilder();
        for (int i = 0; i < firstTokens.length - 1; i++) {
            if (i > 0) typePrefix.append(" ");
            typePrefix.append(firstTokens[i]);
        }
        String typeStr = typePrefix.toString();
        result.add(typeStr + " " + name1);
        for (int i = 1; i < segments.length; i++) {
            String n = segments[i].trim();
            if (!n.isEmpty()) {
                result.add(typeStr + " " + n);
            }
        }
        if (result.isEmpty()) {
            result.add(trimmed);
        }
        return result;
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

package net.irisshaders.iris.metal.shader;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.Iris;
import org.lwjgl.PointerBuffer;
import org.lwjgl.util.spvc.Spvc;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * 使用 LWJGL SPIRV-Cross 将 SPIR-V 转换为 MSL。
 * 
 * <p>这个类封装了 SPIRV-Cross 的调用，提供简单的 GLSL → MSL 转换功能。</p>
 */
@Environment(EnvType.CLIENT)
public final class SPIRVToMslConverter {
    private static final int MSL_VERSION_3_0 = 0x30000;
    private static final int MSL_VERSION_4_0 = 0x40000;
    
    private SPIRVToMslConverter() {}
    
    /**
     * 将 SPIR-V 字节码转换为 MSL 源码。
     *
     * @param spirv SPIR-V 字节码
     * @param mslVersion MSL 版本（如 0x30000 表示 MSL 3.0）
     * @return MSL 源码，如果转换失败返回 null
     */
    public static String convert(ByteBuffer spirv, int mslVersion) {
        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
            IntBuffer spirvWords = spirv.asIntBuffer();
            
            // 创建 context
            PointerBuffer pContext = stack.mallocPointer(1);
            int result = Spvc.spvc_context_create(pContext);
            if (result != Spvc.SPVC_SUCCESS) {
                Iris.logger.warn("Failed to create SPIRV-Cross context: {}", result);
                return null;
            }
            long context = pContext.get(0);
            
            try {
                // 解析 SPIR-V
                PointerBuffer pIr = stack.mallocPointer(1);
                result = Spvc.spvc_context_parse_spirv(context, spirvWords, spirvWords.remaining(), pIr);
                if (result != Spvc.SPVC_SUCCESS) {
                    Iris.logger.warn("Failed to parse SPIR-V: {}", result);
                    return null;
                }
                long ir = pIr.get(0);
                
                // 创建 MSL 编译器
                PointerBuffer pCompiler = stack.mallocPointer(1);
                result = Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_MSL, ir, Spvc.SPVC_CAPTURE_MODE_COPY, pCompiler);
                if (result != Spvc.SPVC_SUCCESS) {
                    Iris.logger.warn("Failed to create MSL compiler: {}", result);
                    return null;
                }
                long compiler = pCompiler.get(0);
                
                try {
                    // 创建选项
                    PointerBuffer pOptions = stack.mallocPointer(1);
                    result = Spvc.spvc_compiler_create_compiler_options(compiler, pOptions);
                    if (result != Spvc.SPVC_SUCCESS) {
                        Iris.logger.warn("Failed to create compiler options: {}", result);
                        return null;
                    }
                    long options = pOptions.get(0);
                    
                    // 设置 MSL 选项
                    Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_PLATFORM, Spvc.SPVC_MSL_PLATFORM_MACOS);
                    Spvc.spvc_compiler_options_set_uint(options, Spvc.SPVC_COMPILER_OPTION_MSL_VERSION, mslVersion > 0 ? mslVersion : MSL_VERSION_3_0);
                    Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_ENABLE_DECORATION_BINDING, true);
                    Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_MSL_TEXTURE_BUFFER_NATIVE, true);
                    Spvc.spvc_compiler_options_set_bool(options, Spvc.SPVC_COMPILER_OPTION_FLIP_VERTEX_Y, true);
                    
                    // 安装选项
                    result = Spvc.spvc_compiler_install_compiler_options(compiler, options);
                    if (result != Spvc.SPVC_SUCCESS) {
                        Iris.logger.warn("Failed to install compiler options: {}", result);
                        return null;
                    }
                    
                    // 获取活跃的接口变量
                    PointerBuffer pActiveSet = stack.mallocPointer(1);
                    result = Spvc.spvc_compiler_get_active_interface_variables(compiler, pActiveSet);
                    if (result == Spvc.SPVC_SUCCESS) {
                        Spvc.spvc_compiler_set_enabled_interface_variables(compiler, pActiveSet.get(0));
                    }
                    
                    // 编译
                    PointerBuffer pSource = stack.mallocPointer(1);
                    result = Spvc.spvc_compiler_compile(compiler, pSource);
                    if (result != Spvc.SPVC_SUCCESS) {
                        Iris.logger.warn("Failed to compile: {}", result);
                        return null;
                    }
                    
                    long sourcePtr = pSource.get(0);
                    // 使用 safe copy - 分配一个临时的 Java 字符串而不是依赖 native 指针
                    if (sourcePtr == 0) {
                        return null;
                    }
                    // 使用 memUTF8 带最大长度限制，避免读取越界
                    // 限制为 1MB 应该足够
                    return org.lwjgl.system.MemoryUtil.memUTF8(sourcePtr, 1024 * 1024);
                    
                } finally {
                    Spvc.spvc_context_destroy(context);
                }
                
            } finally {
                Spvc.spvc_context_destroy(context);
            }
        }
    }
    
    /**
     * 将 SPIR-V 字节数组转换为 MSL 源码。
     */
    public static String convert(byte[] spirv, int mslVersion) {
        return convert(ByteBuffer.wrap(spirv), mslVersion);
    }
    
    /**
     * 使用 MSL 3.0 转换。
     */
    public static String convertToMsl3(byte[] spirv) {
        return convert(spirv, MSL_VERSION_3_0);
    }
    
    /**
     * 使用 MSL 4.0 转换。
     */
    public static String convertToMsl4(byte[] spirv) {
        return convert(spirv, MSL_VERSION_4_0);
    }
}

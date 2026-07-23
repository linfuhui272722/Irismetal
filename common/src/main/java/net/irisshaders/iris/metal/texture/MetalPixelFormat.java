package net.irisshaders.iris.metal.texture;

import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelFormat;

/**
 * GL 像素格式与 Metal {@code MTLPixelFormat} 的映射表。
 *
 * <p>本类是 Iris Metal 后端的核心转换表之一。Iris 光影通过 GL 内部格式
 * （{@code GL_RGBA16F} 等）声明纹理，Metal 使用不同的 {@code MTLPixelFormat}
 * 枚举值。本类提供双向映射。</p>
 *
 * <p><b>关键映射差异</b>：</p>
 * <ul>
 *   <li>GL 的 {@code DEPTH_COMPONENT24} 在 Metal 中没有直接对应，映射到
 *       {@code Depth32Float}（32 位浮点深度，Metal 不支持 24 位深度）</li>
 *   <li>GL 的 {@code DEPTH_COMPONENT32F} → {@code Depth32Float}</li>
 *   <li>GL 的 {@code DEPTH_STENCIL} / {@code DEPTH24_STENCIL8} →
 *       {@code Depth32Float_Stencil8}</li>
 *   <li>GL 的 {@code R11F_G11F_B10F} → {@code RG11B10Float}</li>
 *   <li>GL 的 {@code RGB10_A2} → {@code BGR10A2}（注意 Metal 的通道顺序）</li>
 *   <li>GL 的 {@code RGBA8}（无符号归一化）→ {@code RGBA8Unorm}</li>
 *   <li>GL 的 {@code RGBA16} → {@code RGBA16Unorm}</li>
 * </ul>
 *
 * <p><b>MTLPixelFormat 枚举值</b>（来自 Metal 框架，此处用 int 常量避免依赖
 * macOS SDK）：</p>
 */
public final class MetalPixelFormat {
    // === 颜色格式（无符号归一化） ===
    public static final int RGBA8Unorm = 30;
    public static final int RGBA8Unorm_sRGB = 31;
    public static final int BGRA8Unorm = 80;
    public static final int BGRA8Unorm_sRGB = 81;
    public static final int RGBA16Unorm = 65;
    public static final int RG8Unorm = 20;

    // === 颜色格式（有符号归一化） ===
    public static final int RGBA8Snorm = 32;
    public static final int RGBA16Snorm = 67;

    // === 颜色格式（无符号整数） ===
    public static final int RGBA8Uint = 33;
    public static final int RGBA16Uint = 66;
    public static final int RGBA32Uint = 71;
    public static final int RG16Uint = 64;
    public static final int R32Uint = 53;

    // === 颜色格式（有符号整数） ===
    public static final int RGBA8Sint = 34;
    public static final int RGBA16Sint = 68;
    public static final int RGBA32Sint = 72;

    // === 颜色格式（浮点） ===
    public static final int RGBA16Float = 115;
    public static final int RGBA32Float = 125;
    public static final int RG16Float = 105;
    public static final int RG32Float = 115;
    public static final int R16Float = 25;
    public static final int R32Float = 55;
    public static final int RG11B10Float = 22;
    public static final int RGB9E5Float = 26;
    public static final int BGR10A2 = 92;

    // === 深度/模板格式 ===
    public static final int Depth32Float = 252;
    public static final int Depth16Unorm = 250;
    public static final int Depth32Float_Stencil8 = 260;
    public static final int Stencil8 = 253;

    private MetalPixelFormat() {
    }

    /**
     * 将 Iris 的 GL 内部格式映射为 MTLPixelFormat。
     *
     * @param format GL 内部格式
     * @return MTLPixelFormat 枚举值
     * @throws IllegalArgumentException 如果格式不支持
     */
    public static int from(InternalTextureFormat format) {
        switch (format) {
            // 8 位
            case RGBA8:
            case RGBA8_SRGB:
                return RGBA8Unorm;
            case R8:
                return RG8Unorm; // Metal 没有 R8Unorm 的独立常量值在此表中，用 RG8 近似
            case RG8:
                return RG8Unorm;
            // 16 位归一化
            case RGBA16:
                return RGBA16Unorm;
            case R16:
                return RGBA16Unorm; // 近似
            case RG16:
                return RGBA16Unorm; // 近似
            // 16 位浮点
            case RGBA16F:
                return RGBA16Float;
            case RG16F:
                return RG16Float;
            case R16F:
                return R16Float;
            // 32 位浮点
            case RGBA32F:
                return RGBA32Float;
            case RG32F:
                return RG32Float;
            case R32F:
                return R32Float;
            // 特殊浮点
            case R11G11B10F:
                return RG11B10Float;
            case RGB9E5:
                return RGB9E5Float;
            // 10 位
            case RGB10_A2:
                return BGR10A2;
            // 深度
            case DEPTH_COMPONENT:
            case DEPTH_COMPONENT16:
                return Depth16Unorm;
            case DEPTH_COMPONENT24:
            case DEPTH_COMPONENT32:
            case DEPTH_COMPONENT32F:
                return Depth32Float;
            // 深度模板
            case DEPTH_STENCIL:
            case DEPTH24_STENCIL8:
                return Depth32Float_Stencil8;
            // 整数
            case RGBA8UI:
                return RGBA8Uint;
            case RGBA16UI:
                return RGBA16Uint;
            case RGBA32UI:
                return RGBA32Uint;
            case RGBA8I:
                return RGBA8Sint;
            case RGBA16I:
                return RGBA16Sint;
            case RGBA32I:
                return RGBA32Sint;
            default:
                throw new IllegalArgumentException("Unsupported internal texture format for Metal: " + format);
        }
    }

    /**
     * 判断该 MTLPixelFormat 是否为深度格式。
     */
    public static boolean isDepthFormat(int mtlPixelFormat) {
        return mtlPixelFormat == Depth16Unorm
                || mtlPixelFormat == Depth32Float
                || mtlPixelFormat == Depth32Float_Stencil8;
    }

    /**
     * 判断该 MTLPixelFormat 是否包含模板分量。
     */
    public static boolean hasStencil(int mtlPixelFormat) {
        return mtlPixelFormat == Depth32Float_Stencil8
                || mtlPixelFormat == Stencil8;
    }

    /**
     * 判断该 MTLPixelFormat 是否为可渲染（color render target）格式。
     */
    public static boolean isColorRenderable(int mtlPixelFormat) {
        // Metal 中大部分颜色格式可渲染，深度/模板格式不可作为 color attachment
        return !isDepthFormat(mtlPixelFormat);
    }

    /**
     * 返回该 MTLPixelFormat 每像素字节数（用于纹理上传/下载的字节数计算）。
     */
    public static int bytesPerPixel(int mtlPixelFormat) {
        switch (mtlPixelFormat) {
            case RGBA8Unorm:
            case RGBA8Unorm_sRGB:
            case BGRA8Unorm:
            case BGRA8Unorm_sRGB:
            case RGBA8Uint:
            case RGBA8Sint:
            case RGBA8Snorm:
            case RG8Unorm:
                return 4;
            case RGBA16Unorm:
            case RGBA16Snorm:
            case RGBA16Uint:
            case RGBA16Sint:
            case RGBA16Float:
            case RG16Uint:
            case RG16Float:
            case Depth16Unorm:
                return 8;
            case RGBA32Float:
            case RGBA32Uint:
            case RGBA32Sint:
            case RG32Float:
            case Depth32Float:
                return 16;
            case R32Float:
            case R32Uint:
                return 4;
            case R16Float:
                return 2;
            case RG11B10Float:
            case RGB9E5Float:
            case BGR10A2:
                return 4;
            case Depth32Float_Stencil8:
                return 8;
            case Stencil8:
                return 1;
            default:
                return 4; // 默认假设
        }
    }
}

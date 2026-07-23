package net.irisshaders.iris.metal.blending;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Metal blend 状态描述，对应 Iris 原有的 {@code BlendMode}。
 *
 * <p>本类是不可变值对象，描述一个 color attachment 的 blend 配置。
 * 在创建 {@code MTLRenderPipelineState} 时使用。</p>
 *
 * <p><b>GL blend factor → Metal blend factor 映射</b>：</p>
 * <pre>
 * GL_ZERO                          → MTLBlendFactorZero (0)
 * GL_ONE                           → MTLBlendFactorOne (1)
 * GL_SRC_COLOR                     → MTLBlendFactorSourceColor (2)
 * GL_ONE_MINUS_SRC_COLOR           → MTLBlendFactorOneMinusSourceColor (3)
 * GL_DST_COLOR                     → MTLBlendFactorDestinationColor (4)
 * GL_ONE_MINUS_DST_COLOR           → MTLBlendFactorOneMinusDestinationColor (5)
 * GL_SRC_ALPHA                     → MTLBlendFactorSourceAlpha (6)
 * GL_ONE_MINUS_SRC_ALPHA           → MTLBlendFactorOneMinusSourceAlpha (7)
 * GL_DST_ALPHA                     → MTLBlendFactorDestinationAlpha (8)
 * GL_ONE_MINUS_DST_ALPHA           → MTLBlendFactorOneMinusDestinationAlpha (9)
 * GL_CONSTANT_COLOR                → MTLBlendFactorBlendColor (10)
 * GL_ONE_MINUS_CONSTANT_COLOR      → MTLBlendFactorOneMinusBlendColor (11)
 * GL_CONSTANT_ALPHA                → MTLBlendFactorBlendAlpha (12)
 * GL_ONE_MINUS_CONSTANT_ALPHA      → MTLBlendFactorOneMinusBlendAlpha (13)
 * GL_SRC_ALPHA_SATURATE            → MTLBlendFactorSourceAlphaSaturated (14)
 * GL_SRC1_COLOR / GL_SRC1_ALPHA    → MTLBlendFactorSource1Color/Alpha (15/16)
 * </pre>
 *
 * <p><b>GL blend equation → Metal blend operation 映射</b>：</p>
 * <pre>
 * GL_FUNC_ADD              → MTLBlendOperationAdd (0)
 * GL_FUNC_SUBTRACT         → MTLBlendOperationSubtract (1)
 * GL_FUNC_REVERSE_SUBTRACT → MTLBlendOperationReverseSubtract (2)
 * GL_MIN                   → MTLBlendOperationMin (3)
 * GL_MAX                   → MTLBlendOperationMax (4)
 * </pre>
 */
@Environment(EnvType.CLIENT)
public final class MetalBlendState {
    private final boolean blendEnabled;
    private final int sourceRgbBlendFactor;
    private final int destinationRgbBlendFactor;
    private final int rgbBlendOperation;
    private final int sourceAlphaBlendFactor;
    private final int destinationAlphaBlendFactor;
    private final int alphaBlendOperation;

    public static final MetalBlendState DISABLED = new MetalBlendState(
            false, 1, 0, 0, 1, 0, 0);

    public static final MetalBlendState ALPHA_BLEND = new MetalBlendState(
            true, 6, 7, 0, 6, 7, 0);

    public static final MetalBlendState PREMULTIPLIED_ALPHA = new MetalBlendState(
            true, 1, 7, 0, 1, 7, 0);

    public MetalBlendState(boolean blendEnabled,
                           int sourceRgbBlendFactor, int destinationRgbBlendFactor, int rgbBlendOperation,
                           int sourceAlphaBlendFactor, int destinationAlphaBlendFactor, int alphaBlendOperation) {
        this.blendEnabled = blendEnabled;
        this.sourceRgbBlendFactor = sourceRgbBlendFactor;
        this.destinationRgbBlendFactor = destinationRgbBlendFactor;
        this.rgbBlendOperation = rgbBlendOperation;
        this.sourceAlphaBlendFactor = sourceAlphaBlendFactor;
        this.destinationAlphaBlendFactor = destinationAlphaBlendFactor;
        this.alphaBlendOperation = alphaBlendOperation;
    }

    public boolean blendEnabled() {
        return blendEnabled;
    }

    public int sourceRgbBlendFactor() {
        return sourceRgbBlendFactor;
    }

    public int destinationRgbBlendFactor() {
        return destinationRgbBlendFactor;
    }

    public int rgbBlendOperation() {
        return rgbBlendOperation;
    }

    public int sourceAlphaBlendFactor() {
        return sourceAlphaBlendFactor;
    }

    public int destinationAlphaBlendFactor() {
        return destinationAlphaBlendFactor;
    }

    public int alphaBlendOperation() {
        return alphaBlendOperation;
    }

    /**
     * 从 GL blend factor 常量转换为 Metal blend factor。
     */
    public static int glBlendFactorToMetal(int glFactor) {
        switch (glFactor) {
            case 0: return 0;  // GL_ZERO → Zero
            case 1: return 1;  // GL_ONE → One
            case 0x0300: return 2;  // GL_SRC_COLOR → SourceColor
            case 0x0301: return 3;  // GL_ONE_MINUS_SRC_COLOR → OneMinusSourceColor
            case 0x0306: return 4;  // GL_DST_COLOR → DestinationColor
            case 0x0307: return 5;  // GL_ONE_MINUS_DST_COLOR → OneMinusDestinationColor
            case 0x0302: return 6;  // GL_SRC_ALPHA → SourceAlpha
            case 0x0303: return 7;  // GL_ONE_MINUS_SRC_ALPHA → OneMinusSourceAlpha
            case 0x0304: return 8;  // GL_DST_ALPHA → DestinationAlpha
            case 0x0305: return 9;  // GL_ONE_MINUS_DST_ALPHA → OneMinusDestinationAlpha
            case 0x8001: return 10; // GL_CONSTANT_COLOR → BlendColor
            case 0x8002: return 11; // GL_ONE_MINUS_CONSTANT_COLOR → OneMinusBlendColor
            case 0x8003: return 12; // GL_CONSTANT_ALPHA → BlendAlpha
            case 0x8004: return 13; // GL_ONE_MINUS_CONSTANT_ALPHA → OneMinusBlendAlpha
            case 0x0308: return 14; // GL_SRC_ALPHA_SATURATE → SourceAlphaSaturated
            case 0x85F8: return 15; // GL_SRC1_COLOR → Source1Color
            case 0x85F9: return 16; // GL_ONE_MINUS_SRC1_COLOR → OneMinusSource1Color
            case 0x8585: return 17; // GL_SRC1_ALPHA → Source1Alpha
            case 0x8586: return 18; // GL_ONE_MINUS_SRC1_ALPHA → OneMinusSource1Alpha
            default: return 1; // 默认 One
        }
    }

    /**
     * 从 GL blend equation 转换为 Metal blend operation。
     */
    public static int glBlendEquationToMetal(int glEquation) {
        switch (glEquation) {
            case 0x8006: return 0; // GL_FUNC_ADD → Add
            case 0x800A: return 1; // GL_FUNC_SUBTRACT → Subtract
            case 0x800B: return 2; // GL_FUNC_REVERSE_SUBTRACT → ReverseSubtract
            case 0x8007: return 3; // GL_MIN → Min
            case 0x8008: return 4; // GL_MAX → Max
            default: return 0;
        }
    }
}

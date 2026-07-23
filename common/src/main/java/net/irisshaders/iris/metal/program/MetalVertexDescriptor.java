package net.irisshaders.iris.metal.program;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Metal vertex descriptor，描述顶点缓冲区的布局。
 *
 * <p>对应 GL 的 {@code glVertexAttribPointer} + {@code glEnableVertexAttribArray}。
 * 在 Metal 中，vertex descriptor 是 pipeline state 的一部分，在创建
 * {@code MTLRenderPipelineState} 时固定，不能像 GL 那样动态修改。</p>
 *
 * <p><b>Iris 的顶点布局</b>：Iris 光影的 gbuffers 程序接收 Sodium/vanilla 提供的
 * 顶点数据。MC 26.2 的顶点格式由 {@code VertexFormat} 定义，包含 position、color、
 * uv0（texture）、uv1（lightmap）、normal 等属性。本类负责将这些属性映射到
 * Metal 的 {@code MTLVertexAttributeDescriptor}。</p>
 *
 * <p><b>属性映射</b>：</p>
 * <pre>
 * position (3xFloat32) → attribute 0, format Float32x3, offset 0, buffer 0
 * color    (4xUInt8)   → attribute 1, format UChar8x4Normalized, offset 12, buffer 0
 * uv0      (2xFloat32) → attribute 2, format Float32x2, offset 16, buffer 0
 * uv1      (2xUInt16)  → attribute 3, format UShort16x2Normalized, offset 24, buffer 0
 * normal   (3xUInt8)   → attribute 4, format Char8x3Normalized, offset 28, buffer 0
 * </pre>
 */
@Environment(EnvType.CLIENT)
public final class MetalVertexDescriptor {
    // MTLVertexFormat 常量
    public static final int FORMAT_INVALID = 0;
    public static final int FORMAT_FLOAT2 = 1;
    public static final int FORMAT_FLOAT3 = 2;
    public static final int FORMAT_FLOAT4 = 3;
    public static final int FORMAT_UCHAR4_NORMALIZED = 4;
    public static final int FORMAT_USHORT2_NORMALIZED = 5;
    public static final int FORMAT_CHAR3_NORMALIZED = 6;
    public static final int FORMAT_UINT = 7;

    // MTLVertexStepFunction
    public static final int STEP_PER_VERTEX = 0;
    public static final int STEP_PER_INSTANCE = 1;

    private final Attribute[] attributes;
    private final int stride;

    public MetalVertexDescriptor(Attribute[] attributes, int stride) {
        this.attributes = attributes;
        this.stride = stride;
    }

    /**
     * 创建 MC 26.2 默认的 NEW_ENTITY / POSITION_COLOR_TEX_LIGHTMAP_NORMAL 顶点布局。
     */
    public static MetalVertexDescriptor defaultMcFormat() {
        Attribute[] attrs = {
                new Attribute(0, FORMAT_FLOAT3, 0, 0),           // position
                new Attribute(1, FORMAT_UCHAR4_NORMALIZED, 12, 0), // color
                new Attribute(2, FORMAT_FLOAT2, 16, 0),           // uv0 (texture)
                new Attribute(3, FORMAT_USHORT2_NORMALIZED, 24, 0),// uv1 (lightmap)
                new Attribute(4, FORMAT_CHAR3_NORMALIZED, 28, 0), // normal
        };
        return new MetalVertexDescriptor(attrs, 32);
    }

    public Attribute[] attributes() {
        return attributes;
    }

    public int stride() {
        return stride;
    }

    public static final class Attribute {
        public final int location;
        public final int format;
        public final int offset;
        public final int bufferIndex;

        public Attribute(int location, int format, int offset, int bufferIndex) {
            this.location = location;
            this.format = format;
            this.offset = offset;
            this.bufferIndex = bufferIndex;
        }
    }
}

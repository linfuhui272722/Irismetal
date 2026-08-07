package net.irisshaders.iris.metal.program;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.metal.bridge.IrisMetalNativeBridge;

import java.lang.foreign.MemorySegment;

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
    // MTLVertexFormat 常量 (根据 metallum 的 MTLVertexFormat 枚举)
    public static final int FORMAT_INVALID = 0;
    public static final int FORMAT_UCHAR2 = 1;
    public static final int FORMAT_UCHAR3 = 2;
    public static final int FORMAT_UCHAR4 = 3;
    public static final int FORMAT_CHAR2 = 4;
    public static final int FORMAT_CHAR3 = 5;
    public static final int FORMAT_CHAR4 = 6;
    public static final int FORMAT_UCHAR2_NORMALIZED = 7;
    public static final int FORMAT_UCHAR3_NORMALIZED = 8;
    public static final int FORMAT_UCHAR4_NORMALIZED = 9;
    public static final int FORMAT_CHAR2_NORMALIZED = 10;
    public static final int FORMAT_CHAR3_NORMALIZED = 11;
    public static final int FORMAT_CHAR4_NORMALIZED = 12;
    public static final int FORMAT_USHORT2 = 13;
    public static final int FORMAT_USHORT3 = 14;
    public static final int FORMAT_USHORT4 = 15;
    public static final int FORMAT_SHORT2 = 16;  // 用于 ivec2 (UV2 in MC 26.2)
    public static final int FORMAT_SHORT3 = 17;
    public static final int FORMAT_SHORT4 = 18;
    public static final int FORMAT_USHORT2_NORMALIZED = 19;
    public static final int FORMAT_USHORT3_NORMALIZED = 20;
    public static final int FORMAT_USHORT4_NORMALIZED = 21;
    public static final int FORMAT_SHORT2_NORMALIZED = 22;
    public static final int FORMAT_SHORT3_NORMALIZED = 23;
    public static final int FORMAT_SHORT4_NORMALIZED = 24;
    public static final int FORMAT_FLOAT2 = 29;
    public static final int FORMAT_FLOAT3 = 30;
    public static final int FORMAT_FLOAT4 = 31;
    public static final int FORMAT_INT = 32;
    public static final int FORMAT_INT2 = 33;
    public static final int FORMAT_INT3 = 34;
    public static final int FORMAT_INT4 = 35;

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
     * 
     * 注意：MC 26.2 中 UV2 (lightmap) 使用 RG16_SINT 格式，对应 Metal 的 Short2。
     * glsl-transformer 将其转换为 GLSL 的 ivec2，所以这里使用 FORMAT_SHORT2。
     */
    public static MetalVertexDescriptor defaultMcFormat() {
        Attribute[] attrs = {
                new Attribute(0, FORMAT_FLOAT3, 0, 0),           // position (vec3)
                new Attribute(1, FORMAT_UCHAR4_NORMALIZED, 12, 0), // color (vec4)
                new Attribute(2, FORMAT_FLOAT2, 16, 0),         // uv0 (texture, vec2)
                new Attribute(3, FORMAT_SHORT2, 24, 0),          // uv1 (lightmap, ivec2 in shader -> Short2 in metal)
                new Attribute(4, FORMAT_CHAR3_NORMALIZED, 28, 0), // normal (vec3)
                new Attribute(5, FORMAT_FLOAT4, 32, 0),          // mc_Entity (vec4)
        };
        return new MetalVertexDescriptor(attrs, 48);
    }

    /**
     * 创建用于地形渲染的顶点描述符。
     * 对应 GL 的 NEW_ENTITY 顶点格式。
     */
    public static MetalVertexDescriptor createTerrain() {
        return defaultMcFormat();
    }

    /**
     * 创建用于全屏四边形渲染的顶点描述符。
     * 简单的 2D 顶点，仅包含位置和纹理坐标。
     */
    public static MetalVertexDescriptor createFullscreenQuad() {
        Attribute[] attrs = {
                new Attribute(0, FORMAT_FLOAT3, 0, 0),    // position (x, y, z)
                new Attribute(1, FORMAT_FLOAT2, 12, 0),   // texcoord (u, v)
        };
        return new MetalVertexDescriptor(attrs, 20);
    }

    public Attribute[] attributes() {
        return attributes;
    }

    public int stride() {
        return stride;
    }

    /**
     * 创建Metal vertex descriptor原生对象。
     * 
     * 使用 metallum 的 native 函数创建和配置 vertex descriptor。
     */
    public MemorySegment handle() {
        // 创建 vertex descriptor
        MemorySegment vertexDesc = IrisMetalNativeBridge.createMTLVertexDescriptor();
        if (IrisMetalNativeBridge.isNullHandle(vertexDesc)) {
            return MemorySegment.NULL;
        }
        
        // 设置每个 attribute
        for (Attribute attr : attributes) {
            IrisMetalNativeBridge.setVertexDescriptorAttribute(
                vertexDesc, 
                attr.location, 
                attr.format, 
                attr.offset, 
                attr.bufferIndex
            );
        }
        
        // 设置 layout（stride 和 step function）
        // PerVertex 的 stepRate 必须是 0，不能是 1
        IrisMetalNativeBridge.setVertexDescriptorLayout(
            vertexDesc, 
            0,  // buffer index
            stride, 
            STEP_PER_VERTEX, 
            0   // step rate - PerVertex 必须是 0！
        );
        
        return vertexDesc;
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

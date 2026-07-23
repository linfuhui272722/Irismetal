package net.irisshaders.iris.metal.texture;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.TextureAccess;
import net.irisshaders.iris.metal.IrisMetalDevice;
import net.irisshaders.iris.metal.bridge.IrisMetalNativeBridge;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/**
 * Metal 纹理实现，对应 Iris 原有的 {@code GlTexture} / {@code TextureWrapper}。
 *
 * <p>本类封装一个 {@code MTLTexture}，负责：</p>
 * <ul>
 *   <li>根据 Iris 的 {@link InternalTextureFormat}（GL 内部格式）映射到 {@code MTLPixelFormat}</li>
 *   <li>纹理创建（2D / 3D / Cube）</li>
 *   <li>像素数据上传（{@code replaceRegion}）</li>
 *   <li>像素数据回读（{@code getBytes}，用于 Iris 的截图/调试）</li>
 *   <li>mipmap 生成（通过 blit encoder）</li>
 *   <li>资源生命周期管理</li>
 * </ul>
 *
 * <p><b>格式映射</b>：Iris 光影大量使用浮点格式（{@code RGBA16F}/{@code RGBA32F}）和
 * 深度格式（{@code DEPTH_COMPONENT}/{@code DEPTH_STENCIL}）。Metal 的像素格式命名与
 * GL 不同，本类通过 {@link MetalPixelFormat} 完成映射。注意 Metal 不支持
 * {@code DEPTH_COMPONENT24}，会映射到 {@code Depth32Float}；{@code DEPTH_STENCIL}
 * 映射到 {@code Depth32Float_Stencil8}。</p>
 *
 * <p><b>与 metallum 的关系</b>：metallum 的 {@code MetalGpuTexture} 实现了 MC 26.2 的
 * {@code GpuTexture} 接口，用于 vanilla/Sodium 渲染。Iris 的纹理是独立管理的（光影
 * colortex 等），不经过 MC 的 {@code GpuTexture} 抽象，因此需要本类独立实现。</p>
 */
@Environment(EnvType.CLIENT)
public final class MetalTexture implements TextureAccess, AutoCloseable {
    private final int textureId;
    private final MemorySegment handle;
    private final InternalTextureFormat internalFormat;
    private final int mtlPixelFormat;
    private final int width;
    private final int height;
    private final int depth;
    private final int mipLevels;
    private final Type type;
    private boolean destroyed;

    /**
     * 纹理类型，对应 GL 的 TEXTURE_2D / TEXTURE_3D / TEXTURE_CUBE_MAP。
     */
    public enum Type {
        TEXTURE_2D,
        TEXTURE_3D,
        TEXTURE_CUBE
    }

    public MetalTexture(int textureId, InternalTextureFormat internalFormat, int width, int height,
                        int depth, int mipLevels, Type type, String label) {
        this.textureId = textureId;
        this.internalFormat = internalFormat;
        this.mtlPixelFormat = MetalPixelFormat.from(internalFormat);
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.mipLevels = mipLevels;
        this.type = type;

        MemorySegment device = IrisMetalDevice.get().deviceHandle();
        // MTLTextureUsageShaderRead | MTLTextureUsageShaderWrite | MTLTextureUsageRenderTarget
        int usage = 0x1 | 0x2 | 0x4;
        // MTLStorageModePrivate - GPU 私有内存，性能最佳，数据上传通过 blit encoder
        int storageMode = 0; // Private

        switch (type) {
            case TEXTURE_2D:
                this.handle = IrisMetalNativeBridge.createTexture2D(device, mtlPixelFormat,
                        width, height, 1, mipLevels, 1, usage, storageMode, label);
                break;
            case TEXTURE_3D:
                this.handle = IrisMetalNativeBridge.createTexture3D(device, mtlPixelFormat,
                        width, height, depth, mipLevels, usage, storageMode, label);
                break;
            case TEXTURE_CUBE:
                this.handle = IrisMetalNativeBridge.createTextureCube(device, mtlPixelFormat,
                        Math.max(width, height), mipLevels, usage, storageMode, label);
                break;
            default:
                throw new IllegalArgumentException("Unknown texture type: " + type);
        }

        if (IrisMetalNativeBridge.isNullHandle(handle)) {
            throw new RuntimeException("Failed to create Metal texture: " + label);
        }
    }

    public int getTextureId() {
        return textureId;
    }

    public MemorySegment handle() {
        return handle;
    }

    public int mtlPixelFormat() {
        return mtlPixelFormat;
    }

    public InternalTextureFormat internalFormat() {
        return internalFormat;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getDepth() {
        return depth;
    }

    public int getMipLevels() {
        return mipLevels;
    }

    public Type getType() {
        return type;
    }

    /**
     * 上传像素数据到指定 mip 层。
     *
     * <p>由于纹理使用 {@code MTLStorageModePrivate}（GPU 私有内存），数据上传需要
     * 通过一个临时 staging buffer + blit encoder 完成。本方法内部处理这一流程。</p>
     *
     * @param data       像素数据（ByteBuffer，位置和限制决定有效数据范围）
     * @param mipLevel   目标 mip 层
     * @param slice      目标 slice（cube face 或 3D 层）
     * @param bytesPerRow 每行字节数
     * @param bytesPerImage 每个 image 字节数（3D 纹理用）
     */
    public void upload(ByteBuffer data, int mipLevel, int slice, int bytesPerRow, int bytesPerImage) {
        if (destroyed) {
            throw new IllegalStateException("Texture already destroyed");
        }
        // 通过 staging buffer 上传
        MetalTextureUploader.upload(this, data, mipLevel, slice, bytesPerRow, bytesPerImage);
    }

    /**
     * 读取纹理像素数据（用于截图/调试）。
     */
    public ByteBuffer download(int mipLevel, int slice, int bytesPerRow, int bytesPerImage, int totalBytes) {
        if (destroyed) {
            throw new IllegalStateException("Texture already destroyed");
        }
        return MetalTextureDownloader.download(this, mipLevel, slice, bytesPerRow, bytesPerImage, totalBytes);
    }

    /**
     * 生成 mipmap（通过 blit encoder 的 generateMipmaps）。
     */
    public void generateMipmaps() {
        if (destroyed) {
            throw new IllegalStateException("Texture already destroyed");
        }
        MemorySegment buffer = IrisMetalDevice.get().beginFrame();
        try {
            MemorySegment blitEncoder = IrisMetalNativeBridge.makeBlitCommandEncoder(buffer);
            IrisMetalNativeBridge.blitGenerateMipmaps(blitEncoder, handle);
            IrisMetalNativeBridge.endEncoding(blitEncoder);
        } finally {
            IrisMetalDevice.get().endFrame();
        }
    }

    @Override
    public int getGlId() {
        return textureId;
    }

    @Override
    public int getInternalFormat() {
        return internalFormat.getGlFormat();
    }

    @Override
    public void close() {
        if (!destroyed) {
            destroyed = true;
            IrisMetalNativeBridge.releaseObject(handle);
        }
    }

    public boolean isDestroyed() {
        return destroyed;
    }
}

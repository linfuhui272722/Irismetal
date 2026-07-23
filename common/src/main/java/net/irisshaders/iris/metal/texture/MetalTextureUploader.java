package net.irisshaders.iris.metal.texture;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.metal.IrisMetalDevice;
import net.irisshaders.iris.metal.bridge.IrisMetalNativeBridge;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/**
 * Metal 纹理像素上传辅助类。
 *
 * <p>对应 GL 的 {@code glTexSubImage2D} / {@code glTexSubImage3D}。</p>
 *
 * <p><b>实现方式</b>：Metal 中纹理上传通过 {@code MTLBlitCommandEncoder} 的
 * {@code copyFromBuffer:toTexture:} 实现。流程：</p>
 * <ol>
 *   <li>创建一个临时 {@code MTLBuffer}（Shared 模式），写入像素数据</li>
 *   <li>创建 blit encoder，调用 {@code copyFromBuffer:sourceBytesPerRow:sourceBytesPerImage:
 *       sourceOrigin:sourceSize:toTexture:destinationSlice:destinationLevel:destinationOrigin:}</li>
 *   <li>结束编码</li>
 * </ol>
 *
 * <p><b>性能优化</b>：实际实现应使用持久化的 staging buffer 环形缓冲区，避免每帧分配。
 * 本版本为简化实现，每次上传创建临时 buffer。</p>
 */
@Environment(EnvType.CLIENT)
final class MetalTextureUploader {
    private MetalTextureUploader() {
    }

    static void upload(MetalTexture texture, ByteBuffer data, int mipLevel, int slice,
                       int bytesPerRow, int bytesPerImage) {
        MemorySegment device = IrisMetalDevice.get().deviceHandle();
        MemorySegment buffer = IrisMetalDevice.get().beginFrame();
        try {
            // 创建临时 staging buffer
            int dataSize = data.remaining();
            MemorySegment stagingBuffer = IrisMetalNativeBridge.createBuffer(
                    device, data, dataSize, 0 /* Shared */);

            MemorySegment blitEncoder = IrisMetalNativeBridge.makeBlitCommandEncoder(buffer);

            IrisMetalNativeBridge.blitCopyBufferToTexture(
                    blitEncoder,
                    stagingBuffer,
                    0, // sourceOffset
                    bytesPerRow,
                    bytesPerImage,
                    texture.handle(),
                    slice,
                    mipLevel,
                    0, 0, 0, // destinationOrigin (x, y, z)
                    texture.width(), texture.height(), texture.depth() // sourceSize
            );

            IrisMetalNativeBridge.endEncoding(blitEncoder);
            IrisMetalNativeBridge.releaseObject(stagingBuffer);
        } finally {
            IrisMetalDevice.get().endFrame();
        }
    }
}

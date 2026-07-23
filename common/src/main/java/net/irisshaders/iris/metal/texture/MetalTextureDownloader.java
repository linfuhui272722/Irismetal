package net.irisshaders.iris.metal.texture;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.metal.IrisMetalDevice;
import net.irisshaders.iris.metal.bridge.IrisMetalNativeBridge;

import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/**
 * Metal 纹理像素下载辅助类。
 *
 * <p>对应 GL 的 {@code glGetTexImage} / {@code glReadPixels}。</p>
 *
 * <p><b>实现方式</b>：Metal 中纹理下载通过 {@code MTLBlitCommandEncoder} 的
 * {@code copyFromTexture:toBuffer:} 实现，然后同步等待命令完成，从 buffer 读取数据。</p>
 *
 * <p><b>用途</b>：Iris 的截图功能、调试视图、某些光影的反馈纹理读取需要此功能。
 * 注意 Metal 不支持直接从 GPU 纹理同步读取，必须经过 blit + waitUntilCompleted。</p>
 */
@Environment(EnvType.CLIENT)
final class MetalTextureDownloader {
    private MetalTextureDownloader() {
    }

    static ByteBuffer download(MetalTexture texture, int mipLevel, int slice,
                               int bytesPerRow, int bytesPerImage, int totalBytes) {
        MemorySegment device = IrisMetalDevice.get().deviceHandle();
        MemorySegment buffer = IrisMetalDevice.get().beginFrame();
        try {
            // 创建接收 buffer（Shared 模式，CPU 可读）
            MemorySegment readbackBuffer = IrisMetalNativeBridge.createEmptyBuffer(
                    device, totalBytes, 0 /* Shared */);

            MemorySegment blitEncoder = IrisMetalNativeBridge.makeBlitCommandEncoder(buffer);

            IrisMetalNativeBridge.blitCopyTextureToBuffer(
                    blitEncoder,
                    texture.handle(),
                    slice,
                    mipLevel,
                    0, 0, 0,
                    texture.width(), texture.height(), texture.depth(),
                    readbackBuffer,
                    0,
                    bytesPerRow,
                    bytesPerImage
            );

            IrisMetalNativeBridge.endEncoding(blitEncoder);

            // 提交并等待完成（同步读取）
            IrisMetalDevice.get().endFrame();

            // 从 buffer 读取数据
            MemorySegment contents = IrisMetalNativeBridge.getBufferContents(readbackBuffer);
            ByteBuffer result = contents.asByteBuffer().slice(0, totalBytes);

            IrisMetalNativeBridge.releaseObject(readbackBuffer);
            return result;
        } finally {
            // endFrame 已在 try 块内调用，这里不重复
        }
    }
}

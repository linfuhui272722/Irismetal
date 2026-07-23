package net.irisshaders.iris.metal.image;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.metal.bridge.IrisMetalNativeBridge;
import net.irisshaders.iris.metal.texture.MetalTexture;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;

/**
 * Metal image 绑定管理，对应 Iris 原有的 {@code ProgramImages}。
 *
 * <p><b>GL image vs Metal image 的差异</b>：</p>
 * <ul>
 *   <li>GL：通过 {@code glBindImageTexture} 绑定纹理为 image，shader 中通过
 *       {@code layout(binding=N) uniform image2D} 声明，支持 {@code imageStore} /
 *       {@code imageLoad}。</li>
 *   <li>Metal：image 操作通过 {@code texture2d<T, access::write>} 或
 *       {@code texture2d<T, access::read_write>} 声明，通过
 *       {@code setVertexTexture}/{@code setFragmentTexture} 绑定（与 sampler texture
 *       共用 slot 空间，但 access qualifier 不同）。{@code imageStore} 对应
 *       {@code texture.write()}，{@code imageLoad} 对应 {@code texture.read()}。</li>
 * </ul>
 *
 * <p><b>注意</b>：MakeUp 光影不使用 image（已确认无 imageStore/imageLoad），因此本类
 * 主要为其他复杂光影准备。Metal 的 image 需要纹理创建时带
 * {@code MTLTextureUsageShaderWrite} flag。</p>
 */
@Environment(EnvType.CLIENT)
public final class MetalProgramImages implements AutoCloseable {
    private final Map<String, Integer> imageBindings;
    private final MetalTexture[] boundImages;
    private boolean destroyed;

    private MetalProgramImages(Map<String, Integer> bindings, int maxSlots) {
        this.imageBindings = bindings;
        this.boundImages = new MetalTexture[maxSlots];
    }

    public static MetalProgramImages reflectFromPipeline(
            MemorySegment pipelineState, MemorySegment vertexFunction, MemorySegment fragmentFunction) {
        String[] names = IrisMetalNativeBridge.getPipelineImageNames(pipelineState);
        int[] slots = IrisMetalNativeBridge.getPipelineImageSlots(pipelineState);

        Map<String, Integer> map = new HashMap<>();
        int maxSlot = 0;
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                map.put(names[i], slots[i]);
                maxSlot = Math.max(maxSlot, slots[i]);
            }
        }
        return new MetalProgramImages(map, maxSlot + 1);
    }

    public void bindImage(String name, MetalTexture texture) {
        Integer slot = imageBindings.get(name);
        if (slot == null) return;
        boundImages[slot] = texture;
    }

    public void bindImage(int slot, MetalTexture texture) {
        if (slot >= 0 && slot < boundImages.length) {
            boundImages[slot] = texture;
        }
    }

    public void apply(MemorySegment encoder) {
        for (int i = 0; i < boundImages.length; i++) {
            if (boundImages[i] != null && !boundImages[i].isDestroyed()) {
                // image 使用与 texture 不同的 slot 范围（约定从 slot 16 开始）
                int slot = 16 + i;
                IrisMetalNativeBridge.setFragmentTexture(encoder, slot, boundImages[i].handle());
                IrisMetalNativeBridge.setVertexTexture(encoder, slot, boundImages[i].handle());
            }
        }
    }

    public boolean hasImage(String name) {
        return imageBindings.containsKey(name);
    }

    @Override
    public void close() {
        if (!destroyed) {
            destroyed = true;
            java.util.Arrays.fill(boundImages, null);
        }
    }
}

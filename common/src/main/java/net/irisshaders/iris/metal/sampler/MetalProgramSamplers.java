package net.irisshaders.iris.metal.sampler;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.metal.bridge.IrisMetalNativeBridge;
import net.irisshaders.iris.metal.texture.MetalTexture;
import org.jspecify.annotations.Nullable;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;

/**
 * Metal sampler 管理，对应 Iris 原有的 {@code ProgramSamplers}。
 *
 * <p><b>GL sampler vs Metal sampler 的差异</b>：</p>
 * <ul>
 *   <li>GL：sampler state（过滤模式、wrap 模式）是纹理对象的一部分，通过
 *       {@code glTexParameteri} 设置。shader 中通过 {@code uniform sampler2D} 声明，
 *       通过 {@code glUniform1i} 绑定到 texture unit。</li>
 *   <li>Metal：{@code MTLTexture} 和 {@code MTLSamplerState} 是分离的对象。
 *       shader 中通过 {@code texture2d<T, access::sample>} 和
 *       {@code sampler} 分别声明，通过 {@code setVertexTexture}/{@code setFragmentTexture}
 *       和 {@code setVertexSamplerState}/{@code setFragmentSamplerState} 分别绑定。</li>
 * </ul>
 *
 * <p>本类负责：</p>
 * <ul>
 *   <li>反射 pipeline 中所有 sampler binding（texture slot + sampler slot）</li>
 *   <li>运行时绑定 texture 和 sampler state 到对应 slot</li>
 *   <li>缓存常用 sampler state（避免重复创建）</li>
 * </ul>
 */
@Environment(EnvType.CLIENT)
public final class MetalProgramSamplers implements AutoCloseable {
    private final Map<String, Integer> samplerBindings;
    private final MetalTexture[] boundTextures;
    private boolean destroyed;

    private MetalProgramSamplers(Map<String, Integer> bindings, int maxSlots) {
        this.samplerBindings = bindings;
        this.boundTextures = new MetalTexture[maxSlots];
    }

    public static MetalProgramSamplers reflectFromPipeline(
            MemorySegment pipelineState, MemorySegment vertexFunction, MemorySegment fragmentFunction) {
        String[] names = IrisMetalNativeBridge.getPipelineSamplerNames(pipelineState);
        int[] slots = IrisMetalNativeBridge.getPipelineSamplerSlots(pipelineState);

        Map<String, Integer> map = new HashMap<>();
        int maxSlot = 0;
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                map.put(names[i], slots[i]);
                maxSlot = Math.max(maxSlot, slots[i]);
            }
        }
        return new MetalProgramSamplers(map, maxSlot + 1);
    }

    public void bindTexture(String name, MetalTexture texture) {
        Integer slot = samplerBindings.get(name);
        if (slot == null) return;
        boundTextures[slot] = texture;
    }

    public void bindTexture(int slot, MetalTexture texture) {
        if (slot >= 0 && slot < boundTextures.length) {
            boundTextures[slot] = texture;
        }
    }

    /**
     * 将所有绑定的 texture 和 sampler state 应用到 render encoder。
     *
     * @param encoder       render pass encoder 句柄
     * @param samplerStates 预缓存的 sampler state 数组（按 slot 索引）
     */
    public void apply(MemorySegment encoder, MemorySegment[] samplerStates) {
        for (int i = 0; i < boundTextures.length; i++) {
            if (boundTextures[i] != null && !boundTextures[i].isDestroyed()) {
                IrisMetalNativeBridge.setFragmentTexture(encoder, i, boundTextures[i].handle());
                IrisMetalNativeBridge.setVertexTexture(encoder, i, boundTextures[i].handle());
                if (samplerStates != null && i < samplerStates.length
                        && !IrisMetalNativeBridge.isNullHandle(samplerStates[i])) {
                    IrisMetalNativeBridge.setFragmentSamplerState(encoder, i, samplerStates[i]);
                    IrisMetalNativeBridge.setVertexSamplerState(encoder, i, samplerStates[i]);
                }
            }
        }
    }

    public boolean hasSampler(String name) {
        return samplerBindings.containsKey(name);
    }

    public int getSamplerSlot(String name) {
        Integer s = samplerBindings.get(name);
        return s != null ? s : -1;
    }

    @Override
    public void close() {
        if (!destroyed) {
            destroyed = true;
            java.util.Arrays.fill(boundTextures, null);
        }
    }
}

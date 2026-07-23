package net.irisshaders.iris.metal.blending;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.metal.IrisMetalDevice;
import net.irisshaders.iris.metal.bridge.IrisMetalNativeBridge;

import java.lang.foreign.MemorySegment;
import java.util.HashMap;
import java.util.Map;

/**
 * Metal sampler state 缓存。
 *
 * <p>对应 GL 的纹理过滤/wrap 参数设置。在 GL 中这些参数是纹理对象的一部分，
 * 在 Metal 中是独立的 {@code MTLSamplerState} 对象。由于 sampler state 创建开销
 * 较大，本类缓存所有使用过的 sampler state 组合。</p>
 *
 * <p><b>缓存键</b>：minFilter + magFilter + mipFilter + wrapS + wrapT + wrapR +
 * maxAnisotropy + compareFunction。相同参数返回同一个 {@code MTLSamplerState}。</p>
 */
@Environment(EnvType.CLIENT)
public final class MetalSamplerStateCache {
    private static final Map<Long, MemorySegment> cache = new HashMap<>();

    // MTLSamplerMinMagFilter
    public static final int FILTER_NEAREST = 0;
    public static final int FILTER_LINEAR = 1;

    // MTLSamplerMipFilter
    public static final int MIP_NONE = 0;
    public static final int MIP_NEAREST = 1;
    public static final int MIP_LINEAR = 2;

    // MTLSamplerAddressMode
    public static final int ADDRESS_CLAMP_TO_EDGE = 0;
    public static final int ADDRESS_REPEAT = 1;
    public static final int ADDRESS_MIRROR_REPEAT = 2;
    public static final int ADDRESS_CLAMP_TO_ZERO = 3;

    // MTLCompareFunction
    public static final int COMPARE_NEVER = 0;
    public static final int COMPARE_LESS = 1;
    public static final int COMPARE_LESS_EQUAL = 2;
    public static final int COMPARE_EQUAL = 3;
    public static final int COMPARE_GREATER_EQUAL = 4;
    public static final int COMPARE_GREATER = 5;
    public static final int COMPARE_NOT_EQUAL = 6;
    public static final int COMPARE_ALWAYS = 7;
    public static final int COMPARE_NONE = 8;

    private MetalSamplerStateCache() {
    }

    /**
     * 获取或创建 sampler state。
     *
     * @param minFilter      缩小过滤
     * @param magFilter      放大过滤
     * @param mipFilter      mipmap 过滤
     * @param wrapS          S 方向 wrap
     * @param wrapT          T 方向 wrap
     * @param wrapR          R 方向 wrap
     * @param maxAnisotropy  最大各向异性（0 表示关闭）
     * @param compareFunction 比较函数（用于 shadow sampler，NONE 表示不使用）
     * @return MTLSamplerState 句柄
     */
    public static synchronized MemorySegment get(int minFilter, int magFilter, int mipFilter,
                                                  int wrapS, int wrapT, int wrapR,
                                                  int maxAnisotropy, int compareFunction) {
        long key = makeKey(minFilter, magFilter, mipFilter, wrapS, wrapT, wrapR,
                maxAnisotropy, compareFunction);
        MemorySegment existing = cache.get(key);
        if (existing != null) {
            return existing;
        }
        MemorySegment device = IrisMetalDevice.get().deviceHandle();
        MemorySegment sampler = IrisMetalNativeBridge.createSamplerState(
                device, minFilter, magFilter, mipFilter,
                wrapS, wrapT, wrapR, maxAnisotropy, compareFunction);
        cache.put(key, sampler);
        return sampler;
    }

    /**
     * 释放所有缓存的 sampler state。仅在 Iris 卸载光影时调用。
     */
    public static synchronized void destroyAll() {
        for (MemorySegment sampler : cache.values()) {
            IrisMetalNativeBridge.releaseObject(sampler);
        }
        cache.clear();
    }

    private static long makeKey(int minFilter, int magFilter, int mipFilter,
                                int wrapS, int wrapT, int wrapR,
                                int maxAnisotropy, int compareFunction) {
        // 将 8 个参数打包到一个 long 中
        return ((long) (minFilter & 0xF) << 0)
                | ((long) (magFilter & 0xF) << 4)
                | ((long) (mipFilter & 0xF) << 8)
                | ((long) (wrapS & 0xF) << 12)
                | ((long) (wrapT & 0xF) << 16)
                | ((long) (wrapR & 0xF) << 20)
                | ((long) (maxAnisotropy & 0xFF) << 24)
                | ((long) (compareFunction & 0xF) << 32);
    }
}

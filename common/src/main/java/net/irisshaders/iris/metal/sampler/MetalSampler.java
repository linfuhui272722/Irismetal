package net.irisshaders.iris.metal.sampler;

import net.irisshaders.iris.metal.IrisMetalDevice;
import net.irisshaders.iris.metal.bridge.IrisMetalNativeBridge;

import java.lang.foreign.MemorySegment;

/**
 * Metal 纹理采样器。
 *
 * <p>对应 OpenGL 的 {@code glTexParameter} 采样配置。</p>
 */
public class MetalSampler implements AutoCloseable {
    private final MemorySegment handle;
    private final int minFilter;
    private final int magFilter;
    private final int mipFilter;
    private final int addressModeU;
    private final int addressModeV;
    private final int addressModeW;
    private final float maxAnisotropy;
    private final float lodMin;
    private final float lodMax;

    // 采样器状态缓存键
    private final int cacheKey;

    public MetalSampler(int minFilter, int magFilter, int mipFilter,
                        int addressModeU, int addressModeV, int addressModeW,
                        float maxAnisotropy, float lodMin, float lodMax) {
        this.minFilter = minFilter;
        this.magFilter = magFilter;
        this.mipFilter = mipFilter;
        this.addressModeU = addressModeU;
        this.addressModeV = addressModeV;
        this.addressModeW = addressModeW;
        this.maxAnisotropy = maxAnisotropy;
        this.lodMin = lodMin;
        this.lodMax = lodMax;

        // 计算缓存键
        this.cacheKey = calculateCacheKey();

        MemorySegment device = IrisMetalDevice.get().deviceHandle();
        this.handle = IrisMetalNativeBridge.createSamplerState(
                device,
                minFilter, magFilter, mipFilter,
                addressModeU, addressModeV, addressModeW,
                (int) maxAnisotropy,
                -1  // 无compare function
        );
    }

    private int calculateCacheKey() {
        int key = 0;
        key = key * 31 + minFilter;
        key = key * 31 + magFilter;
        key = key * 31 + mipFilter;
        key = key * 31 + addressModeU;
        key = key * 31 + addressModeV;
        key = key * 31 + addressModeW;
        key = key * 31 + Float.hashCode(maxAnisotropy);
        key = key * 31 + Float.hashCode(lodMin);
        key = key * 31 + Float.hashCode(lodMax);
        return key;
    }

    public MemorySegment handle() {
        return handle;
    }

    public int getCacheKey() {
        return cacheKey;
    }

    public int getMinFilter() {
        return minFilter;
    }

    public int getMagFilter() {
        return magFilter;
    }

    public int getMipFilter() {
        return mipFilter;
    }

    public int getAddressModeU() {
        return addressModeU;
    }

    public int getAddressModeV() {
        return addressModeV;
    }

    public int getAddressModeW() {
        return addressModeW;
    }

    public float getMaxAnisotropy() {
        return maxAnisotropy;
    }

    public float getLodMin() {
        return lodMin;
    }

    public float getLodMax() {
        return lodMax;
    }

    @Override
    public void close() {
        if (!IrisMetalNativeBridge.isNullHandle(handle)) {
            IrisMetalNativeBridge.releaseObject(handle);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MetalSampler that = (MetalSampler) o;
        return cacheKey == that.cacheKey;
    }

    @Override
    public int hashCode() {
        return cacheKey;
    }

    // ===== 静态采样器工厂 =====

    private static MetalSampler cachedSampler = null;
    private static final Object SAMPLER_LOCK = new Object();

    /**
     * 获取默认的线性采样器（用于2D纹理）。
     */
    public static MetalSampler getLinearSampler() {
        synchronized (SAMPLER_LOCK) {
            if (cachedSampler == null) {
                cachedSampler = new MetalSampler(
                        /* minFilter */ 0x1101, // LINEAR
                        /* magFilter */ 0x1101, // LINEAR
                        /* mipFilter */ 0x1101, // LINEAR
                        /* addressModeU */ 0x812F, // CLAMP_TO_EDGE
                        /* addressModeV */ 0x812F, // CLAMP_TO_EDGE
                        /* addressModeW */ 0x812F, // CLAMP_TO_EDGE
                        /* maxAnisotropy */ 1.0f,
                        /* lodMin */ 0.0f,
                        /* lodMax */ Float.MAX_VALUE
                );
            }
            return cachedSampler;
        }
    }
}

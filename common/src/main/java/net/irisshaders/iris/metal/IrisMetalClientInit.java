package net.irisshaders.iris.metal;

import net.fabricmc.api.ClientModInitializer;
import net.irisshaders.iris.metal.bridge.IrisMetalNativeBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Iris Metal 后端客户端初始化。
 *
 * <p>在客户端启动时检测 Metal 设备是否可用，并预加载原生库。
 * 如果 Metal 后端不可用，将优雅地回退到 OpenGL。</p>
 */
public class IrisMetalClientInit implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("IrisMetalClient");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[Iris-Metal] Initializing Metal backend...");

        try {
            // 首先检查系统信息
            String osName = System.getProperty("os.name", "");
            String osArch = System.getProperty("os.arch", "");
            LOGGER.info("[Iris-Metal] OS: {} {}, Architecture: {}", osName, System.getProperty("os.version", ""), osArch);

            // 尝试加载原生库
            // 注意：metallum 和 Iris 使用相同的原生库 (libmetallum.dylib)
            // 即使 metallum mod 已加载，原生库也可以被 Iris 使用
            try {
                IrisMetalNativeBridge.ensureLoaded();
            } catch (Throwable e) {
                LOGGER.warn("[Iris-Metal] Failed to load native library: {}", e.getMessage());
                LOGGER.info("[Iris-Metal] Falling back to OpenGL backend");
                return;
            }

            if (!IrisMetalNativeBridge.isAvailable()) {
                LOGGER.info("[Iris-Metal] Native library reports unavailable, using OpenGL fallback");
                return;
            }

            LOGGER.info("[Iris-Metal] Native library loaded successfully");

            // 延迟初始化设备，避免在启动时崩溃
            // 设备将在首次使用时才创建
            try {
                // 使用tryInitialize进行安全初始化
                if (IrisMetalDevice.tryInitialize()) {
                    LOGGER.info("[Iris-Metal] Metal device acquired: {}", IrisMetalDevice.get().getDeviceName());

                    // 检查 metallum 是否已加载
                    if (IrisMetalDevice.isMetallumLoaded()) {
                        LOGGER.info("[Iris-Metal] Metallum mod detected, Iris will integrate with its rendering pipeline");
                    }
                } else {
                    LOGGER.info("[Iris-Metal] Could not acquire Metal device, using OpenGL fallback");
                }
            } catch (Exception e) {
                LOGGER.warn("[Iris-Metal] Metal device initialization failed: {}", e.getMessage());
                LOGGER.info("[Iris-Metal] Falling back to OpenGL backend");
            }

        } catch (Throwable t) {
            LOGGER.warn("[Iris-Metal] Iris Metal Backend initialization failed, falling back to OpenGL", t);
        }

        LOGGER.info("[Iris-Metal] Initialization complete");
    }
}

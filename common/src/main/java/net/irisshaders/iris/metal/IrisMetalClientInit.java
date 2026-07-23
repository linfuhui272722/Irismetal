package net.irisshaders.iris.metal;

import net.fabricmc.api.ClientModInitializer;
import net.irisshaders.iris.metal.bridge.IrisMetalNativeBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Iris Metal 后端客户端初始化。
 *
 * <p>在客户端启动时检测 Metal 设备是否可用，并预加载原生库。</p>
 */
public class IrisMetalClientInit implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("IrisMetalClient");

    @Override
    public void onInitializeClient() {
        try {
            boolean nativeLoaded = IrisMetalNativeBridge.ensureLoaded();
            if (nativeLoaded) {
                LOGGER.info("Iris Metal native library loaded successfully");
                // 预初始化设备（获取系统默认 Metal 设备）
                try {
                    IrisMetalDevice device = IrisMetalDevice.get();
                    LOGGER.info("Metal device acquired: {}", device.getDeviceName());
                } catch (Exception e) {
                    LOGGER.warn("Failed to acquire Metal device, Iris will use OpenGL fallback", e);
                }
            } else {
                LOGGER.info("Iris Metal native library not available (non-macOS or missing dylib), using OpenGL fallback");
            }
        } catch (Throwable t) {
            LOGGER.warn("Iris Metal Backend initialization failed, falling back to OpenGL", t);
        }
    }
}

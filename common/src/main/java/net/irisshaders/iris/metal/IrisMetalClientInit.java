package net.irisshaders.iris.metal;

import net.fabricmc.api.ClientModInitializer;
import net.irisshaders.iris.Iris;
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
            // 首先检查系统是否支持Metal
            String osName = System.getProperty("os.name", "");
            String osArch = System.getProperty("os.arch", "");
            LOGGER.info("[Iris-Metal] OS: {} {}, Architecture: {}", osName, System.getProperty("os.version", ""), osArch);
            
            // 在非Apple平台或者模拟器环境，先检查metallum是否已加载
            // 如果metallum已加载，它可能已经接管了渲染
            // iOS也使用metallum，所以我们需要检查metallum是否已加载
            if (!osName.contains("Mac") && !osName.contains("Darwin")) {
                LOGGER.info("[Iris-Metal] Not running on macOS, checking for metallum...");
                if (isMetallumLoaded()) {
                    LOGGER.info("[Iris-Metal] Metallum is loaded, delegating Metal rendering to metallum");
                    return;
                }
            } else if (osName.contains("Darwin") && !osName.contains("Mac")) {
                // iOS 或其他 Darwin 系统
                LOGGER.info("[Iris-Metal] Running on Darwin-based system (possibly iOS), checking for metallum...");
                if (isMetallumLoaded()) {
                    LOGGER.info("[Iris-Metal] Metallum is loaded, delegating Metal rendering to metallum");
                    return;
                }
            }
            
            // 尝试加载原生库
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
    
    /**
     * 检查metallum mod是否已加载。
     */
    private boolean isMetallumLoaded() {
        try {
            Class<?> metallumClass = Class.forName("com.metallum.Metallum");
            if (metallumClass != null) {
                LOGGER.info("[Iris-Metal] Found metallum class: {}", metallumClass.getName());
                return true;
            }
        } catch (ClassNotFoundException e) {
            // metallum未加载，这是正常的
        } catch (Throwable e) {
            LOGGER.debug("[Iris-Metal] Error checking metallum: {}", e.getMessage());
        }
        return false;
    }
}

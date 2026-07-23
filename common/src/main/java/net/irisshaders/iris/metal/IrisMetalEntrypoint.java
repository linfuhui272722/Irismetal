package net.irisshaders.iris.metal;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Iris Metal 后端入口点。
 *
 * <p>负责在 mod 加载阶段检测当前环境是否为 metallum Metal 环境，
 * 并在 Metal 设备可用时初始化 Iris Metal 后端。</p>
 */
public class IrisMetalEntrypoint implements ModInitializer {
    public static final String MOD_ID = "iris_metal";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Iris Metal Backend initializing...");

        // 检测是否在 metallum 环境下运行
        boolean metallumPresent = isMetallumPresent();
        LOGGER.info("Metallum detected: {}", metallumPresent);

        if (metallumPresent) {
            // 延迟到客户端初始化阶段检测 Metal 设备
            // 实际的 Metal 设备初始化在 RenderSystem 首次调用时进行
            LOGGER.info("Iris Metal Backend will activate when Metal device is detected");
        } else {
            LOGGER.info("Metallum not present, Iris Metal Backend will remain inactive (OpenGL path used)");
        }
    }

    private static boolean isMetallumPresent() {
        try {
            Class.forName("com.metallum.Metallum");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}

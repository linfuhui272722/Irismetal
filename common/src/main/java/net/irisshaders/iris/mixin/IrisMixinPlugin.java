package net.irisshaders.iris.mixin;

import com.google.common.base.Splitter;
import com.google.common.io.Files;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class IrisMixinPlugin implements IMixinConfigPlugin {
    private static final Splitter OPTION_SPLITTER = Splitter.on(':').limit(2);

    public static boolean usingVulkan;
    /**
     * 当 metallum 模组将图形后端切换为 Metal 时为 true。
     * metallum 通过 mixin 让 PreferredGraphicsApi.DEFAULT 指向 Metal 后端，
     * 此时 options.txt 中 preferredGraphicsBackend 的值为 "default"（或包含 "metal"）。
     * 在 Metal 模式下，Iris 的 GL 相关 mixin 不应应用（会因无 GL 上下文而崩溃），
     * 改由 MetalOnly_ 前缀的 mixin 接管。
     */
    public static boolean usingMetal;

    static {
        BufferedReader reader = null;
        boolean check = true;
        try {
            reader = Files.newReader(IrisPlatformHelpers.getInstance().getGameDir().resolve("options.txt").toFile(), StandardCharsets.UTF_8);
        } catch (FileNotFoundException e) {
            usingVulkan = false;
            usingMetal = false;
            check = false;
        }

        if (check) {
            Map<String, String> options = new HashMap<>();

            try {
                reader.lines().forEach(line -> {
                    try {
                        Iterator<String> iterator = OPTION_SPLITTER.split(line).iterator();
                        options.put((String) iterator.next(), (String) iterator.next());
                    } catch (Exception var3) {
                    }
                });
            } catch (Throwable var6) {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Throwable var5) {
                        var6.addSuppressed(var5);
                    }
                }

                throw var6;
            }

            String preferredBackend = options.get("preferredGraphicsBackend");
            if (preferredBackend != null) {
                String lower = preferredBackend.toLowerCase(Locale.ROOT);
                usingVulkan = lower.contains("vulkan");
                // metallum 将 DEFAULT 映射为 Metal；也兼容显式 "metal" 值
                usingMetal = lower.contains("metal") || lower.equals("default");
            } else {
                usingVulkan = false;
                usingMetal = false;
            }
        }
    }
        @Override
        public void onLoad(String mixinPackage) {

        }

        @Override
        public String getRefMapperConfig() {
                return "iris.refmap.json";
        }

        @Override
        public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
                if (mixinClassName.contains("VKOnly")) return usingVulkan;
                if (mixinClassName.contains("MetalOnly")) return usingMetal;
                // 在 Vulkan 或 Metal 模式下，GL 相关 mixin 都不应用
                return !usingVulkan && !usingMetal;
        }

        @Override
        public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {

        }

        @Override
        public List<String> getMixins() {
                return List.of();
        }

        @Override
        public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
                //if (targetClassName.contains("LevelRenderer")) {
                //      targetClass.methods.forEach(m -> System.out.println(m.name + m.desc));
                //}
        }

        @Override
        public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {

        }
}

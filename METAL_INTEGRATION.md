# Iris Metal Backend 集成说明

本文档记录了将 Metal 后端补丁集成进 Iris 26.2 主项目的所有修改。

## 目标环境
- Minecraft Java 26.2 + Fabric
- Metallum 0.0.21+（将图形 API 替换为 Apple Metal）
- macOS + Apple Silicon (M1+)
- Java 25

## 集成方式

采用与 Iris 已有 Vulkan 后端相同的模式：通过 `IrisMixinPlugin` 的 `usingMetal` 标志，
在 Metal 模式下跳过 GL 相关 mixin，改由 `MetalOnly_` 前缀的 mixin 接管。

## 修改文件清单

### 1. 新增文件

#### Metal 后端源码（`common/src/main/java/net/irisshaders/iris/metal/`）
- `IrisMetalDevice.java` — Metal 设备与命令队列管理（单例，与 metallum 共享系统默认设备）
- `IrisMetalRenderSystem.java` — GL 状态机模拟层（替代 IrisRenderSystem 的 GL 调用）
- `IrisMetalPipelineManager.java` — pipeline 调度中枢
- `IrisMetalEntrypoint.java` — mod 初始化入口（内部工具类）
- `IrisMetalClientInit.java` — 客户端初始化（预加载 native 库）
- `bridge/IrisMetalNativeBridge.java` — JNI/FFM 原生桥接（仿 metallum 的 MetalNativeBridge）
- `shader/MetalShaderCompiler.java` — GLSL→SPIRV→MSL 编译器（复用 metallum 的 SPIRV-Cross 路径）
- `program/MetalCompiledProgram.java` — 渲染管线状态对象（替代 GL program）
- `program/MetalVertexDescriptor.java` — 顶点布局描述
- `texture/MetalTexture.java` — 纹理实现
- `texture/MetalPixelFormat.java` — GL↔Metal 像素格式映射表
- `texture/MetalTextureUploader.java` — 像素上传（blit encoder）
- `texture/MetalTextureDownloader.java` — 像素下载
- `framebuffer/MetalFramebuffer.java` — 帧缓冲区
- `framebuffer/MetalRenderPassEncoder.java` — 渲染命令编码器
- `blending/MetalBlendState.java` — blend 状态（GL blend factor→Metal 映射）
- `blending/MetalSamplerStateCache.java` — sampler state 缓存
- `sampler/MetalProgramSamplers.java` — sampler 绑定管理
- `image/MetalProgramImages.java` — image 绑定管理
- `buffer/MetalBuffer.java` — GPU buffer（VBO/IBO/SSBO/UBO）
- `uniform/MetalUniformBlock.java` — uniform 管理

#### Metal 专属 Mixin（`common/src/main/java/net/irisshaders/iris/mixin/`）
- `MetalOnly_RenderSystemMixin.java` — 在 Metal 后端初始化时触发 Iris Metal 设备初始化
- `MetalOnly_PreferredGraphicsApiMixin.java` — 在 metallum 注入 Metal 后端时初始化 Iris Metal 设备

#### 原生代码（`common/src/main/native/`）
- `IrisMetalNative.swift` — Swift 原生代码，调用 Metal/MetalKit/QuartzCore 框架
  （编译为 `libiris_metal.dylib`，仅在 macOS 上编译）

### 2. 修改的文件

#### `common/src/main/java/net/irisshaders/iris/mixin/IrisMixinPlugin.java`
- 新增 `usingMetal` 静态字段
- 在 static 块中检测 `options.txt` 的 `preferredGraphicsBackend`：
  - 值包含 "metal" 或等于 "default"（metallum 将 DEFAULT 映射为 Metal）时 `usingMetal = true`
- 修改 `shouldApplyMixin()`：
  - `MetalOnly_` 前缀的 mixin 仅在 `usingMetal == true` 时应用
  - 在 Vulkan 或 Metal 模式下，GL 相关 mixin 都不应用

#### `common/src/main/java/net/irisshaders/iris/gl/IrisRenderSystem.java`
- 在 `initRenderer()` 开头增加 Metal 模式检测：
  - 若 `usingMetal == true`，初始化 `IrisMetalDevice` 并直接 return
  - 跳过 `GL.getCapabilities()` 调用（Metal 环境下无 GL 上下文，调用会崩溃）

#### `common/src/main/resources/mixins.iris.json`
- 在 client 列表中注册两个 Metal mixin：
  - `MetalOnly_RenderSystemMixin`
  - `MetalOnly_PreferredGraphicsApiMixin`

#### `common/src/main/resources/fabric.mod.json`
- 新增 `recommends.metallum: ">=0.0.21"`
  （可选依赖，非 metallum 环境下 Iris 仍正常用 GL）

#### `common/build.gradle.kts`
- 新增 metallum compileOnly 依赖（`maven.modrinth:metallum:0.0.21`）
- 新增 `buildMetalNative` Exec 任务：
  - 仅在 macOS 上执行
  - 用 swiftc 编译 `IrisMetalNative.swift` 为 `libiris_metal.dylib`
  - 输出到 `src/main/resources/natives/macos/`
  - processResources 依赖此任务

## 工作原理

### Metal 模式检测
```
options.txt: preferredGraphicsBackend=default (或 metal)
       ↓
IrisMixinPlugin.static { usingMetal = true }
       ↓
shouldApplyMixin():
  - MetalOnly_* mixin → 应用
  - 其他 GL mixin → 跳过
       ↓
IrisRenderSystem.initRenderer():
  - usingMetal == true → 初始化 IrisMetalDevice，return
  - 否则 → 正常 GL 初始化
```

### 与 metallum 的协作
- metallum 通过自己的 mixin 让 `PreferredGraphicsApi.DEFAULT` 指向 Metal 后端
- metallum 实现 MC 26.2 的 `GpuBackend`/`MetalBackend`/`MetalDevice`，接管 vanilla/Sodium 渲染
- Iris 的 `IrisMetalDevice` 调用 `MTLCreateSystemDefaultDevice()` 获取同一个系统默认设备（进程内单例）
- Iris 拥有独立的命令队列，避免与 metallum 的命令编码互相干扰

### Shader 编译流程
```
光影 GLSL (#version 120, gl_FragData, texture2D 等)
       ↓
Iris transformer 层 (CompatibilityTransformer 等)
       ↓
现代 GLSL (#version 330+)
       ↓
MetalShaderCompiler: GLSL → SPIR-V (GlslCompiler) → MSL (SPIRV-Cross)
       ↓
MTLDevice.newLibraryWithSource → MTLFunction
       ↓
MTLRenderPipelineState
```

## 构建方式

### 在 macOS 上构建（完整功能）
```bash
export JAVA_HOME=/path/to/jdk-25
./gradlew :fabric:build
```
`buildMetalNative` 任务会自动编译 Swift 原生库。

### 在非 macOS 上构建（GL 模式，无 Metal）
```bash
export JAVA_HOME=/path/to/jdk-25
./gradlew :fabric:build
```
`buildMetalNative` 任务自动跳过。Metal 代码仍会编译（Java 部分），但运行时
`IrisMetalNativeBridge.ensureLoaded()` 会返回 false，Iris 走 GL 路径。

## 重要说明

1. **未经运行时验证**：本集成代码无法在当前环境（x86_64 Linux，无 Metal）编译验证。
   Metal 相关代码（Java + Swift）需要在 macOS + Apple Silicon 上测试调试。

2. **Metal 后端是架构骨架**：`metal/` 包提供了完整的 Metal 后端架构，但
   native bridge 的 Swift 函数实现和 SPIRV-Cross 转换的边界情况需要补充调试。

3. **GL 模式不受影响**：所有修改都通过 `usingMetal` 标志保护，非 metallum
   环境下 Iris 行为与原版完全一致。

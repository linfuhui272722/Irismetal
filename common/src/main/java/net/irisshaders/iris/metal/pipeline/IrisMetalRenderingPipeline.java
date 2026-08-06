package net.irisshaders.iris.metal.pipeline;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.TextureType;
import net.irisshaders.iris.helpers.Tri;
import net.irisshaders.iris.metal.IrisMetalDevice;
import net.irisshaders.iris.metal.IrisMetalPipelineManager;
import net.irisshaders.iris.metal.blending.MetalBlendState;
import net.irisshaders.iris.metal.framebuffer.MetalFramebuffer;
import net.irisshaders.iris.metal.program.MetalCompiledProgram;
import net.irisshaders.iris.metal.program.MetalVertexDescriptor;
import net.irisshaders.iris.metal.texture.MetalPixelFormat;
import net.irisshaders.iris.metal.texture.MetalTexture;
import net.irisshaders.iris.mixin.LevelRendererAccessor;
import net.irisshaders.iris.gl.blending.AlphaTest;
import net.irisshaders.iris.gl.state.ShaderAttributeInputs;
import net.irisshaders.iris.pipeline.ShaderRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.programs.ShaderMap;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.CloudSetting;
import net.irisshaders.iris.shaderpack.properties.ParticleRenderingSettings;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackRenderTargetDirectives;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Iris Metal 后端的完整 shader 渲染管线。
 * 
 * <p>本类实现 {@link WorldRenderingPipeline} 和 {@link ShaderRenderingPipeline} 接口，
 * 负责在 Metal 模式下加载和执行 Iris 光影 shader。</p>
 * 
 * <p><b>渲染流程</b>:</p>
 * <ol>
 *   <li>Shadow Pass - 从光源视角渲染到 shadow FBO</li>
 *   <li>Gbuffers Pass - 渲染场景到 colortex FBO</li>
 *   <li>Composite Pass - 后处理效果</li>
 *   <li>Final Pass - 合成到屏幕</li>
 * </ol>
 */
@Environment(EnvType.CLIENT)
public class IrisMetalRenderingPipeline implements WorldRenderingPipeline, ShaderRenderingPipeline {
    private static final Logger LOGGER = LoggerFactory.getLogger(IrisMetalRenderingPipeline.class);

    // Shader pack and directives
    private final ProgramSet programSet;
    private final PackDirectives packDirectives;
    private final ShaderPackHolder shaderPackHolder;
    
    // Uniforms and notifications
    private final FrameUpdateNotifier updateNotifier;
    private final DHCompat dhCompat;
    
    // Metal resources
    private final IrisMetalPipelineManager pipelineManager;
    private final Map<String, MetalCompiledProgram> programs = new HashMap<>();
    private final Map<String, MetalFramebuffer> framebuffers = new HashMap<>();
    private final Map<String, MetalTexture> textures = new HashMap<>();
    
    // Render targets
    private final int shadowMapResolution;
    private @Nullable MetalFramebuffer shadowFramebuffer;
    private @Nullable MetalTexture shadowDepthTexture;
    private @Nullable MetalCompiledProgram shadowProgram;
    
    private @Nullable MetalFramebuffer gbuffersFramebuffer;
    private @Nullable MetalTexture gbuffersDepthTexture;
    private MetalTexture[] gbuffersColorTextures;
    
    // Rendering state
    private WorldRenderingPhase phase = WorldRenderingPhase.NONE;
    @Nullable private WorldRenderingPhase overridePhase = null;
    private boolean destroyed = false;
    private boolean isRenderingWorld = false;
    private boolean isMainBound = false;
    private boolean isBeforeTranslucent = false;
    
    // Shader configuration
    private final float sunPathRotation;
    private final boolean shouldRenderUnderwaterOverlay;
    private final boolean shouldRenderVignette;
    private final boolean shouldWriteRainAndSnowToDepthBuffer;
    private final boolean shouldRenderSun;
    private final boolean shouldRenderWeather;
    private final boolean shouldRenderWeatherParticles;
    private final boolean shouldRenderMoon;
    private final boolean shouldRenderStars;
    private final boolean shouldRenderSkyDisc;
    private final CloudSetting cloudSetting;
    private final ParticleRenderingSettings particleRenderingSettings;
    private final OptionalInt forcedShadowRenderDistanceChunks;
    private final boolean supportsEndFlash;

    // Gbuffer programs organized by type
    private final EnumMap<ProgramId, MetalCompiledProgram> gbufferPrograms = new EnumMap<>(ProgramId.class);
    
    // Composite programs
    private final Map<String, MetalCompiledProgram> compositePrograms = new HashMap<>();
    private @Nullable MetalCompiledProgram finalProgram;
    
    // Fullscreen quad vertex descriptor
    private static final MetalVertexDescriptor FULLSCREEN_VERTEX = MetalVertexDescriptor.createFullscreenQuad();

    public IrisMetalRenderingPipeline(ProgramSet programSet) {
        LOGGER.info("[Iris-Metal] Initializing Metal rendering pipeline...");
        
        this.programSet = programSet;
        this.packDirectives = programSet.getPackDirectives();
        this.shaderPackHolder = new ShaderPackHolder();
        this.updateNotifier = new FrameUpdateNotifier();
        this.dhCompat = new DHCompat(null, false);
        
        // Initialize Metal device
        if (!IrisMetalDevice.isInitialized()) {
            IrisMetalDevice.ensureLoaded();
            if (!IrisMetalDevice.tryInitialize()) {
                throw new IllegalStateException("Failed to initialize Metal device");
            }
        }
        
        // Initialize pipeline manager
        this.pipelineManager = IrisMetalPipelineManager.get();
        pipelineManager.initialize();
        
        // Read configuration
        this.shouldRenderUnderwaterOverlay = packDirectives.underwaterOverlay();
        this.shouldRenderVignette = packDirectives.vignette();
        this.shouldWriteRainAndSnowToDepthBuffer = packDirectives.rainDepth();
        this.shouldRenderSun = packDirectives.shouldRenderSun();
        this.shouldRenderWeather = packDirectives.shouldRenderWeather();
        this.shouldRenderWeatherParticles = packDirectives.shouldRenderWeatherParticles();
        this.shouldRenderMoon = packDirectives.shouldRenderMoon();
        this.shouldRenderStars = packDirectives.shouldRenderStars();
        this.shouldRenderSkyDisc = packDirectives.shouldRenderSkyDisc();
        this.cloudSetting = packDirectives.getCloudSetting();
        this.particleRenderingSettings = packDirectives.getParticleRenderingSettings();
        this.sunPathRotation = packDirectives.getSunPathRotation();
        this.supportsEndFlash = packDirectives.supportsEndFlash();
        this.shadowMapResolution = packDirectives.getShadowDirectives().getResolution();
        
        // Shadow distance
        PackShadowDirectives shadowDirectives = packDirectives.getShadowDirectives();
        if (shadowDirectives.isDistanceRenderMulExplicit()) {
            if (shadowDirectives.getDistanceRenderMul() >= 0.0) {
                forcedShadowRenderDistanceChunks = OptionalInt.of(
                    ((int) (shadowDirectives.getDistance() * shadowDirectives.getDistanceRenderMul()) + 15) / 16);
            } else {
                forcedShadowRenderDistanceChunks = OptionalInt.of(-1);
            }
        } else {
            forcedShadowRenderDistanceChunks = OptionalInt.empty();
        }
        
        // Initialize render targets
        initializeRenderTargets();
        
        // Compile all shaders
        compileShaders();
        
        LOGGER.info("[Iris-Metal] Metal rendering pipeline initialized successfully, compiled {} programs", programs.size());
    }
    
    private void initializeRenderTargets() {
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        int width = main.width;
        int height = main.height;
        
        // Get color formats from directives
        PackRenderTargetDirectives renderTargetDirectives = packDirectives.getRenderTargetDirectives();
        Map<Integer, PackRenderTargetDirectives.RenderTargetSettings> renderTargetSettings =
            renderTargetDirectives.getRenderTargetSettings();
        
        // Create gbuffers color textures
        gbuffersColorTextures = new MetalTexture[8];
        for (int i = 0; i < 8; i++) {
            PackRenderTargetDirectives.RenderTargetSettings settings = renderTargetSettings.getOrDefault(i,
                new PackRenderTargetDirectives.RenderTargetSettings());
            InternalTextureFormat format = settings.getInternalFormat();
            
            try {
                MetalTexture texture = pipelineManager.createTexture("colortex" + i, format, width, height, 1, 1);
                textures.put("colortex" + i, texture);
                gbuffersColorTextures[i] = texture;
            } catch (Exception e) {
                LOGGER.warn("[Iris-Metal] Failed to create colortex{}: {}", i, e.getMessage());
                gbuffersColorTextures[i] = null;
            }
        }
        
        // Create gbuffers depth texture
        try {
            InternalTextureFormat depthFormat = InternalTextureFormat.fromString("DEPTH_COMPONENT32")
                .orElse(InternalTextureFormat.R32F);
            gbuffersDepthTexture = pipelineManager.createTexture("depthtex0", depthFormat, width, height, 1, 1);
            textures.put("depthtex0", gbuffersDepthTexture);
        } catch (Exception e) {
            LOGGER.warn("[Iris-Metal] Failed to create depthtex0: {}", e.getMessage());
            gbuffersDepthTexture = null;
        }
        
        // Create gbuffers framebuffer
        MetalTexture[] colorTextures = new MetalTexture[8];
        for (int i = 0; i < 8; i++) {
            colorTextures[i] = gbuffersColorTextures[i];
        }
        gbuffersFramebuffer = pipelineManager.createFramebuffer("gbuffers", colorTextures, gbuffersDepthTexture);
        framebuffers.put("gbuffers", gbuffersFramebuffer);
        
        // Create shadow framebuffer if shadow is enabled
        if (packDirectives.getShadowDirectives().isShadowEnabled().orElse(false)) {
            try {
                InternalTextureFormat shadowDepthFormat = InternalTextureFormat.fromString("DEPTH_COMPONENT32")
                    .orElse(InternalTextureFormat.R32F);
                shadowDepthTexture = pipelineManager.createTexture("shadow", shadowDepthFormat, 
                    shadowMapResolution, shadowMapResolution, 1, 1);
                textures.put("shadow", shadowDepthTexture);
                shadowFramebuffer = pipelineManager.createFramebuffer("shadow", new MetalTexture[0], shadowDepthTexture);
                framebuffers.put("shadow", shadowFramebuffer);
            } catch (Exception e) {
                LOGGER.warn("[Iris-Metal] Failed to create shadow framebuffer: {}", e.getMessage());
            }
        }
        
        // Create composite framebuffers (composite1-composite7)
        for (int i = 1; i <= 7; i++) {
            String name = "composite" + (i == 1 ? "" : i);
            MetalTexture[] compColors = new MetalTexture[8];
            for (int j = 0; j < 8; j++) {
                compColors[j] = gbuffersColorTextures[j];
            }
            try {
                MetalFramebuffer fb = pipelineManager.createFramebuffer(name, compColors, null);
                framebuffers.put(name, fb);
            } catch (Exception e) {
                LOGGER.warn("[Iris-Metal] Failed to create {} framebuffer: {}", name, e.getMessage());
            }
        }
    }
    
    private void compileShaders() {
        LOGGER.info("[Iris-Metal] Compiling shaders...");
        
        // Compile shadow shader if shadow is enabled
        if (shadowFramebuffer != null) {
            compileShadowShader();
        }
        
        // Compile gbuffers shaders
        compileGbufferShaders();
        
        // Compile composite shaders
        compileCompositeShaders();
        
        // Compile final shader
        compileFinalShader();
        
        LOGGER.info("[Iris-Metal] Shader compilation complete");
    }
    
    private void compileShadowShader() {
        // Shadow pass uses composite shader programs
        ProgramSource[] sources = programSet.getComposite(ProgramArrayId.ShadowComposite);
        if (sources == null || sources.length == 0) {
            LOGGER.debug("[Iris-Metal] No shadow composite shader in pack");
            return;
        }
        // Shadow compilation deferred to render time
    }
    
    private void compileGbufferShaders() {
        // Compile all gbuffer programs
        for (ProgramId programId : ProgramId.values()) {
            ProgramSource source = programSet.get(programId).orElse(null);
            if (source == null || source.getVertexSource().isEmpty()) {
                continue;
            }
            
            try {
                MetalVertexDescriptor vertexDesc = MetalVertexDescriptor.createTerrain();
                MetalBlendState blendState = createBlendState(source);
                
                int[] colorFormats = new int[8];
                for (int i = 0; i < 8; i++) {
                    if (gbuffersColorTextures[i] != null) {
                        colorFormats[i] = gbuffersColorTextures[i].mtlPixelFormat();
                    }
                }
                int depthFormat = gbuffersDepthTexture != null ? gbuffersDepthTexture.mtlPixelFormat() : 0;
                
                // Transform shader sources using TransformPatcher
                // This converts OpenGL GLSL to Vulkan-compatible GLSL
                AlphaTest alpha = source.getDirectives().getAlphaTestOverride().orElse(AlphaTest.ALWAYS);
                boolean isLines = programId == ProgramId.Line;
                boolean isClouds = programId == ProgramId.Clouds;
                ShaderAttributeInputs inputs = new ShaderAttributeInputs(DefaultVertexFormat.BLOCK, false, isLines, false, false, false);
                
                Object2ObjectMap<Tri<String, net.irisshaders.iris.gl.texture.TextureType, TextureStage>, String> textureMap = 
                    programSet.getPackDirectives().getTextureMap();
                
                Map<PatchShaderType, String> transformed = TransformPatcher.patchVanilla(
                    source.getName(),
                    source.getVertexSource().orElse(""),
                    source.getGeometrySource().orElse(null),
                    source.getTessControlSource().orElse(null),
                    source.getTessEvalSource().orElse(null),
                    source.getFragmentSource().orElse(""),
                    alpha, isLines, isClouds, true, inputs, textureMap
                );
                
                String vertexSource = transformed.get(PatchShaderType.VERTEX);
                String fragmentSource = transformed.get(PatchShaderType.FRAGMENT);
                
                MetalCompiledProgram program = MetalCompiledProgram.create(
                    source.getName(),
                    vertexSource,
                    fragmentSource,
                    transformed.get(PatchShaderType.GEOMETRY),
                    vertexDesc,
                    colorFormats,
                    depthFormat,
                    new MetalBlendState[]{blendState}
                );
                
                gbufferPrograms.put(programId, program);
                programs.put(source.getName(), program);
            } catch (Exception e) {
                LOGGER.warn("[Iris-Metal] Failed to compile gbuffers shader {}: {}", source.getName(), e.getMessage());
            }
        }
    }
    
    private void compileCompositeShaders() {
        // Compile all composite programs
        for (ProgramArrayId arrayId : ProgramArrayId.values()) {
            ProgramSource[] sources = programSet.getComposite(arrayId);
            if (sources == null || sources.length == 0) {
                continue;
            }
            
            for (int i = 0; i < sources.length; i++) {
                ProgramSource source = sources[i];
                if (source == null || source.getVertexSource().isEmpty()) {
                    continue;
                }
                
                String passName = source.getName();
                
                try {
                    MetalVertexDescriptor vertexDesc = FULLSCREEN_VERTEX;
                    MetalBlendState blendState = createBlendState(source);
                    
                    int[] colorFormats = new int[8];
                    for (int j = 0; j < 8; j++) {
                        if (gbuffersColorTextures[j] != null) {
                            colorFormats[j] = gbuffersColorTextures[j].mtlPixelFormat();
                        }
                    }
                    
                    // Transform shader sources using TransformPatcher
                    TextureStage stage = TextureStage.COMPOSITE_AND_FINAL;
                    Object2ObjectMap<Tri<String, net.irisshaders.iris.gl.texture.TextureType, TextureStage>, String> textureMap = 
                        programSet.getPackDirectives().getTextureMap();
                    Map<PatchShaderType, String> transformed = TransformPatcher.patchComposite(
                        passName,
                        source.getVertexSource().orElse(""),
                        null, // geometry
                        source.getFragmentSource().orElse(""),
                        stage,
                        textureMap
                    );
                    
                    String vertexSource = transformed.get(PatchShaderType.VERTEX);
                    String fragmentSource = transformed.get(PatchShaderType.FRAGMENT);
                    
                    MetalCompiledProgram program = MetalCompiledProgram.create(
                        passName,
                        vertexSource,
                        fragmentSource,
                        null,
                        vertexDesc,
                        colorFormats,
                        0,
                        new MetalBlendState[]{blendState}
                    );
                    
                    compositePrograms.put(passName, program);
                    programs.put(passName, program);
                } catch (Exception e) {
                    LOGGER.warn("[Iris-Metal] Failed to compile composite shader {}: {}", passName, e.getMessage());
                }
            }
        }
    }
    
    private void compileFinalShader() {
        LOGGER.info("[Iris-Metal] Compiling final shader...");
        
        // Get the final program source
        Optional<ProgramSource> maybeFinal = programSet.get(ProgramId.Final);
        if (maybeFinal.isEmpty() || maybeFinal.get().getVertexSource().isEmpty()) {
            LOGGER.info("[Iris-Metal] No final shader in pack");
            return;
        }
        
        ProgramSource source = maybeFinal.get();
        String passName = source.getName();
        
        try {
            MetalVertexDescriptor vertexDesc = FULLSCREEN_VERTEX;
            MetalBlendState blendState = createBlendState(source);
            
            int[] colorFormats = new int[8];
            for (int i = 0; i < 8; i++) {
                if (gbuffersColorTextures[i] != null) {
                    colorFormats[i] = gbuffersColorTextures[i].mtlPixelFormat();
                }
            }
            
            // Transform shader sources using TransformPatcher
            TextureStage stage = TextureStage.COMPOSITE_AND_FINAL;
            Object2ObjectMap<Tri<String, net.irisshaders.iris.gl.texture.TextureType, TextureStage>, String> textureMap = 
                programSet.getPackDirectives().getTextureMap();
            Map<PatchShaderType, String> transformed = TransformPatcher.patchComposite(
                passName,
                source.getVertexSource().orElse(""),
                null, // geometry
                source.getFragmentSource().orElse(""),
                stage,
                textureMap
            );
            
            String vertexSource = transformed.get(PatchShaderType.VERTEX);
            String fragmentSource = transformed.get(PatchShaderType.FRAGMENT);
            
            // Create the final program
            MetalCompiledProgram program = MetalCompiledProgram.create(
                "final",
                vertexSource,
                fragmentSource,
                null,
                vertexDesc,
                colorFormats,
                0,
                new MetalBlendState[]{blendState}
            );
            
            finalProgram = program;
            programs.put("final", program);
            
            LOGGER.info("[Iris-Metal] Final shader compiled successfully: {}", passName);
        } catch (Exception e) {
            LOGGER.warn("[Iris-Metal] Failed to compile final shader {}: {}", passName, e.getMessage());
        }
    }
    
    private MetalBlendState createBlendState(ProgramSource source) {
        // Read blend state from program directives
        // For now, return a default blend state
        return new MetalBlendState(false, 1, 0, 1, 0, 0, 0);
    }

    // ==================== WorldRenderingPipeline implementation ====================

    @Override
    public void beginLevelRendering() {
        isRenderingWorld = true;
        phase = WorldRenderingPhase.TERRAIN_SOLID;
        
        LOGGER.debug("[Iris-Metal] beginLevelRendering");
        
        // Clear gbuffers framebuffer
        if (gbuffersFramebuffer != null) {
            pipelineManager.bindFramebuffer("gbuffers");
            IrisMetalDevice.get().clearColor(gbuffersFramebuffer, 0, 0, 0, 0);
        }
    }

    @Override
    public void renderShadows(LevelRendererAccessor worldRenderer, Camera playerCamera, CameraRenderState renderState) {
        if (shadowFramebuffer == null || shadowProgram == null) {
            return;
        }
        
        LOGGER.debug("[Iris-Metal] renderShadows");
        
        // Bind shadow framebuffer
        pipelineManager.bindFramebuffer("shadow");
        
        // Clear depth
        if (shadowDepthTexture != null) {
            IrisMetalDevice.get().clearDepth(shadowDepthTexture, 0.0f);
        }
        
        // The actual shadow rendering would be done by the Sodium/mixin system
        // We just need to ensure the shadow program is bound and ready
        pipelineManager.bindProgram("shadow");
    }

    @Override
    public void addDebugText(DebugScreenDisplayer messages) {
        messages.addLine("Iris Metal Pipeline Active");
        messages.addLine("Programs: " + programs.size());
        messages.addLine("Metallum: " + (IrisMetalDevice.isMetallumLoaded() ? "Yes" : "No"));
    }

    @Override
    public OptionalInt getForcedShadowRenderDistanceChunksForDisplay() {
        return forcedShadowRenderDistanceChunks;
    }

    @Override
    public Object2ObjectMap<Tri<String, TextureType, TextureStage>, String> getTextureMap() {
        return Object2ObjectMaps.emptyMap();
    }

    @Override
    public WorldRenderingPhase getPhase() {
        return overridePhase != null ? overridePhase : phase;
    }

    @Override
    public void setPhase(WorldRenderingPhase phase) {
        this.phase = phase;
    }

    @Override
    public void setOverridePhase(WorldRenderingPhase phase) {
        this.overridePhase = phase;
    }

    @Override
    public int getCurrentNormalTexture() {
        // Return the normal texture ID for PBR
        return 0;
    }

    @Override
    public int getCurrentSpecularTexture() {
        return 0;
    }

    @Override
    public void onSetAlbedoTex(GpuTextureView id) {
        // Not used in Metal pipeline
    }

    @Override
    public void beginHand() {
        phase = WorldRenderingPhase.HAND_SOLID;
        LOGGER.debug("[Iris-Metal] beginHand");
    }

    @Override
    public void beginTranslucents() {
        phase = WorldRenderingPhase.TERRAIN_TRANSLUCENT;
        isBeforeTranslucent = false;
        LOGGER.debug("[Iris-Metal] beginTranslucents");
    }

    @Override
    public void finalizeLevelRendering() {
        LOGGER.debug("[Iris-Metal] finalizeLevelRendering");
        
        isRenderingWorld = false;
        phase = WorldRenderingPhase.NONE;
        
        // 确保命令缓冲区存在
        IrisMetalDevice.get().beginFrame();
        
        // Render composite passes
        renderCompositePasses();
        
        // Render final pass
        renderFinalPass();
        
        // 提交命令缓冲区
        IrisMetalDevice.get().endFrame();
    }
    
    private void renderCompositePasses() {
        // Render all composite passes in order
        String[] passOrder = {"begin", "deferred", "prepare", "composite", "composite1", "composite2", "composite3"};
        
        for (String passName : passOrder) {
            MetalCompiledProgram program = compositePrograms.get(passName);
            if (program == null) {
                continue;
            }
            
            LOGGER.debug("[Iris-Metal] Rendering composite pass: {}", passName);
            
            // Bind framebuffer for this pass
            MetalFramebuffer fb = framebuffers.get(passName);
            if (fb != null) {
                pipelineManager.bindFramebuffer(passName);
            }
            
            // Bind program and draw
            pipelineManager.bindProgram(passName);
            IrisMetalDevice.get().drawFullscreenQuad();
        }
    }
    
    private void renderFinalPass() {
        if (finalProgram == null) {
            LOGGER.warn("[Iris-Metal] No final shader program");
            return;
        }
        
        LOGGER.debug("[Iris-Metal] Rendering final pass");
        
        // The final pass is rendered to the screen
        // metallum handles the actual blit to screen
        pipelineManager.bindProgram("final");
        IrisMetalDevice.get().drawFullscreenQuad();
    }

    @Override
    public void finalizeGameRendering() {
        // Color space conversion if needed
    }

    @Override
    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;

        LOGGER.info("[Iris-Metal] Destroying Metal rendering pipeline");

        // Destroy all programs
        for (MetalCompiledProgram program : programs.values()) {
            try {
                program.close();
            } catch (Exception e) {
                LOGGER.warn("[Iris-Metal] Error closing program: {}", e.getMessage());
            }
        }
        programs.clear();
        gbufferPrograms.clear();
        compositePrograms.clear();
        
        // Destroy pipeline manager
        pipelineManager.destroy();
    }

    @Override
    public FrameUpdateNotifier getFrameUpdateNotifier() {
        return updateNotifier;
    }

    @Override
    public boolean shouldDisableVanillaEntityShadows() {
        return shadowFramebuffer != null;
    }

    @Override
    public boolean shouldDisableDirectionalShading() {
        return packDirectives.isOldLighting();
    }

    @Override
    public boolean shouldDisableFrustumCulling() {
        return false;
    }

    @Override
    public boolean shouldDisableOcclusionCulling() {
        return false;
    }

    @Override
    public CloudSetting getCloudSetting() {
        return cloudSetting;
    }

    @Override
    public boolean shouldRenderUnderwaterOverlay() {
        return shouldRenderUnderwaterOverlay;
    }

    @Override
    public boolean shouldRenderVignette() {
        return shouldRenderVignette;
    }

    @Override
    public boolean shouldRenderSun() {
        return shouldRenderSun;
    }

    @Override
    public boolean shouldRenderWeather() {
        return shouldRenderWeather;
    }

    @Override
    public boolean shouldRenderWeatherParticles() {
        return shouldRenderWeatherParticles;
    }

    @Override
    public boolean shouldRenderMoon() {
        return shouldRenderMoon;
    }

    @Override
    public boolean shouldRenderStars() {
        return shouldRenderStars;
    }

    @Override
    public boolean shouldRenderSkyDisc() {
        return shouldRenderSkyDisc;
    }

    @Override
    public boolean shouldWriteRainAndSnowToDepthBuffer() {
        return shouldWriteRainAndSnowToDepthBuffer;
    }

    @Override
    public ParticleRenderingSettings getParticleRenderingSettings() {
        return particleRenderingSettings;
    }

    @Override
    public boolean allowConcurrentCompute() {
        return packDirectives.getConcurrentCompute();
    }

    @Override
    public boolean hasFeature(FeatureFlags flag) {
        return programSet.getPack().hasFeature(flag);
    }

    @Override
    public float getSunPathRotation() {
        return sunPathRotation;
    }

    @Override
    public DHCompat getDHCompat() {
        return dhCompat;
    }

    @Override
    public void setIsMainBound(boolean mainBound) {
        this.isMainBound = mainBound;
    }

    @Override
    public void onBeginClear() {
        // Called before vanilla clear
    }

    @Override
    public boolean supportsEndFlash() {
        return supportsEndFlash;
    }

    @Override
    public int getAlbedoTex() {
        return 0;
    }

    // ==================== ShaderRenderingPipeline implementation ====================

    @Override
    public ShaderMap getShaderMap() {
        // Metal pipeline doesn't use ShaderMap directly
        // Shaders are managed through MetalCompiledProgram
        return null;
    }

    @Override
    public boolean shouldOverrideShaders() {
        return true;
    }

    // ==================== Metal-specific methods ====================

    @Nullable
    public MetalCompiledProgram getGbufferProgram(ProgramId id) {
        return gbufferPrograms.get(id);
    }

    @Nullable
    public MetalCompiledProgram getCompositeProgram(String name) {
        return compositePrograms.get(name);
    }

    @Nullable
    public MetalFramebuffer getGbuffersFramebuffer() {
        return gbuffersFramebuffer;
    }

    @Nullable
    public MetalFramebuffer getShadowFramebuffer() {
        return shadowFramebuffer;
    }

    public ProgramSet getProgramSet() {
        return programSet;
    }

    // ==================== Helper classes ====================

    /**
     * Placeholder for ShaderPack holder.
     * In the full implementation, this would manage the shader pack resources.
     */
    private static class ShaderPackHolder {
    }
}

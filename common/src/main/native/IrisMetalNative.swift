// IrisMetalNative.swift
//
// Iris Metal 后端原生桥接层
//
// 本文件实现 IrisMetalNativeBridge.java 中声明的所有 native 方法。
// 它通过 Foundation / Metal / MetalKit / QuartzCore 框架直接调用 Metal API。
//
// 编译方式（macOS 上）：
// swiftc -O -target arm64-apple-macos14.0 \
//   -framework Foundation -framework Metal -framework MetalKit \
//   -framework QuartzCore -framework objc \
//   -emit-library IrisMetalNative.swift \
//   -o libiris_metal.dylib
//
// 编译后的 dylib 需要放入 Iris mod jar 的 src/main/resources/natives/macos/ 目录。
//
// 与 metallum 的关系：
// metallum 的 MetallumNative.swift 实现了 vanilla/Sodium 的 Metal 后端。
// 本文件实现 Iris 专属的 Metal 调用，但共享同一个系统默认 Metal 设备
// (MTLCreateSystemDefaultDevice() 返回单例)。
//
// 重要：本文件只能在 macOS + Apple Silicon 上编译运行。
// 在非 macOS 环境下无法编译，Iris 会回退到 OpenGL 或禁用光影。

import Foundation
import Metal
import MetalKit
import QuartzCore

// MARK: - 全局状态

/// Iris Metal 设备单例句柄（与 metallum 共享系统默认设备）
private var irisDevice: MTLDevice?

/// Iris 专属命令队列
private var irisCommandQueue: MTLCommandQueue?

/// 当前命令缓冲区
private var currentCommandBuffer: MTLCommandBuffer?

/// 帧嵌套计数
private var frameDepth: Int = 0

// MARK: - 设备与命令队列

@_cdecl("iris_metal_get_device")
public func irisMetalGetDevice() -> OpaquePointer? {
    if irisDevice == nil {
        irisDevice = MTLCreateSystemDefaultDevice()
    }
    guard let device = irisDevice else {
        return nil
    }
    return OpaquePointer(Unmanaged.passUnretained(device).toOpaque())
}

@_cdecl("iris_metal_get_device_name")
public func irisMetalGetDeviceName(_ devicePtr: OpaquePointer?) -> UnsafeMutablePointer<CChar>? {
    guard let ptr = devicePtr else { return nil }
    let device = Unmanaged<MTLDevice>.fromOpaque(UnsafeRawPointer(ptr)).takeUnretainedValue()
    let name = device.name
    return strdup(name)
}

@_cdecl("iris_metal_create_command_queue")
public func irisMetalCreateCommandQueue(_ devicePtr: OpaquePointer?) -> OpaquePointer? {
    guard let ptr = devicePtr else { return nil }
    let device = Unmanaged<MTLDevice>.fromOpaque(UnsafeRawPointer(ptr)).takeUnretainedValue()
    let queue = device.makeCommandQueue()
    guard let q = queue else { return nil }
    return OpaquePointer(Unmanaged.passUnretained(q).toOpaque())
}

@_cdecl("iris_metal_command_buffer_begin")
public func irisMetalCommandBufferBegin(_ queuePtr: OpaquePointer?) -> OpaquePointer? {
    guard let ptr = queuePtr else { return nil }
    let queue = Unmanaged<MTLCommandQueue>.fromOpaque(UnsafeRawPointer(ptr)).takeUnretainedValue()
    let buffer = queue.makeCommandBuffer()
    guard let buf = buffer else { return nil }
    currentCommandBuffer = buf
    return OpaquePointer(Unmanaged.passUnretained(buf).toOpaque())
}

@_cdecl("iris_metal_command_buffer_commit")
public func irisMetalCommandBufferCommit(_ bufferPtr: OpaquePointer?) {
    guard let ptr = bufferPtr else { return }
    let buffer = Unmanaged<MTLCommandBuffer>.fromOpaque(UnsafeRawPointer(ptr)).takeUnretainedValue()
    buffer.commit()
}

@_cdecl("iris_metal_command_buffer_wait_until_completed")
public func irisMetalCommandBufferWaitUntilCompleted(_ bufferPtr: OpaquePointer?) {
    guard let ptr = bufferPtr else { return }
    let buffer = Unmanaged<MTLCommandBuffer>.fromOpaque(UnsafeRawPointer(ptr)).takeUnretainedValue()
    buffer.waitUntilCompleted()
}

// MARK: - 对象生命周期

@_cdecl("iris_metal_release_object")
public func irisMetalReleaseObject(_ ptr: OpaquePointer?) {
    guard let ptr = ptr else { return }
    // Metal 对象通过 ARC 管理，这里 release Unmanaged 引用
    // 注意：passUnretained 创建的对象不应 release，只有 passRetained 的才需要
    // 为安全起见，这里不做操作（ARC 会自动管理）
    // 实际实现中需要根据对象创建方式决定是否 release
}

@_cdecl("iris_metal_is_null_handle")
public func irisMetalIsNullHandle(_ ptr: OpaquePointer?) -> Bool {
    return ptr == nil
}

// MARK: - 纹理创建

@_cdecl("iris_metal_create_texture_2d")
public func irisMetalCreateTexture2D(
    _ devicePtr: OpaquePointer?,
    _ pixelFormat: Int32,
    _ width: Int32,
    _ height: Int32,
    _ depthOrLayers: Int32,
    _ mipLevels: Int32,
    _ isCubemap: Int32,
    _ usage: Int32,
    _ storageMode: Int32,
    _ label: UnsafePointer<CChar>?
) -> OpaquePointer? {
    guard let ptr = devicePtr else { return nil }
    let device = Unmanaged<MTLDevice>.fromOpaque(UnsafeRawPointer(ptr)).takeUnretainedValue()

    let desc = MTLTextureDescriptor()
    desc.pixelFormat = mtlPixelFormatFromInt(pixelFormat)
    desc.width = Int(width)
    desc.height = Int(height)

    if isCubemap != 0 {
        desc.textureType = .cube
        desc.arrayLength = 1
    } else if depthOrLayers > 1 {
        desc.textureType = .type2DArray
        desc.arrayLength = Int(depthOrLayers)
    } else {
        desc.textureType = .type2D
    }

    desc.mipmapLevelCount = Int(mipLevels)
    desc.usage = MTLTextureUsage(rawValue: UInt(usage))
    desc.storageMode = MTLStorageMode(rawValue: UInt(storageMode)) ?? .shared

    if let labelStr = label {
        desc.label = String(cString: labelStr)
    }

    guard let texture = device.makeTexture(descriptor: desc) else { return nil }
    return OpaquePointer(Unmanaged.passUnretained(texture).toOpaque())
}

@_cdecl("iris_metal_create_texture_3d")
public func irisMetalCreateTexture3D(
    _ devicePtr: OpaquePointer?,
    _ pixelFormat: Int32,
    _ width: Int32,
    _ height: Int32,
    _ depth: Int32,
    _ mipLevels: Int32,
    _ usage: Int32,
    _ label: UnsafePointer<CChar>?
) -> OpaquePointer? {
    guard let ptr = devicePtr else { return nil }
    let device = Unmanaged<MTLDevice>.fromOpaque(UnsafeRawPointer(ptr)).takeUnretainedValue()

    let desc = MTLTextureDescriptor()
    desc.pixelFormat = mtlPixelFormatFromInt(pixelFormat)
    desc.width = Int(width)
    desc.height = Int(height)
    desc.depth = Int(depth)
    desc.textureType = .type3D
    desc.mipmapLevelCount = Int(mipLevels)
    desc.usage = MTLTextureUsage(rawValue: UInt(usage))
    desc.storageMode = .shared

    if let labelStr = label {
        desc.label = String(cString: labelStr)
    }

    guard let texture = device.makeTexture(descriptor: desc) else { return nil }
    return OpaquePointer(Unmanaged.passUnretained(texture).toOpaque())
}

// MARK: - Buffer 创建

@_cdecl("iris_metal_create_buffer")
public func irisMetalCreateBuffer(
    _ devicePtr: OpaquePointer?,
    _ data: UnsafeRawPointer?,
    _ size: Int,
    _ options: Int32
) -> OpaquePointer? {
    guard let ptr = devicePtr else { return nil }
    let device = Unmanaged<MTLDevice>.fromOpaque(UnsafeRawPointer(ptr)).takeUnretainedValue()

    let mtlOptions = MTLResourceOptions(rawValue: UInt(options))
    let buffer: MTLBuffer

    if let dataPtr = data {
        buffer = device.makeBuffer(bytes: dataPtr, length: size, options: mtlOptions)!
    } else {
        buffer = device.makeBuffer(length: size, options: mtlOptions)!
    }

    return OpaquePointer(Unmanaged.passUnretained(buffer).toOpaque())
}

@_cdecl("iris_metal_get_buffer_contents")
public func irisMetalGetBufferContents(_ bufferPtr: OpaquePointer?) -> UnsafeMutableRawPointer? {
    guard let ptr = bufferPtr else { return nil }
    let buffer = Unmanaged<MTLBuffer>.fromOpaque(UnsafeRawPointer(ptr)).takeUnretainedValue()
    return buffer.contents()
}

@_cdecl("iris_metal_upload_buffer_data")
public func irisMetalUploadBufferData(
    _ bufferPtr: OpaquePointer?,
    _ data: UnsafeRawPointer?,
    _ size: Int
) {
    guard let ptr = bufferPtr, let dataPtr = data else { return }
    let buffer = Unmanaged<MTLBuffer>.fromOpaque(UnsafeRawPointer(ptr)).takeUnretainedValue()
    memcpy(buffer.contents(), dataPtr, size)
}

// MARK: - Render Pass 与 Encoder

@_cdecl("iris_metal_make_render_command_encoder")
public func irisMetalMakeRenderCommandEncoder(
    _ bufferPtr: OpaquePointer?,
    _ passDescriptorPtr: OpaquePointer?
) -> OpaquePointer? {
    guard let bPtr = bufferPtr, let pdPtr = passDescriptorPtr else { return nil }
    let buffer = Unmanaged<MTLCommandBuffer>.fromOpaque(UnsafeRawPointer(bPtr)).takeUnretainedValue()
    let passDesc = Unmanaged<MTLRenderPassDescriptor>.fromOpaque(UnsafeRawPointer(pdPtr)).takeUnretainedValue()

    guard let encoder = buffer.makeRenderCommandEncoder(descriptor: passDesc) else { return nil }
    return OpaquePointer(Unmanaged.passUnretained(encoder).toOpaque())
}

@_cdecl("iris_metal_make_blit_command_encoder")
public func irisMetalMakeBlitCommandEncoder(_ bufferPtr: OpaquePointer?) -> OpaquePointer? {
    guard let ptr = bufferPtr else { return nil }
    let buffer = Unmanaged<MTLCommandBuffer>.fromOpaque(UnsafeRawPointer(ptr)).takeUnretainedValue()

    guard let encoder = buffer.makeBlitCommandEncoder() else { return nil }
    return OpaquePointer(Unmanaged.passUnretained(encoder).toOpaque())
}

@_cdecl("iris_metal_end_encoding")
public func irisMetalEndEncoding(_ encoderPtr: OpaquePointer?) {
    guard let ptr = encoderPtr else { return }
    let encoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(UnsafeRawPointer(ptr)).takeUnretainedValue()
    encoder.endEncoding()
}

// MARK: - Render Pass Descriptor 创建

@_cdecl("iris_metal_create_render_pass_descriptor")
public func irisMetalCreateRenderPassDescriptor() -> OpaquePointer? {
    let desc = MTLRenderPassDescriptor()
    return OpaquePointer(Unmanaged.passUnretained(desc).toOpaque())
}

@_cdecl("iris_metal_set_render_pass_color_attachment")
public func irisMetalSetRenderPassColorAttachment(
    _ descPtr: OpaquePointer?,
    _ index: Int32,
    _ texturePtr: OpaquePointer?,
    _ clearR: Float,
    _ clearG: Float,
    _ clearB: Float,
    _ clearA: Float,
    _ shouldClear: Int32
) {
    guard let ptr = descPtr else { return }
    let desc = Unmanaged<MTLRenderPassDescriptor>.fromOpaque(UnsafeRawPointer(ptr)).takeUnretainedValue()
    let attachment = desc.colorAttachments[Int(index)]

    if let texPtr = texturePtr {
        let texture = Unmanaged<MTLTexture>.fromOpaque(UnsafeRawPointer(texPtr)).takeUnretainedValue()
        attachment.texture = texture
    }

    if shouldClear != 0 {
        attachment.loadAction = .clear
        attachment.storeAction = .store
        attachment.clearColor = MTLClearColor(red: Double(clearR), green: Double(clearG), blue: Double(clearB), alpha: Double(clearA))
    } else {
        attachment.loadAction = .load
        attachment.storeAction = .store
    }
}

@_cdecl("iris_metal_set_render_pass_depth_attachment")
public func irisMetalSetRenderPassDepthAttachment(
    _ descPtr: OpaquePointer?,
    _ texturePtr: OpaquePointer?,
    _ shouldClear: Int32,
    _ clearDepth: Float,
    _ shouldStore: Int32
) {
    guard let ptr = descPtr else { return }
    let desc = Unmanaged<MTLRenderPassDescriptor>.fromOpaque(UnsafeRawPointer(ptr)).takeUnretainedValue()
    let attachment = desc.depthAttachment

    if let texPtr = texturePtr {
        let texture = Unmanaged<MTLTexture>.fromOpaque(UnsafeRawPointer(texPtr)).takeUnretainedValue()
        attachment.texture = texture
    }

    if shouldClear != 0 {
        attachment.loadAction = .clear
        attachment.clearDepth = Double(clearDepth)
    } else {
        attachment.loadAction = .load
    }

    attachment.storeAction = shouldStore != 0 ? .store : .dontCare
}

@_cdecl("iris_metal_set_render_pass_stencil_attachment")
public func irisMetalSetRenderPassStencilAttachment(
    _ descPtr: OpaquePointer?,
    _ texturePtr: OpaquePointer?,
    _ shouldClear: Int32,
    _ clearStencil: Int32,
    _ shouldStore: Int32
) {
    guard let ptr = descPtr else { return }
    let desc = Unmanaged<MTLRenderPassDescriptor>.fromOpaque(UnsafeRawPointer(ptr)).takeUnretainedValue()
    let attachment = desc.stencilAttachment

    if let texPtr = texturePtr {
        let texture = Unmanaged<MTLTexture>.fromOpaque(UnsafeRawPointer(texPtr)).takeUnretainedValue()
        attachment.texture = texture
    }

    if shouldClear != 0 {
        attachment.loadAction = .clear
        attachment.clearStencil = clearStencil
    } else {
        attachment.loadAction = .load
    }

    attachment.storeAction = shouldStore != 0 ? .store : .dontCare
}

// MARK: - Encoder 状态设置

@_cdecl("iris_metal_set_viewport")
public func irisMetalSetViewport(
    _ encoderPtr: OpaquePointer?,
    _ x: Int32,
    _ y: Int32,
    _ width: Int32,
    _ height: Int32
) {
    guard let ptr = encoderPtr else { return }
    let encoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(UnsafeRawPointer(ptr)).takeUnretainedValue()
    let viewport = MTLViewport(originX: Double(x), originY: Double(y), width: Double(width), height: Double(height), znear: 0.0, zfar: 1.0)
    encoder.setViewport(viewport)
}

@_cdecl("iris_metal_set_scissor_rect")
public func irisMetalSetScissorRect(
    _ encoderPtr: OpaquePointer?,
    _ x: Int32,
    _ y: Int32,
    _ width: Int32,
    _ height: Int32
) {
    guard let ptr = encoderPtr else { return }
    let encoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(UnsafeRawPointer(ptr)).takeUnretainedValue()
    let rect = MTLScissorRect(x: Int(x), y: Int(y), width: Int(width), height: Int(height))
    encoder.setScissorRect(rect)
}

@_cdecl("iris_metal_set_scissor_enabled")
public func irisMetalSetScissorEnabled(_ encoderPtr: OpaquePointer?, _ enabled: Int32) {
    // Metal 没有独立的 scissor enable/disable，通过设置全屏 rect 来"禁用"
    guard let ptr = encoderPtr else { return }
    let encoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(UnsafeRawPointer(ptr)).takeUnretainedValue()

    if enabled != 0 {
        // 保持当前 scissor（调用方应先 setScissorRect）
    } else {
        // 设置一个超大 rect 来模拟禁用
        encoder.setScissorRect(MTLScissorRect(x: 0, y: 0, width: 32767, height: 32767))
    }
}

@_cdecl("iris_metal_set_blend_color")
public func irisMetalSetBlendColor(
    _ encoderPtr: OpaquePointer?,
    _ r: Float,
    _ g: Float,
    _ b: Float,
    _ a: Float
) {
    guard let ptr = encoderPtr else { return }
    let encoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(UnsafeRawPointer(ptr)).takeUnretainedValue()
    // 修复：使用新的 API 名称
    encoder.setBlendColor(red: r, green: g, blue: b, alpha: a)
}

@_cdecl("iris_metal_set_render_pipeline_state")
public func irisMetalSetRenderPipelineState(
    _ encoderPtr: OpaquePointer?,
    _ pipelinePtr: OpaquePointer?
) {
    guard let ePtr = encoderPtr, let pPtr = pipelinePtr else { return }
    let encoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(UnsafeRawPointer(ePtr)).takeUnretainedValue()
    let pipeline = Unmanaged<MTLRenderPipelineState>.fromOpaque(UnsafeRawPointer(pPtr)).takeUnretainedValue()
    encoder.setRenderPipelineState(pipeline)
}

@_cdecl("iris_metal_set_vertex_buffer")
public func irisMetalSetVertexBuffer(
    _ encoderPtr: OpaquePointer?,
    _ slot: Int32,
    _ bufferPtr: OpaquePointer?,
    _ offset: Int
) {
    guard let ePtr = encoderPtr, let bPtr = bufferPtr else { return }
    let encoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(UnsafeRawPointer(ePtr)).takeUnretainedValue()
    let buffer = Unmanaged<MTLBuffer>.fromOpaque(UnsafeRawPointer(bPtr)).takeUnretainedValue()
    encoder.setVertexBuffer(buffer, offset: offset, index: Int(slot))
}

@_cdecl("iris_metal_set_fragment_buffer")
public func irisMetalSetFragmentBuffer(
    _ encoderPtr: OpaquePointer?,
    _ slot: Int32,
    _ bufferPtr: OpaquePointer?,
    _ offset: Int
) {
    guard let ePtr = encoderPtr, let bPtr = bufferPtr else { return }
    let encoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(UnsafeRawPointer(ePtr)).takeUnretainedValue()
    let buffer = Unmanaged<MTLBuffer>.fromOpaque(UnsafeRawPointer(bPtr)).takeUnretainedValue()
    encoder.setFragmentBuffer(buffer, offset: offset, index: Int(slot))
}

@_cdecl("iris_metal_set_vertex_texture")
public func irisMetalSetVertexTexture(
    _ encoderPtr: OpaquePointer?,
    _ slot: Int32,
    _ texturePtr: OpaquePointer?
) {
    guard let ePtr = encoderPtr else { return }
    let encoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(UnsafeRawPointer(ePtr)).takeUnretainedValue()

    if let tPtr = texturePtr {
        let texture = Unmanaged<MTLTexture>.fromOpaque(UnsafeRawPointer(tPtr)).takeUnretainedValue()
        encoder.setVertexTexture(texture, index: Int(slot))
    } else {
        encoder.setVertexTexture(nil, index: Int(slot))
    }
}

@_cdecl("iris_metal_set_fragment_texture")
public func irisMetalSetFragmentTexture(
    _ encoderPtr: OpaquePointer?,
    _ slot: Int32,
    _ texturePtr: OpaquePointer?
) {
    guard let ePtr = encoderPtr else { return }
    let encoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(UnsafeRawPointer(ePtr)).takeUnretainedValue()

    if let tPtr = texturePtr {
        let texture = Unmanaged<MTLTexture>.fromOpaque(UnsafeRawPointer(tPtr)).takeUnretainedValue()
        encoder.setFragmentTexture(texture, index: Int(slot))
    } else {
        encoder.setFragmentTexture(nil, index: Int(slot))
    }
}

@_cdecl("iris_metal_set_vertex_sampler_state")
public func irisMetalSetVertexSamplerState(
    _ encoderPtr: OpaquePointer?,
    _ slot: Int32,
    _ samplerPtr: OpaquePointer?
) {
    guard let ePtr = encoderPtr, let sPtr = samplerPtr else { return }
    let encoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(UnsafeRawPointer(ePtr)).takeUnretainedValue()
    let sampler = Unmanaged<MTLSamplerState>.fromOpaque(UnsafeRawPointer(sPtr)).takeUnretainedValue()
    encoder.setVertexSamplerState(sampler, index: Int(slot))
}

@_cdecl("iris_metal_set_fragment_sampler_state")
public func irisMetalSetFragmentSamplerState(
    _ encoderPtr: OpaquePointer?,
    _ slot: Int32,
    _ samplerPtr: OpaquePointer?
) {
    guard let ePtr = encoderPtr, let sPtr = samplerPtr else { return }
    let encoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(UnsafeRawPointer(ePtr)).takeUnretainedValue()
    let sampler = Unmanaged<MTLSamplerState>.fromOpaque(UnsafeRawPointer(sPtr)).takeUnretainedValue()
    encoder.setFragmentSamplerState(sampler, index: Int(slot))
}

@_cdecl("iris_metal_set_vertex_bytes")
public func irisMetalSetVertexBytes(
    _ encoderPtr: OpaquePointer?,
    _ slot: Int32,
    _ data: UnsafeRawPointer?,
    _ length: Int
) {
    guard let ePtr = encoderPtr, let dPtr = data else { return }
    let encoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(UnsafeRawPointer(ePtr)).takeUnretainedValue()
    encoder.setVertexBytes(dPtr, length: length, index: Int(slot))
}

@_cdecl("iris_metal_set_fragment_bytes")
public func irisMetalSetFragmentBytes(
    _ encoderPtr: OpaquePointer?,
    _ slot: Int32,
    _ data: UnsafeRawPointer?,
    _ length: Int
) {
    guard let ePtr = encoderPtr, let dPtr = data else { return }
    let encoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(UnsafeRawPointer(ePtr)).takeUnretainedValue()
    encoder.setFragmentBytes(dPtr, length: length, index: Int(slot))
}

// MARK: - 绘制命令

@_cdecl("iris_metal_draw_primitives")
public func irisMetalDrawPrimitives(
    _ encoderPtr: OpaquePointer?,
    _ primitiveType: Int32,
    _ vertexStart: Int32,
    _ vertexCount: Int32,
    _ instanceCount: Int32
) {
    guard let ptr = encoderPtr else { return }
    let encoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(UnsafeRawPointer(ptr)).takeUnretainedValue()
    let mtlType = mtlPrimitiveTypeFromInt(primitiveType)
    // 修复：添加 type: 标签
    encoder.drawPrimitives(type: mtlType, vertexStart: Int(vertexStart), vertexCount: Int(vertexCount), instanceCount: Int(instanceCount))
}

@_cdecl("iris_metal_draw_indexed_primitives")
public func irisMetalDrawIndexedPrimitives(
    _ encoderPtr: OpaquePointer?,
    _ primitiveType: Int32,
    _ indexCount: Int32,
    _ indexType: Int32,
    _ indexBufferPtr: OpaquePointer?,
    _ indexBufferOffset: Int,
    _ instanceCount: Int32
) {
    guard let ePtr = encoderPtr, let ibPtr = indexBufferPtr else { return }
    let encoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(UnsafeRawPointer(ePtr)).takeUnretainedValue()
    let indexBuffer = Unmanaged<MTLBuffer>.fromOpaque(UnsafeRawPointer(ibPtr)).takeUnretainedValue()

    let mtlType = mtlPrimitiveTypeFromInt(primitiveType)
    let mtlIndexType = indexType == 0 ? MTLIndexType.uint16 : MTLIndexType.uint32

    // 修复：添加 type: 标签
    encoder.drawIndexedPrimitives(type: mtlType, indexCount: Int(indexCount), indexType: mtlIndexType, indexBuffer: indexBuffer, indexBufferOffset: indexBufferOffset, instanceCount: Int(instanceCount))
}

// MARK: - Blit 命令

@_cdecl("iris_metal_blit_copy_buffer_to_texture")
public func irisMetalBlitCopyBufferToTexture(
    _ encoderPtr: OpaquePointer?,
    _ sourceBufferPtr: OpaquePointer?,
    _ sourceOffset: Int,
    _ sourceBytesPerRow: Int,
    _ sourceBytesPerImage: Int,
    _ destinationTexturePtr: OpaquePointer?,
    _ destinationSlice: Int32,
    _ destinationLevel: Int32,
    _ destinationOriginX: Int32,
    _ destinationOriginY: Int32,
    _ destinationOriginZ: Int32,
    _ sourceSizeWidth: Int32,
    _ sourceSizeHeight: Int32,
    _ sourceSizeDepth: Int32
) {
    guard let ePtr = encoderPtr, let sPtr = sourceBufferPtr, let dPtr = destinationTexturePtr else { return }
    let encoder = Unmanaged<MTLBlitCommandEncoder>.fromOpaque(UnsafeRawPointer(ePtr)).takeUnretainedValue()
    let sourceBuffer = Unmanaged<MTLBuffer>.fromOpaque(UnsafeRawPointer(sPtr)).takeUnretainedValue()
    let destTexture = Unmanaged<MTLTexture>.fromOpaque(UnsafeRawPointer(dPtr)).takeUnretainedValue()

    let origin = MTLOrigin(x: Int(destinationOriginX), y: Int(destinationOriginY), z: Int(destinationOriginZ))
    let size = MTLSize(width: Int(sourceSizeWidth), height: Int(sourceSizeHeight), depth: Int(sourceSizeDepth))

    encoder.copy(from: sourceBuffer, sourceOffset: sourceOffset, sourceBytesPerRow: sourceBytesPerRow, sourceBytesPerImage: sourceBytesPerImage, sourceSize: size, to: destTexture, destinationSlice: Int(destinationSlice), destinationLevel: Int(destinationLevel), destinationOrigin: origin)
}

@_cdecl("iris_metal_blit_copy_texture_to_buffer")
public func irisMetalBlitCopyTextureToBuffer(
    _ encoderPtr: OpaquePointer?,
    _ sourceTexturePtr: OpaquePointer?,
    _ sourceSlice: Int32,
    _ sourceLevel: Int32,
    _ sourceOriginX: Int32,
    _ sourceOriginY: Int32,
    _ sourceOriginZ: Int32,
    _ sourceSizeWidth: Int32,
    _ sourceSizeHeight: Int32,
    _ sourceSizeDepth: Int32,
    _ destinationBufferPtr: OpaquePointer?,
    _ destinationOffset: Int,
    _ destinationBytesPerRow: Int,
    _ destinationBytesPerImage: Int
) {
    guard let ePtr = encoderPtr, let sPtr = sourceTexturePtr, let dPtr = destinationBufferPtr else { return }
    let encoder = Unmanaged<MTLBlitCommandEncoder>.fromOpaque(UnsafeRawPointer(ePtr)).takeUnretainedValue()
    let sourceTexture = Unmanaged<MTLTexture>.fromOpaque(UnsafeRawPointer(sPtr)).takeUnretainedValue()
    let destBuffer = Unmanaged<MTLBuffer>.fromOpaque(UnsafeRawPointer(dPtr)).takeUnretainedValue()

    let origin = MTLOrigin(x: Int(sourceOriginX), y: Int(sourceOriginY), z: Int(sourceOriginZ))
    let size = MTLSize(width: Int(sourceSizeWidth), height: Int(sourceSizeHeight), depth: Int(sourceSizeDepth))

    encoder.copy(from: sourceTexture, sourceSlice: Int(sourceSlice), sourceLevel: Int(sourceLevel), sourceOrigin: origin, sourceSize: size, to: destBuffer, destinationOffset: destinationOffset, destinationBytesPerRow: destinationBytesPerImage, destinationBytesPerImage: destinationBytesPerImage)
}

@_cdecl("iris_metal_blit_generate_mipmaps")
public func irisMetalBlitGenerateMipmaps(
    _ encoderPtr: OpaquePointer?,
    _ texturePtr: OpaquePointer?
) {
    guard let ePtr = encoderPtr, let tPtr = texturePtr else { return }
    let encoder = Unmanaged<MTLBlitCommandEncoder>.fromOpaque(UnsafeRawPointer(ePtr)).takeUnretainedValue()
    let texture = Unmanaged<MTLTexture>.fromOpaque(UnsafeRawPointer(tPtr)).takeUnretainedValue()

    encoder.generateMipmaps(for: texture)
}

// MARK: - Shader 编译 (GLSL → SPIRV → MSL → MTLLibrary)

@_cdecl("iris_metal_compile_glsl_to_msl")
public func irisMetalCompileGlslToMsl(
    _ glslSource: UnsafePointer<CChar>?,
    _ stage: Int32, // 0=vertex, 1=fragment, 2=compute
    _ entryPoint: UnsafePointer<CChar>?
) -> OpaquePointer? {
    // 本函数在 Java 侧通过 SPIRV-Cross (LWJGL spvc) 完成 GLSL→SPIRV→MSL 转换
    // 这里接收已经转换好的 MSL 源码，编译为 MTLLibrary
    // 注意：实际实现中，GLSL→MSL 转换在 Java 侧用 LWJGL 的 spvc 绑定完成
    // 本函数仅负责 MSL → MTLLibrary 的编译
    // 返回一个封装了 MTLLibrary 和 error 的句柄
    return nil // 占位，实际编译在 iris_metal_compile_msl_to_library
}

@_cdecl("iris_metal_compile_msl_to_library")
public func irisMetalCompileMslToLibrary(
    _ devicePtr: OpaquePointer?,
    _ mslSource: UnsafePointer<CChar>?
) -> OpaquePointer? {
    guard let dPtr = devicePtr, let srcPtr = mslSource else { return nil }
    let device = Unmanaged<MTLDevice>.fromOpaque(UnsafeRawPointer(dPtr)).takeUnretainedValue()
    let source = String(cString: srcPtr)

    let library: MTLLibrary
    do {
        library = try device.makeLibrary(source: source, options: nil)
    } catch {
        // 编译失败，返回 nil（错误信息通过其他方式获取）
        return nil
    }

    return OpaquePointer(Unmanaged.passUnretained(library).toOpaque())
}

@_cdecl("iris_metal_get_function")
public func irisMetalGetFunction(
    _ libraryPtr: OpaquePointer?,
    _ name: UnsafePointer<CChar>?
) -> OpaquePointer? {
    guard let lPtr = libraryPtr, let nPtr = name else { return nil }
    let library = Unmanaged<MTLLibrary>.fromOpaque(UnsafeRawPointer(lPtr)).takeUnretainedValue()
    let funcName = String(cString: nPtr)

    guard let function = library.makeFunction(name: funcName) else { return nil }
    return OpaquePointer(Unmanaged.passUnretained(function).toOpaque())
}

// MARK: - Pipeline State 创建

@_cdecl("iris_metal_create_render_pipeline_state")
public func irisMetalCreateRenderPipelineState(
    _ devicePtr: OpaquePointer?,
    _ vertexFunctionPtr: OpaquePointer?,
    _ fragmentFunctionPtr: OpaquePointer?,
    _ colorFormats: UnsafePointer<Int32>?,
    _ colorFormatCount: Int32,
    _ depthPixelFormat: Int32,
    _ stencilPixelFormat: Int32,
    _ vertexDescriptorPtr: OpaquePointer?,
    _ blendEnabled: Int32,
    _ blendSrcRgb: Int32,
    _ blendDstRgb: Int32,
    _ blendSrcAlpha: Int32,
    _ blendDstAlpha: Int32,
    _ blendOpRgb: Int32,
    _ blendOpAlpha: Int32
) -> OpaquePointer? {
    guard let dPtr = devicePtr else { return nil }
    let device = Unmanaged<MTLDevice>.fromOpaque(UnsafeRawPointer(dPtr)).takeUnretainedValue()

    let desc = MTLRenderPipelineDescriptor()

    if let vfPtr = vertexFunctionPtr {
        let vf = Unmanaged<MTLFunction>.fromOpaque(UnsafeRawPointer(vfPtr)).takeUnretainedValue()
        desc.vertexFunction = vf
    }

    if let ffPtr = fragmentFunctionPtr {
        let ff = Unmanaged<MTLFunction>.fromOpaque(UnsafeRawPointer(ffPtr)).takeUnretainedValue()
        desc.fragmentFunction = ff
    }

    // 颜色附件格式
    if let formats = colorFormats {
        for i in 0..<Int(colorFormatCount) {
            let fmt = formats[i]
            if fmt > 0 {
                // 修复：使用 if let 解包可选值
                if let attachment = desc.colorAttachments[i] {
                    attachment.pixelFormat = mtlPixelFormatFromInt(fmt)

                    if blendEnabled != 0 {
                        attachment.isBlendingEnabled = true
                        attachment.sourceRGBBlendFactor = mtlBlendFactorFromInt(blendSrcRgb)
                        attachment.destinationRGBBlendFactor = mtlBlendFactorFromInt(blendDstRgb)
                        attachment.sourceAlphaBlendFactor = mtlBlendFactorFromInt(blendSrcAlpha)
                        attachment.destinationAlphaBlendFactor = mtlBlendFactorFromInt(blendDstAlpha)
                        attachment.rgbBlendOperation = mtlBlendOperationFromInt(blendOpRgb)
                        attachment.alphaBlendOperation = mtlBlendOperationFromInt(blendOpAlpha)
                    }
                }
            }
        }
    }

    // 深度格式
    if depthPixelFormat > 0 {
        desc.depthAttachmentPixelFormat = mtlPixelFormatFromInt(depthPixelFormat)
    }

    if stencilPixelFormat > 0 {
        desc.stencilAttachmentPixelFormat = mtlPixelFormatFromInt(stencilPixelFormat)
    }

    // Vertex descriptor
    if let vdPtr = vertexDescriptorPtr {
        let vd = Unmanaged<MTLVertexDescriptor>.fromOpaque(UnsafeRawPointer(vdPtr)).takeUnretainedValue()
        desc.vertexDescriptor = vd
    }

    let pipeline: MTLRenderPipelineState
    do {
        pipeline = try device.makeRenderPipelineState(descriptor: desc)
    } catch {
        return nil
    }

    return OpaquePointer(Unmanaged.passUnretained(pipeline).toOpaque())
}

// MARK: - Sampler State 创建

@_cdecl("iris_metal_create_sampler_state")
public func irisMetalCreateSamplerState(
    _ devicePtr: OpaquePointer?,
    _ minFilter: Int32, // 0=nearest, 1=linear
    _ magFilter: Int32,
    _ mipFilter: Int32, // 0=not mipmapped, 1=nearest, 2=linear
    _ sAddressMode: Int32, // 0=clamp, 1=repeat, 2=mirror_repeat
    _ tAddressMode: Int32,
    _ rAddressMode: Int32,
    _ maxAnisotropy: Int32
) -> OpaquePointer? {
    guard let dPtr = devicePtr else { return nil }
    let device = Unmanaged<MTLDevice>.fromOpaque(UnsafeRawPointer(dPtr)).takeUnretainedValue()

    let desc = MTLSamplerDescriptor()
    desc.minFilter = minFilter == 0 ? .nearest : .linear
    desc.magFilter = magFilter == 0 ? .nearest : .linear

    switch mipFilter {
    case 1:
        desc.mipFilter = .nearest
    case 2:
        desc.mipFilter = .linear
    default:
        desc.mipFilter = .notMipmapped
    }

    desc.sAddressMode = mtlSamplerAddressModeFromInt(sAddressMode)
    desc.tAddressMode = mtlSamplerAddressModeFromInt(tAddressMode)
    desc.rAddressMode = mtlSamplerAddressModeFromInt(rAddressMode)

    if maxAnisotropy > 1 {
        desc.maxAnisotropy = Int(maxAnisotropy)
    }

    guard let sampler = device.makeSamplerState(descriptor: desc) else { return nil }
    return OpaquePointer(Unmanaged.passUnretained(sampler).toOpaque())
}

// MARK: - 辅助转换函数

private func mtlPixelFormatFromInt(_ value: Int32) -> MTLPixelFormat {
    switch value {
    case 30: return .rgba8Unorm
    case 31: return .rgba8Unorm_srgb
    case 80: return .bgra8Unorm
    case 81: return .bgra8Unorm_srgb
    case 65: return .rgba16Unorm
    case 20: return .rg8Unorm
    case 32: return .rgba8Snorm
    case 67: return .rgba16Snorm
    case 45: return .rgba8Uint
    case 46: return .rgba8Sint
    case 50: return .rgba16Uint
    case 51: return .rgba16Sint
    case 55: return .rgba16Float
    case 60: return .rgba32Uint
    case 61: return .rgba32Sint
    case 62: return .rgba32Float
    case 25: return .r16Float
    case 53: return .rg16Float
    case 58: return .rg32Float
    case 10: return .r32Float
    case 22: return .r16Snorm
    case 90: return .rg11b10Float      // 修正名称：从 rg11B10Float 改为 rg11b10Float
    case 92: return .rgb9e5Float
    case 93: return .bgr10a2Unorm
    case 252: return .depth16Unorm
    case 253: return .depth32Float
    case 255: return .depth32FloatStencil8  // 修正名称：从 depth32Float_Stencil8 改为 depth32FloatStencil8
    case 254: return .stencil8
    default: return .rgba8Unorm
    }
}

private func mtlPrimitiveTypeFromInt(_ value: Int32) -> MTLPrimitiveType {
    switch value {
    case 0: return .triangle
    case 1: return .triangleStrip
    case 2: return .line
    case 3: return .lineStrip
    case 4: return .point
    default: return .triangle
    }
}

private func mtlBlendFactorFromInt(_ value: Int32) -> MTLBlendFactor {
    switch value {
    case 0: return .zero
    case 1: return .one
    case 2: return .sourceColor
    case 3: return .oneMinusSourceColor
    case 4: return .destinationColor
    case 5: return .oneMinusDestinationColor
    case 6: return .sourceAlpha
    case 7: return .oneMinusSourceAlpha
    case 8: return .destinationAlpha
    case 9: return .oneMinusDestinationAlpha
    case 10: return .blendColor
    case 11: return .oneMinusBlendColor
    case 12: return .blendAlpha
    case 13: return .oneMinusBlendAlpha
    case 14: return .sourceAlphaSaturated
    case 15: return .source1Color
    case 16: return .oneMinusSource1Color
    case 17: return .source1Alpha
    case 18: return .oneMinusSource1Alpha
    default: return .one
    }
}

private func mtlBlendOperationFromInt(_ value: Int32) -> MTLBlendOperation {
    switch value {
    case 0: return .add
    case 1: return .subtract
    case 2: return .reverseSubtract
    case 3: return .min
    case 4: return .max
    default: return .add
    }
}

private func mtlSamplerAddressModeFromInt(_ value: Int32) -> MTLSamplerAddressMode {
    switch value {
    case 0: return .clampToEdge
    case 1: return .repeat
    case 2: return .mirrorRepeat
    case 3: return .clampToZero
    default: return .clampToEdge
    }
}

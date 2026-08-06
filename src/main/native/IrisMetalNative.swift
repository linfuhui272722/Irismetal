import Foundation
import Metal
import MetalKit
import simd

#if os(macOS)
import Darwin
#elseif os(iOS)
import Darwin
#endif

// MARK: - Global State

private struct GlobalState {
    static var device: MTLDevice?
    static var commandQueue: MTLCommandQueue?
    static var depthStencilStates: [String: MTLDepthStencilState] = [:]
    static var samplerStates: [String: MTLSamplerState] = [:]
    static var renderPipelineStates: [String: MTLRenderPipelineState] = [:]
    static var computePipelineStates: [String: MTLComputePipelineState] = [:]
    static let pipelineLock = NSLock()
}

// MARK: - Device Functions

@_cdecl("iris_metal_create_system_default_device")
public func iris_metal_create_system_default_device() -> UnsafeMutableRawPointer? {
    if let device = MTLCreateSystemDefaultDevice() {
        GlobalState.device = device
        GlobalState.commandQueue = device.makeCommandQueue()
        return Unmanaged.passUnretained(device).toOpaque()
    }
    return nil
}

@_cdecl("iris_metal_copy_device_name")
public func iris_metal_copy_device_name(
    _ nameBuffer: UnsafeMutablePointer<CChar>,
    _ bufferSize: Int,
    _ device: UnsafeMutableRawPointer?
) -> Int32 {
    guard let device = device else { return 0 }
    let metalDevice = Unmanaged<MTLDevice>.fromOpaque(device).takeUnretainedValue()
    let name = metalDevice.name
    let nameData = name.data(using: .utf8) ?? Data()
    let copyLength = min(nameData.count, bufferSize - 1)
    nameBuffer.initializeMemory(as: CChar.self, from: Array(nameData.prefix(copyLength)), count: copyLength)
    nameBuffer[copyLength] = 0
    return Int32(copyLength + 1)
}

@_cdecl("iris_metal_MTLDevice_makeCommandQueue")
public func iris_metal_MTLDevice_makeCommandQueue(
    _ device: UnsafeMutableRawPointer?
) -> UnsafeMutableRawPointer? {
    guard let device = device else { return nil }
    let metalDevice = Unmanaged<MTLDevice>.fromOpaque(device).takeUnretainedValue()
    if let queue = metalDevice.makeCommandQueue() {
        GlobalState.commandQueue = queue
        return Unmanaged.passUnretained(queue).toOpaque()
    }
    return nil
}

// MARK: - Command Buffer Functions

@_cdecl("iris_metal_MTLCommandQueue_makeCommandBuffer")
public func iris_metal_MTLCommandQueue_makeCommandBuffer(
    _ commandQueue: UnsafeMutableRawPointer?
) -> UnsafeMutableRawPointer? {
    guard let queue = commandQueue else { return nil }
    let metalQueue = Unmanaged<MTLCommandQueue>.fromOpaque(queue).takeUnretainedValue()
    if let buffer = metalQueue.makeCommandBuffer() {
        return Unmanaged.passUnretained(buffer).toOpaque()
    }
    return nil
}

@_cdecl("iris_metal_MTLCommandBuffer_commit")
public func iris_metal_MTLCommandBuffer_commit(
    _ commandBuffer: UnsafeMutableRawPointer?
) {
    guard let buffer = commandBuffer else { return }
    let metalBuffer = Unmanaged<MTLCommandBuffer>.fromOpaque(buffer).takeUnretainedValue()
    metalBuffer.commit()
}

@_cdecl("iris_metal_MTLCommandBuffer_waitUntilCompleted")
public func iris_metal_MTLCommandBuffer_waitUntilCompleted(
    _ commandBuffer: UnsafeMutableRawPointer?
) {
    guard let buffer = commandBuffer else { return }
    let metalBuffer = Unmanaged<MTLCommandBuffer>.fromOpaque(buffer).takeUnretainedValue()
    metalBuffer.waitUntilCompleted()
}

@_cdecl("iris_metal_MTLCommandBuffer_isCompleted")
public func iris_metal_MTLCommandBuffer_isCompleted(
    _ commandBuffer: UnsafeMutableRawPointer?
) -> Int32 {
    guard let buffer = commandBuffer else { return 0 }
    let metalBuffer = Unmanaged<MTLCommandBuffer>.fromOpaque(buffer).takeUnretainedValue()
    return metalBuffer.status == .completed ? 1 : 0
}

@_cdecl("iris_metal_MTLCommandBuffer_pushDebugGroup")
public func iris_metal_MTLCommandBuffer_pushDebugGroup(
    _ commandBuffer: UnsafeMutableRawPointer?,
    _ name: UnsafePointer<CChar>?
) {
    guard let buffer = commandBuffer, let nameStr = name else { return }
    let metalBuffer = Unmanaged<MTLCommandBuffer>.fromOpaque(buffer).takeUnretainedValue()
    metalBuffer.pushDebugGroup(String(cString: nameStr))
}

@_cdecl("iris_metal_MTLCommandBuffer_popDebugGroup")
public func iris_metal_MTLCommandBuffer_popDebugGroup(
    _ commandBuffer: UnsafeMutableRawPointer?
) {
    guard let buffer = commandBuffer else { return }
    let metalBuffer = Unmanaged<MTLCommandBuffer>.fromOpaque(buffer).takeUnretainedValue()
    metalBuffer.popDebugGroup()
}

// MARK: - Render Command Encoder

@_cdecl("iris_metal_MTLCommandBuffer_makeRenderCommandEncoder")
public func iris_metal_MTLCommandBuffer_makeRenderCommandEncoder(
    _ commandBuffer: UnsafeMutableRawPointer?,
    _ renderPassDescriptor: UnsafeMutableRawPointer?
) -> UnsafeMutableRawPointer? {
    guard let buffer = commandBuffer else { return nil }
    let metalBuffer = Unmanaged<MTLCommandBuffer>.fromOpaque(buffer).takeUnretainedValue()
    
    let descriptor: MTLRenderPassDescriptor
    if let descPtr = renderPassDescriptor {
        descriptor = Unmanaged<MTLRenderPassDescriptor>.fromOpaque(descPtr).takeUnretainedValue()
    } else {
        descriptor = MTLRenderPassDescriptor()
    }
    
    if let encoder = metalBuffer.makeRenderCommandEncoder(descriptor: descriptor) {
        return Unmanaged.passUnretained(encoder).toOpaque()
    }
    return nil
}

// MARK: - Blit Command Encoder

@_cdecl("iris_metal_MTLCommandBuffer_makeBlitCommandEncoder")
public func iris_metal_MTLCommandBuffer_makeBlitCommandEncoder(
    _ commandBuffer: UnsafeMutableRawPointer?
) -> UnsafeMutableRawPointer? {
    guard let buffer = commandBuffer else { return nil }
    let metalBuffer = Unmanaged<MTLCommandBuffer>.fromOpaque(buffer).takeUnretainedValue()
    if let encoder = metalBuffer.makeBlitCommandEncoder() {
        return Unmanaged.passUnretained(encoder).toOpaque()
    }
    return nil
}

// MARK: - Compute Command Encoder

@_cdecl("iris_metal_MTLCommandBuffer_makeComputeCommandEncoder")
public func iris_metal_MTLCommandBuffer_makeComputeCommandEncoder(
    _ commandBuffer: UnsafeMutableRawPointer?
) -> UnsafeMutableRawPointer? {
    guard let buffer = commandBuffer else { return nil }
    let metalBuffer = Unmanaged<MTLCommandBuffer>.fromOpaque(buffer).takeUnretainedValue()
    if let encoder = metalBuffer.makeComputeCommandEncoder() {
        return Unmanaged.passUnretained(encoder).toOpaque()
    }
    return nil
}

// MARK: - Encoder End

@_cdecl("iris_metal_MTLCommandEncoder_endEncoding")
public func iris_metal_MTLCommandEncoder_endEncoding(
    _ encoder: UnsafeMutableRawPointer?
) {
    guard let enc = encoder else { return }
    let metalEncoder = Unmanaged<MTLCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    metalEncoder.endEncoding()
}

// MARK: - Texture Functions

@_cdecl("iris_metal_create_texture_2d")
public func iris_metal_create_texture_2d(
    _ device: UnsafeMutableRawPointer?,
    _ width: Int,
    _ height: Int,
    _ pixelFormat: Int32,
    _ usage: Int32
) -> UnsafeMutableRawPointer? {
    guard let dev = device else { return nil }
    let metalDevice = Unmanaged<MTLDevice>.fromOpaque(dev).takeUnretainedValue()
    
    let desc = MTLTextureDescriptor()
    desc.textureType = .type2D
    desc.width = width
    desc.height = height
    desc.pixelFormat = MTLPixelFormat(rawValue: UInt(pixelFormat))
    desc.usage = MTLTextureUsage(rawValue: UInt(usage))
    desc.storageMode = .shared
    
    if let texture = metalDevice.makeTexture(descriptor: desc) {
        return Unmanaged.passUnretained(texture).toOpaque()
    }
    return nil
}

@_cdecl("iris_metal_create_texture_3d")
public func iris_metal_create_texture_3d(
    _ device: UnsafeMutableRawPointer?,
    _ width: Int,
    _ height: Int,
    _ depth: Int,
    _ pixelFormat: Int32,
    _ usage: Int32
) -> UnsafeMutableRawPointer? {
    guard let dev = device else { return nil }
    let metalDevice = Unmanaged<MTLDevice>.fromOpaque(dev).takeUnretainedValue()
    
    let desc = MTLTextureDescriptor()
    desc.textureType = .type3D
    desc.width = width
    desc.height = height
    desc.depth = depth
    desc.pixelFormat = MTLPixelFormat(rawValue: UInt(pixelFormat))
    desc.usage = MTLTextureUsage(rawValue: UInt(usage))
    desc.storageMode = .shared
    
    if let texture = metalDevice.makeTexture(descriptor: desc) {
        return Unmanaged.passUnretained(texture).toOpaque()
    }
    return nil
}

@_cdecl("iris_metal_create_texture_cube")
public func iris_metal_create_texture_cube(
    _ device: UnsafeMutableRawPointer?,
    _ size: Int,
    _ pixelFormat: Int32,
    _ usage: Int32
) -> UnsafeMutableRawPointer? {
    guard let dev = device else { return nil }
    let metalDevice = Unmanaged<MTLDevice>.fromOpaque(dev).takeUnretainedValue()
    
    let desc = MTLTextureDescriptor()
    desc.textureType = .typeCube
    desc.width = size
    desc.height = size
    desc.pixelFormat = MTLPixelFormat(rawValue: UInt(pixelFormat))
    desc.usage = MTLTextureUsage(rawValue: UInt(usage))
    desc.storageMode = .shared
    
    if let texture = metalDevice.makeTexture(descriptor: desc) {
        return Unmanaged.passUnretained(texture).toOpaque()
    }
    return nil
}

@_cdecl("iris_metal_texture_replace_region")
public func iris_metal_texture_replace_region(
    _ texture: UnsafeMutableRawPointer?,
    _ width: Int,
    _ height: Int,
    _ bytesPerRow: Int,
    _ bytesPerImage: Int,
    _ data: UnsafeRawPointer?,
    _ mipmapLevel: Int,
    _ slice: Int
) {
    guard let tex = texture, let dataPtr = data else { return }
    let metalTexture = Unmanaged<MTLTexture>.fromOpaque(tex).takeUnretainedValue()
    
    let region = MTLRegion(
        origin: MTLOrigin(x: 0, y: 0, z: slice),
        size: MTLSize(width: width, height: height, depth: 1)
    )
    
    metalTexture.replace(
        region: region,
        mipmapLevel: mipmapLevel,
        withBytes: dataPtr,
        bytesPerRow: bytesPerRow,
        bytesPerImage: bytesPerImage
    )
}

@_cdecl("iris_metal_texture_get_bytes")
public func iris_metal_texture_get_bytes(
    _ texture: UnsafeMutableRawPointer?,
    _ data: UnsafeMutableRawPointer?,
    _ bytesPerRow: Int,
    _ mipmapLevel: Int
) {
    guard let tex = texture, let dataPtr = data else { return }
    let metalTexture = Unmanaged<MTLTexture>.fromOpaque(tex).takeUnretainedValue()
    
    let region = MTLRegion(
        origin: MTLOrigin(x: 0, y: 0, z: 0),
        size: MTLSize(width: metalTexture.width, height: metalTexture.height, depth: 1)
    )
    
    metalTexture.getBytes(
        dataPtr,
        bytesPerRow: bytesPerRow,
        from: region,
        mipmapLevel: mipmapLevel
    )
}

// MARK: - Buffer Functions

@_cdecl("iris_metal_create_buffer")
public func iris_metal_create_buffer(
    _ device: UnsafeMutableRawPointer?,
    _ length: Int,
    _ options: Int32
) -> UnsafeMutableRawPointer? {
    guard let dev = device else { return nil }
    let metalDevice = Unmanaged<MTLDevice>.fromOpaque(dev).takeUnretainedValue()
    
    if let buffer = metalDevice.makeBuffer(length: length, options: MTLResourceOptions(rawValue: UInt(options))) {
        return Unmanaged.passUnretained(buffer).toOpaque()
    }
    return nil
}

@_cdecl("iris_metal_buffer_contents")
public func iris_metal_buffer_contents(
    _ buffer: UnsafeMutableRawPointer?
) -> UnsafeMutableRawPointer? {
    guard let buf = buffer else { return nil }
    let metalBuffer = Unmanaged<MTLBuffer>.fromOpaque(buf).takeUnretainedValue()
    return metalBuffer.contents()
}

@_cdecl("iris_metal_buffer_replace_region")
public func iris_metal_buffer_replace_region(
    _ buffer: UnsafeMutableRawPointer?,
    _ length: Int,
    _ data: UnsafeRawPointer?,
    _ offset: Int
) {
    guard let buf = buffer, let dataPtr = data else { return }
    let metalBuffer = Unmanaged<MTLBuffer>.fromOpaque(buf).takeUnretainedValue()
    let contents = metalBuffer.contents()
    contents.advanced(by: offset).copyMemory(from: dataPtr, byteCount: length)
}

// MARK: - Pipeline Compilation

@_cdecl("iris_metal_compile_render_pipeline")
public func iris_metal_compile_render_pipeline(
    _ device: UnsafeMutableRawPointer?,
    _ vertexFunction: UnsafeMutableRawPointer?,
    _ fragmentFunction: UnsafeMutableRawPointer?,
    _ vertexData: UnsafeRawPointer?,
    _ vertexDataLength: Int,
    _ colorFormats: UnsafePointer<Int32>?,
    _ colorFormatCount: Int,
    _ depthStencilFormat: Int32
) -> UnsafeMutableRawPointer? {
    guard let dev = device else { return nil }
    let metalDevice = Unmanaged<MTLDevice>.fromOpaque(dev).takeUnretainedValue()
    
    let vertexFunc: MTLFunction?
    let fragmentFunc: MTLFunction?
    
    if let vf = vertexFunction {
        vertexFunc = Unmanaged<MTLFunction>.fromOpaque(vf).takeUnretainedValue()
    } else {
        vertexFunc = nil
    }
    
    if let ff = fragmentFunction {
        fragmentFunc = Unmanaged<MTLFunction>.fromOpaque(ff).takeUnretainedValue()
    } else {
        fragmentFunc = nil
    }
    
    let descriptor = MTLRenderPipelineDescriptor()
    descriptor.vertexFunction = vertexFunc
    descriptor.fragmentFunction = fragmentFunc
    descriptor.colorAttachments[0].pixelFormat = MTLPixelFormat(rawValue: UInt(colorFormats?.pointee ?? 0))
    descriptor.depthAttachmentPixelFormat = MTLPixelFormat(rawValue: UInt(depthStencilFormat))
    
    do {
        let pipeline = try metalDevice.makeRenderPipelineState(descriptor: descriptor)
        return Unmanaged.passUnretained(pipeline).toOpaque()
    } catch {
        return nil
    }
}

@_cdecl("iris_metal_compile_compute_pipeline")
public func iris_metal_compile_compute_pipeline(
    _ device: UnsafeMutableRawPointer?,
    _ computeFunction: UnsafeMutableRawPointer?
) -> UnsafeMutableRawPointer? {
    guard let dev = device else { return nil }
    let metalDevice = Unmanaged<MTLDevice>.fromOpaque(dev).takeUnretainedValue()
    
    let func_: MTLFunction?
    if let f = computeFunction {
        func_ = Unmanaged<MTLFunction>.fromOpaque(f).takeUnretainedValue()
    } else {
        func_ = nil
    }
    
    let descriptor = MTLComputePipelineDescriptor()
    descriptor.computeFunction = func_
    
    do {
        let pipeline = try metalDevice.makeComputePipelineState(descriptor: descriptor)
        return Unmanaged.passUnretained(pipeline).toOpaque()
    } catch {
        return nil
    }
}

// MARK: - Render Encoder Functions

@_cdecl("iris_metal_renderEncoder_setRenderPipelineState")
public func iris_metal_renderEncoder_setRenderPipelineState(
    _ encoder: UnsafeMutableRawPointer?,
    _ pipelineState: UnsafeMutableRawPointer?
) {
    guard let enc = encoder, let ps = pipelineState else { return }
    let renderEncoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    let pipeline = Unmanaged<MTLRenderPipelineState>.fromOpaque(ps).takeUnretainedValue()
    renderEncoder.setRenderPipelineState(pipeline)
}

@_cdecl("iris_metal_renderEncoder_setDepthStencilState")
public func iris_metal_renderEncoder_setDepthStencilState(
    _ encoder: UnsafeMutableRawPointer?,
    _ depthStencilState: UnsafeMutableRawPointer?
) {
    guard let enc = encoder, let ds = depthStencilState else { return }
    let renderEncoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    let state = Unmanaged<MTLDepthStencilState>.fromOpaque(ds).takeUnretainedValue()
    renderEncoder.setDepthStencilState(state)
}

@_cdecl("iris_metal_renderEncoder_setDepthBias")
public func iris_metal_renderEncoder_setDepthBias(
    _ encoder: UnsafeMutableRawPointer?,
    _ depthBias: Float,
    _ slopeScale: Float,
    _ clamp: Float
) {
    guard let enc = encoder else { return }
    let renderEncoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    renderEncoder.setDepthBias(depthBias, slopeScale: slopeScale, clamp: clamp)
}

@_cdecl("iris_metal_renderEncoder_setFrontFacingWinding")
public func iris_metal_renderEncoder_setFrontFacingWinding(
    _ encoder: UnsafeMutableRawPointer?,
    _ winding: Int32
) {
    guard let enc = encoder else { return }
    let renderEncoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    renderEncoder.setFrontFacing(.init(rawValue: MTLWinding(rawValue: UInt(winding))!) ?? .counterClockwise)
}

@_cdecl("iris_metal_renderEncoder_setCullMode")
public func iris_metal_renderEncoder_setCullMode(
    _ encoder: UnsafeMutableRawPointer?,
    _ cullMode: Int32
) {
    guard let enc = encoder else { return }
    let renderEncoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    renderEncoder.setCullMode(.init(rawValue: MTLCullMode(rawValue: UInt(cullMode))!) ?? .back)
}

@_cdecl("iris_metal_renderEncoder_setTriangleFillMode")
public func iris_metal_renderEncoder_setTriangleFillMode(
    _ encoder: UnsafeMutableRawPointer?,
    _ fillMode: Int32
) {
    guard let enc = encoder else { return }
    let renderEncoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    renderEncoder.setTriangleFillMode(.init(rawValue: MTLTriangleFillMode(rawValue: UInt(fillMode))!) ?? .fill)
}

@_cdecl("iris_metal_renderEncoder_setBuffer")
public func iris_metal_renderEncoder_setBuffer(
    _ encoder: UnsafeMutableRawPointer?,
    _ buffer: UnsafeMutableRawPointer?,
    _ offset: Int,
    _ index: Int
) {
    guard let enc = encoder else { return }
    let renderEncoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    if let buf = buffer {
        let metalBuffer = Unmanaged<MTLBuffer>.fromOpaque(buf).takeUnretainedValue()
        renderEncoder.setVertexBuffer(metalBuffer, offset: offset, index: index)
        renderEncoder.setFragmentBuffer(metalBuffer, offset: offset, index: index)
    } else {
        renderEncoder.setVertexBuffer(nil, offset: offset, index: index)
        renderEncoder.setFragmentBuffer(nil, offset: offset, index: index)
    }
}

@_cdecl("iris_metal_renderEncoder_setBufferOffset")
public func iris_metal_renderEncoder_setBufferOffset(
    _ encoder: UnsafeMutableRawPointer?,
    _ offset: Int,
    _ index: Int
) {
    guard let enc = encoder else { return }
    let renderEncoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    renderEncoder.setVertexBufferOffset(offset, index: index)
    renderEncoder.setFragmentBufferOffset(offset, index: index)
}

@_cdecl("iris_metal_renderEncoder_setTexture")
public func iris_metal_renderEncoder_setTexture(
    _ encoder: UnsafeMutableRawPointer?,
    _ texture: UnsafeMutableRawPointer?,
    _ textureIndex: Int,
    _ samplerIndex: Int
) {
    guard let enc = encoder else { return }
    let renderEncoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    if let tex = texture {
        let metalTexture = Unmanaged<MTLTexture>.fromOpaque(tex).takeUnretainedValue()
        renderEncoder.setVertexTexture(metalTexture, index: textureIndex)
        renderEncoder.setFragmentTexture(metalTexture, index: textureIndex)
    }
}

@_cdecl("iris_metal_renderEncoder_setSamplerState")
public func iris_metal_renderEncoder_setSamplerState(
    _ encoder: UnsafeMutableRawPointer?,
    _ sampler: UnsafeMutableRawPointer?,
    _ lodMinClamp: Float,
    _ lodMaxClamp: Float,
    _ index: Int
) {
    guard let enc = encoder else { return }
    let renderEncoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    if let samp = sampler {
        let metalSampler = Unmanaged<MTLSamplerState>.fromOpaque(samp).takeUnretainedValue()
        renderEncoder.setVertexSamplerState(metalSampler, index: index)
        renderEncoder.setFragmentSamplerState(metalSampler, index: index)
    }
}

@_cdecl("iris_metal_renderEncoder_setScissorRect")
public func iris_metal_renderEncoder_setScissorRect(
    _ encoder: UnsafeMutableRawPointer?,
    _ x: Int,
    _ y: Int,
    _ width: Int,
    _ height: Int
) {
    guard let enc = encoder else { return }
    let renderEncoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    renderEncoder.setScissorRect(MTLScissorRect(x: x, y: y, width: width, height: height))
}

@_cdecl("iris_metal_renderEncoder_setViewport")
public func iris_metal_renderEncoder_setViewport(
    _ encoder: UnsafeMutableRawPointer?,
    _ x: Double,
    _ y: Double,
    _ width: Double,
    _ height: Double,
    _ znear: Double,
    _ zfar: Double
) {
    guard let enc = encoder else { return }
    let renderEncoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    renderEncoder.setViewport(MTLViewport(originX: x, originY: y, width: width, height: height, znear: znear, zfar: zfar))
}

@_cdecl("iris_metal_renderEncoder_setBlendColor")
public func iris_metal_renderEncoder_setBlendColor(
    _ encoder: UnsafeMutableRawPointer?,
    _ red: Float,
    _ green: Float,
    _ blue: Float,
    _ alpha: Float
) {
    guard let enc = encoder else { return }
    let renderEncoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    renderEncoder.setBlendColor(red: red, green: green, blue: blue, alpha: alpha)
}

@_cdecl("iris_metal_renderEncoder_setColorWriteMask")
public func iris_metal_renderEncoder_setColorWriteMask(
    _ encoder: UnsafeMutableRawPointer?,
    _ mask: Int32
) {
    // Metal doesn't have color write mask per-encoder, this would need pipeline state
}

@_cdecl("iris_metal_renderEncoder_drawPrimitives")
public func iris_metal_renderEncoder_drawPrimitives(
    _ encoder: UnsafeMutableRawPointer?,
    _ primitiveType: Int32,
    _ vertexStart: Int,
    _ vertexCount: Int,
    _ instanceCount: Int
) {
    guard let enc = encoder else { return }
    let renderEncoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    let pType = MTLPrimitiveType(rawValue: UInt(primitiveType)) ?? .triangle
    renderEncoder.drawPrimitives(
        type: pType,
        vertexStart: vertexStart,
        vertexCount: vertexCount,
        instanceCount: instanceCount
    )
}

@_cdecl("iris_metal_renderEncoder_drawIndexedPrimitives")
public func iris_metal_renderEncoder_drawIndexedPrimitives(
    _ encoder: UnsafeMutableRawPointer?,
    _ primitiveType: Int32,
    _ indexCount: Int,
    _ indexType: Int32,
    _ indexBuffer: UnsafeMutableRawPointer?,
    _ indexBufferOffset: Int,
    _ instanceCount: Int
) {
    guard let enc = encoder, let ib = indexBuffer else { return }
    let renderEncoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    let metalBuffer = Unmanaged<MTLBuffer>.fromOpaque(ib).takeUnretainedValue()
    let pType = MTLPrimitiveType(rawValue: UInt(primitiveType)) ?? .triangle
    let iType = MTLIndexType(rawValue: UInt(indexType)) ?? .uint16
    
    renderEncoder.drawIndexedPrimitives(
        type: pType,
        indexCount: indexCount,
        indexType: iType,
        indexBuffer: metalBuffer,
        indexBufferOffset: indexBufferOffset,
        instanceCount: instanceCount
    )
}

@_cdecl("iris_metal_renderEncoder_drawPrimitivesInstanced")
public func iris_metal_renderEncoder_drawPrimitivesInstanced(
    _ encoder: UnsafeMutableRawPointer?,
    _ primitiveType: Int32,
    _ vertexStart: Int,
    _ vertexCount: Int,
    _ instanceCount: Int,
    _ baseInstance: Int
) {
    guard let enc = encoder else { return }
    let renderEncoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    let pType = MTLPrimitiveType(rawValue: UInt(primitiveType)) ?? .triangle
    renderEncoder.drawPrimitives(
        type: pType,
        vertexStart: vertexStart,
        vertexCount: vertexCount,
        instanceCount: instanceCount,
        baseInstance: baseInstance
    )
}

@_cdecl("iris_metal_renderEncoder_drawIndexedPrimitivesInstanced")
public func iris_metal_renderEncoder_drawIndexedPrimitivesInstanced(
    _ encoder: UnsafeMutableRawPointer?,
    _ primitiveType: Int32,
    _ indexCount: Int,
    _ indexType: Int32,
    _ indexBuffer: UnsafeMutableRawPointer?,
    _ indexBufferOffset: Int,
    _ instanceCount: Int,
    _ baseInstance: Int
) {
    guard let enc = encoder, let ib = indexBuffer else { return }
    let renderEncoder = Unmanaged<MTLRenderCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    let metalBuffer = Unmanaged<MTLBuffer>.fromOpaque(ib).takeUnretainedValue()
    let pType = MTLPrimitiveType(rawValue: UInt(primitiveType)) ?? .triangle
    let iType = MTLIndexType(rawValue: UInt(indexType)) ?? .uint16
    
    renderEncoder.drawIndexedPrimitives(
        type: pType,
        indexCount: indexCount,
        indexType: iType,
        indexBuffer: metalBuffer,
        indexBufferOffset: indexBufferOffset,
        instanceCount: instanceCount,
        baseInstance: baseInstance
    )
}

// MARK: - Compute Encoder Functions

@_cdecl("iris_metal_computeEncoder_setComputePipelineState")
public func iris_metal_computeEncoder_setComputePipelineState(
    _ encoder: UnsafeMutableRawPointer?,
    _ pipelineState: UnsafeMutableRawPointer?
) {
    guard let enc = encoder, let ps = pipelineState else { return }
    let computeEncoder = Unmanaged<MTLComputeCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    let pipeline = Unmanaged<MTLComputePipelineState>.fromOpaque(ps).takeUnretainedValue()
    computeEncoder.setComputePipelineState(pipeline)
}

@_cdecl("iris_metal_computeEncoder_setBuffer")
public func iris_metal_computeEncoder_setBuffer(
    _ encoder: UnsafeMutableRawPointer?,
    _ buffer: UnsafeMutableRawPointer?,
    _ offset: Int,
    _ index: Int
) {
    guard let enc = encoder else { return }
    let computeEncoder = Unmanaged<MTLComputeCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    if let buf = buffer {
        let metalBuffer = Unmanaged<MTLBuffer>.fromOpaque(buf).takeUnretainedValue()
        computeEncoder.setBuffer(metalBuffer, offset: offset, index: index)
    }
}

@_cdecl("iris_metal_computeEncoder_setTexture")
public func iris_metal_computeEncoder_setTexture(
    _ encoder: UnsafeMutableRawPointer?,
    _ texture: UnsafeMutableRawPointer?,
    _ index: Int
) {
    guard let enc = encoder else { return }
    let computeEncoder = Unmanaged<MTLComputeCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    if let tex = texture {
        let metalTexture = Unmanaged<MTLTexture>.fromOpaque(tex).takeUnretainedValue()
        computeEncoder.setTexture(metalTexture, index: index)
    }
}

@_cdecl("iris_metal_computeEncoder_setSamplerState")
public func iris_metal_computeEncoder_setSamplerState(
    _ encoder: UnsafeMutableRawPointer?,
    _ sampler: UnsafeMutableRawPointer?,
    _ index: Int
) {
    guard let enc = encoder else { return }
    let computeEncoder = Unmanaged<MTLComputeCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    if let samp = sampler {
        let metalSampler = Unmanaged<MTLSamplerState>.fromOpaque(samp).takeUnretainedValue()
        computeEncoder.setSamplerState(metalSampler, index: index)
    }
}

@_cdecl("iris_metal_computeEncoder_dispatchThreadgroups")
public func iris_metal_computeEncoder_dispatchThreadgroups(
    _ encoder: UnsafeMutableRawPointer?,
    _ threadgroupsPerGrid: Int,
    _ threadsPerGroupX: Int,
    _ threadsPerGroupY: Int,
    _ threadsPerGroupZ: Int
) {
    guard let enc = encoder else { return }
    let computeEncoder = Unmanaged<MTLComputeCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    computeEncoder.dispatchThreadgroups(
        MTLSize(width: threadgroupsPerGrid, height: 1, depth: 1),
        threadsPerThreadgroup: MTLSize(width: threadsPerGroupX, height: threadsPerGroupY, depth: threadsPerGroupZ)
    )
}

// MARK: - Depth Stencil State

@_cdecl("iris_metal_MTLDevice_makeDepthStencilState")
public func iris_metal_MTLDevice_makeDepthStencilState(
    _ device: UnsafeMutableRawPointer?,
    _ compareFunction: Int32,
    _ depthWriteEnabled: Int32
) -> UnsafeMutableRawPointer? {
    guard let dev = device else { return nil }
    let metalDevice = Unmanaged<MTLDevice>.fromOpaque(dev).takeUnretainedValue()
    
    let desc = MTLDepthStencilDescriptor()
    desc.depthCompareFunction = MTLCompareFunction(rawValue: UInt(compareFunction)) ?? .less
    desc.isDepthWriteEnabled = depthWriteEnabled != 0
    
    if let state = metalDevice.makeDepthStencilState(descriptor: desc) {
        return Unmanaged.passUnretained(state).toOpaque()
    }
    return nil
}

// MARK: - Sampler State

@_cdecl("iris_metal_MTLDevice_makeSamplerState")
public func iris_metal_MTLDevice_makeSamplerState(
    _ device: UnsafeMutableRawPointer?,
    _ minFilter: Int32,
    _ magFilter: Int32,
    _ mipFilter: Int32,
    _ addressModeU: Int32,
    _ addressModeV: Int32,
    _ maxAnisotropy: Int,
    _ lodMinClamp: Float,
    _ lodMaxClamp: Float
) -> UnsafeMutableRawPointer? {
    guard let dev = device else { return nil }
    let metalDevice = Unmanaged<MTLDevice>.fromOpaque(dev).takeUnretainedValue()
    
    let desc = MTLSamplerDescriptor()
    desc.minFilter = MTLSamplerMinMagFilter(rawValue: UInt(minFilter)) ?? .linear
    desc.magFilter = MTLSamplerMinMagFilter(rawValue: UInt(magFilter)) ?? .linear
    desc.mipFilter = MTLSamplerMipFilter(rawValue: UInt(mipFilter)) ?? .linear
    desc.sAddressMode = MTLSamplerAddressMode(rawValue: UInt(addressModeU)) ?? .clampToEdge
    desc.tAddressMode = MTLSamplerAddressMode(rawValue: UInt(addressModeV)) ?? .clampToEdge
    desc.maxAnisotropy = maxAnisotropy > 1 ? Float(maxAnisotropy) : 1
    desc.lodMinClamp = lodMinClamp
    desc.lodMaxClamp = lodMaxClamp
    
    if let state = metalDevice.makeSamplerState(descriptor: desc) {
        return Unmanaged.passUnretained(state).toOpaque()
    }
    return nil
}

// MARK: - Blit Encoder Functions

@_cdecl("iris_metal_blitEncoder_copyBufferToBuffer")
public func iris_metal_blitEncoder_copyBufferToBuffer(
    _ encoder: UnsafeMutableRawPointer?,
    _ source: UnsafeMutableRawPointer?,
    _ destination: UnsafeMutableRawPointer?,
    _ size: Int
) {
    guard let enc = encoder, let src = source, let dst = destination else { return }
    let blitEncoder = Unmanaged<MTLBlitCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    let srcBuffer = Unmanaged<MTLBuffer>.fromOpaque(src).takeUnretainedValue()
    let dstBuffer = Unmanaged<MTLBuffer>.fromOpaque(dst).takeUnretainedValue()
    blitEncoder.copy(from: srcBuffer, to: dstBuffer, size: size)
}

@_cdecl("iris_metal_blitEncoder_copyBufferToTexture")
public func iris_metal_blitEncoder_copyBufferToTexture(
    _ encoder: UnsafeMutableRawPointer?,
    _ source: UnsafeMutableRawPointer?,
    _ destination: UnsafeMutableRawPointer?,
    _ width: Int,
    _ height: Int,
    _ bytesPerRow: Int
) {
    guard let enc = encoder, let src = source, let dst = destination else { return }
    let blitEncoder = Unmanaged<MTLBlitCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    let srcBuffer = Unmanaged<MTLBuffer>.fromOpaque(src).takeUnretainedValue()
    let dstTexture = Unmanaged<MTLTexture>.fromOpaque(dst).takeUnretainedValue()
    
    let region = MTLRegion(
        origin: MTLOrigin(x: 0, y: 0, z: 0),
        size: MTLSize(width: width, height: height, depth: 1)
    )
    blitEncoder.copy(from: srcBuffer, sourceOffset: 0, bytesPerRow: bytesPerRow, bytesPerImage: 0, to: dstTexture, destinationSlice: 0, destinationLevel: 0, destinationOrigin: MTLOrigin(x: 0, y: 0, z: 0))
}

@_cdecl("iris_metal_blitEncoder_copyTextureToTexture")
public func iris_metal_blitEncoder_copyTextureToTexture(
    _ encoder: UnsafeMutableRawPointer?,
    _ source: UnsafeMutableRawPointer?,
    _ destination: UnsafeMutableRawPointer?,
    _ width: Int,
    _ height: Int
) {
    guard let enc = encoder, let src = source, let dst = destination else { return }
    let blitEncoder = Unmanaged<MTLBlitCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    let srcTexture = Unmanaged<MTLTexture>.fromOpaque(src).takeUnretainedValue()
    let dstTexture = Unmanaged<MTLTexture>.fromOpaque(dst).takeUnretainedValue()
    
    let region = MTLRegion(
        origin: MTLOrigin(x: 0, y: 0, z: 0),
        size: MTLSize(width: width, height: height, depth: 1)
    )
    blitEncoder.copy(from: srcTexture, sourceSlice: 0, sourceLevel: 0, sourceOrigin: MTLOrigin(x: 0, y: 0, z: 0), sourceSize: MTLSize(width: width, height: height, depth: 1), to: dstTexture, destinationSlice: 0, destinationLevel: 0, destinationOrigin: MTLOrigin(x: 0, y: 0, z: 0))
}

@_cdecl("iris_metal_blitEncoder_copyTextureToBuffer")
public func iris_metal_blitEncoder_copyTextureToBuffer(
    _ encoder: UnsafeMutableRawPointer?,
    _ source: UnsafeMutableRawPointer?,
    _ destination: UnsafeMutableRawPointer?,
    _ width: Int,
    _ height: Int,
    _ bytesPerRow: Int
) {
    guard let enc = encoder, let src = source, let dst = destination else { return }
    let blitEncoder = Unmanaged<MTLBlitCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    let srcTexture = Unmanaged<MTLTexture>.fromOpaque(src).takeUnretainedValue()
    let dstBuffer = Unmanaged<MTLBuffer>.fromOpaque(dst).takeUnretainedValue()
    
    let region = MTLRegion(
        origin: MTLOrigin(x: 0, y: 0, z: 0),
        size: MTLSize(width: width, height: height, depth: 1)
    )
    blitEncoder.copy(from: srcTexture, sourceSlice: 0, sourceLevel: 0, sourceOrigin: MTLOrigin(x: 0, y: 0, z: 0), sourceSize: MTLSize(width: width, height: height, depth: 1), to: dstBuffer, destinationOffset: 0, bytesPerRow: bytesPerRow, bytesPerImage: 0)
}

@_cdecl("iris_metal_blitEncoder_generateMipmaps")
public func iris_metal_blitEncoder_generateMipmaps(
    _ encoder: UnsafeMutableRawPointer?,
    _ texture: UnsafeMutableRawPointer?
) {
    guard let enc = encoder, let tex = texture else { return }
    let blitEncoder = Unmanaged<MTLBlitCommandEncoder>.fromOpaque(enc).takeUnretainedValue()
    let metalTexture = Unmanaged<MTLTexture>.fromOpaque(tex).takeUnretainedValue()
    blitEncoder.generateMipmaps(for: metalTexture)
}

// MARK: - Release Object

@_cdecl("iris_metal_release_object")
public func iris_metal_release_object(
    _ object: UnsafeMutableRawPointer?
) {
    // In ARC-like Swift memory management, we typically don't need explicit release
    // The Java side should manage the lifecycle via Unmanaged.passRetained/Release
}

// MARK: - SPIRV-Cross (Simplified - would need actual SPIRV-Cross integration)

@_cdecl("iris_metal_compile_glsl_to_msl")
public func iris_metal_compile_glsl_to_msl(
    _ glslSource: UnsafePointer<CChar>?
) -> UnsafeMutableRawPointer? {
    // This is a simplified version - in production would use SPIRV-Cross
    // For now, return nil to indicate compilation failed
    return nil
}

@_cdecl("iris_metal_get_compiled_msl")
public func iris_metal_get_compiled_msl(
    _ handle: UnsafeMutableRawPointer?,
    _ errorBuffer: UnsafeMutablePointer<CChar>?,
    _ errorBufferSize: Int
) -> UnsafeMutableRawPointer? {
    return nil
}

@_cdecl("iris_metal_free_compiled_shader")
public func iris_metal_free_compiled_shader(
    _ handle: UnsafeMutableRawPointer?
) {
    // No-op for simplified version
}

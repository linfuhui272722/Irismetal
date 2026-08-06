#!/bin/bash
# Build script for Iris Metal Native Library
# Requires Xcode command line tools on macOS

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${SCRIPT_DIR}/build"
SRC_FILE="${SCRIPT_DIR}/IrisMetalNative.swift"

# Create build directory
mkdir -p "${BUILD_DIR}"

echo "Building Iris Metal Native Library..."
echo "Source: ${SRC_FILE}"

# Check for macOS
if [[ "$(uname)" != "Darwin" ]]; then
    echo "Error: This build script requires macOS"
    exit 1
fi

# Check for Xcode
if ! command -v xcodebuild &> /dev/null; then
    echo "Error: Xcode command line tools not found"
    echo "Install with: xcode-select --install"
    exit 1
fi

# Build for macOS
echo "Building for macOS (arm64)..."
swiftc \
    -O \
    -emit-library \
    -o "${BUILD_DIR}/libiris_metal.dylib" \
    -target arm64-apple-macosx14.0 \
    -sdk "$(xcrun --sdk macosx --show-sdk-path)" \
    -I "$(xcrun --sdk macosx --show-sdk-path)/System/Library/Frameworks" \
    "${SRC_FILE}" \
    -framework Metal \
    -framework MetalKit \
    -framework Foundation \
    -lsqlite3 || true

# If macOS build failed (no Metal SDK), try generic
if [ ! -f "${BUILD_DIR}/libiris_metal.dylib" ]; then
    echo "Trying fallback build..."
    swiftc \
        -O \
        -emit-library \
        -o "${BUILD_DIR}/libiris_metal.dylib" \
        "${SRC_FILE}" \
        -framework Metal \
        -framework Foundation \
        -lsqlite3 || true
fi

# Copy to resources
RESOURCE_DIR="${SCRIPT_DIR}/../resources/natives/macos"
mkdir -p "${RESOURCE_DIR}"
if [ -f "${BUILD_DIR}/libiris_metal.dylib" ]; then
    cp "${BUILD_DIR}/libiris_metal.dylib" "${RESOURCE_DIR}/"
    echo "Built successfully!"
    echo "Output: ${RESOURCE_DIR}/libiris_metal.dylib"
else
    echo "Build failed. Please ensure you have Xcode and Swift installed."
    exit 1
fi

# Also try iOS build if we have iOS SDK
IOS_SDK=$(xcrun --sdk iphoneos --show-sdk-path 2>/dev/null || echo "")
if [ -n "${IOS_SDK}" ] && [ -d "${IOS_SDK}" ]; then
    echo ""
    echo "Building for iOS..."
    mkdir -p "${SCRIPT_DIR}/../resources/natives/ios"
    
    swiftc \
        -O \
        -emit-library \
        -o "${BUILD_DIR}/libiris_metal_ios.dylib" \
        -target arm64-apple-ios14.0-simulator \
        -sdk "${IOS_SDK}" \
        "${SRC_FILE}" \
        -framework Metal \
        -framework Foundation || true
    
    if [ -f "${BUILD_DIR}/libiris_metal_ios.dylib" ]; then
        cp "${BUILD_DIR}/libiris_metal_ios.dylib" "${SCRIPT_DIR}/../resources/natives/ios/libiris_metal.dylib"
        echo "iOS build successful: ${SCRIPT_DIR}/../resources/natives/ios/libiris_metal.dylib"
    fi
fi

echo ""
echo "Build complete!"

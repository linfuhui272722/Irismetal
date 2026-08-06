# Iris Metal Native Library

This directory contains the Swift source code for the Iris Metal native bridge library (`libiris_metal.dylib`).

## Building

### Prerequisites
- macOS with Xcode command line tools
- Swift compiler

### Build Steps

1. Open Terminal and navigate to this directory:
```bash
cd src/main/native
```

2. Make the build script executable:
```bash
chmod +x build.sh
```

3. Run the build script:
```bash
./build.sh
```

This will compile `libiris_metal.dylib` for both macOS and iOS targets.

## Output

The built library will be placed in:
- macOS: `src/main/resources/natives/macos/libiris_metal.dylib`
- iOS: `src/main/resources/natives/ios/libiris_metal.dylib`

## Notes

- The iOS build requires the iOS SDK (included with Xcode on macOS)
- If you don't have a Mac, you can use GitHub Actions to build this library

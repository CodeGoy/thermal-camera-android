# Thermal Camera for Android

An Android application for viewing USB thermal cameras. Provides real-time thermal imaging with temperature analysis, multiple color palettes, and image capture capabilities.

## Features

- Real-time thermal imaging from USB UVC thermal cameras
- Temperature measurement overlays:
  - Center point temperature
  - Minimum temperature location (blue marker)
  - Maximum temperature location (red marker)
  - Average temperature
  - Tap anywhere to see temperature at that point
- Multiple color palettes: Turbo, Inferno, Ironbow, Grayscale
- Adjustable contrast (0.5x - 3.0x)
- Image rotation (0°, 90°, 180°, 270°)
- Pinch-to-zoom and pan
- Temperature rounding options (none, 0.2°C, 0.5°C)
- Screenshot capture to gallery
- FPS display

## Supported Devices

**Currently tested and working:**
- Infiray P2 Pro

**Potentially compatible (same vendor, untested):**
- Topdon TC001
- Other Infiray/Topdon USB-C thermal cameras

If you have a different thermal camera and would like to see it supported, please open an issue with your device details (vendor ID, product ID, resolution).

## Requirements

- Android 7.0 (API 24) or higher
- USB OTG support
- USB-C thermal camera

## Building

### Prerequisites

- Android Studio
- Android SDK 35
- NDK 27.3.13750724
- CMake

### Build Steps

1. Clone the repository:
   ```bash
   git clone https://github.com/user/thermal-camera-android.git
   cd thermal-camera-android
   ```

2. Open the project in Android Studio

3. Sync Gradle and build the project

4. Install on your device via USB debugging or generate an APK

## Usage

1. Connect your thermal camera to your Android device via USB-C
2. Launch the app
3. Grant USB permission when prompted
4. The thermal image will appear automatically

### Controls

| Button | Function |
|--------|----------|
| **Color** | Cycle through color palettes |
| **-** | Decrease contrast |
| **+** | Increase contrast |
| **Rounding** | Toggle temperature rounding mode |
| **Rotate** | Rotate image 90° clockwise |
| **Snap** | Save screenshot to gallery |

### Gestures

- **Pinch**: Zoom in/out (1x - 5x)
- **Drag**: Pan when zoomed
- **Double-tap**: Reset zoom to 1x
- **Single-tap**: Show temperature at tap location

## Technical Details

- Frame resolution: 256x192 thermal pixels
- Frame rate: 25 FPS
- Supported architectures: arm64-v8a, armeabi-v7a

## Contributing

Contributions are welcome! If you'd like to add support for additional thermal cameras:

1. Open an issue with your camera's USB vendor ID and product ID
2. If possible, share frame format details (resolution, pixel format)
3. Submit a pull request with the necessary changes

## Dependencies

- [libusb](https://github.com/libusb/libusb) - USB library
- [libuvc](https://github.com/libuvc/libuvc) - UVC video capture library
- AndroidX libraries

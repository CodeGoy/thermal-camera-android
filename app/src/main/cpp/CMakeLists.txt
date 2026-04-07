cmake_minimum_required(VERSION 3.22.1)
project("thermalcamera")

include(FetchContent)

# ─── libusb 1.0.27 ────────────────────────────────────────────────────────
# libusb uses autotools, not CMake, so we fetch and compile source files directly.
FetchContent_Declare(
    libusb_src
    GIT_REPOSITORY https://github.com/libusb/libusb.git
    GIT_TAG        v1.0.27
    GIT_SHALLOW    TRUE
)
FetchContent_GetProperties(libusb_src)
if(NOT libusb_src_POPULATED)
    FetchContent_Populate(libusb_src)
endif()

# Build libusb as a static library using the Android backend.
# Source list mirrors the official android/jni/libusb.mk from the libusb repository.
add_library(usb STATIC
    ${libusb_src_SOURCE_DIR}/libusb/core.c
    ${libusb_src_SOURCE_DIR}/libusb/descriptor.c
    ${libusb_src_SOURCE_DIR}/libusb/hotplug.c
    ${libusb_src_SOURCE_DIR}/libusb/io.c
    ${libusb_src_SOURCE_DIR}/libusb/sync.c
    ${libusb_src_SOURCE_DIR}/libusb/strerror.c
    ${libusb_src_SOURCE_DIR}/libusb/os/linux_usbfs.c
    ${libusb_src_SOURCE_DIR}/libusb/os/events_posix.c
    ${libusb_src_SOURCE_DIR}/libusb/os/threads_posix.c
    ${libusb_src_SOURCE_DIR}/libusb/os/linux_netlink.c
)

target_include_directories(usb PUBLIC
    # android/config.h is the official Android build config provided by libusb
    ${libusb_src_SOURCE_DIR}/android
    ${libusb_src_SOURCE_DIR}/libusb
)

target_compile_options(usb PRIVATE
    -fvisibility=hidden
    -Wno-error
    -Wno-unused-result
)

target_link_libraries(usb PRIVATE log)

# Create the LibUSB::LibUSB alias expected by libuvc's FindLibUSB.cmake.
# Because the target already exists, libuvc's cmake guard
#   if (NOT TARGET LibUSB::LibUSB)
# will be skipped, preventing a failed pkg-config look-up.
add_library(LibUSB::LibUSB ALIAS usb)

# ─── libuvc ───────────────────────────────────────────────────────────────
FetchContent_Declare(
    libuvc_src
    GIT_REPOSITORY https://github.com/libuvc/libuvc.git
    GIT_TAG        047920bcdfb1dac42424c90de5cc77dfc9fba04d  # v0.0.7 + fixes
)
FetchContent_GetProperties(libuvc_src)
if(NOT libuvc_src_POPULATED)
    FetchContent_Populate(libuvc_src)
endif()

# Disable optional components; JPEG not available → frame-mjpeg.c excluded automatically.
set(BUILD_EXAMPLE OFF CACHE BOOL "" FORCE)
set(BUILD_TEST    OFF CACHE BOOL "" FORCE)
set(CMAKE_BUILD_TARGET "Static" CACHE STRING "" FORCE)

add_subdirectory(
    ${libuvc_src_SOURCE_DIR}
    ${libuvc_src_BINARY_DIR}
    EXCLUDE_FROM_ALL
)

# ─── JNI shared library ───────────────────────────────────────────────────
add_library(thermalcamera SHARED native-lib.cpp)

target_link_libraries(thermalcamera
    PRIVATE
        uvc_static
        usb
        log
        android
)

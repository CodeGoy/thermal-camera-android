#include <jni.h>
#include <string>
#include <atomic>
#include <mutex>
#include <cmath>
#include <android/log.h>
#include <libusb.h>
#include "libuvc/libuvc.h"

#define LOG_TAG "ThermalCamera"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Frame dimensions for Infiray P2 / Topdon TC001
static constexpr int FRAME_WIDTH = 256;
static constexpr int FRAME_HEIGHT = 384;
static constexpr int IMAGE_HEIGHT = 192;   // Top half: visual image
static constexpr int THERMAL_HEIGHT = 192; // Bottom half: thermal data

// Global state
static uvc_context_t *g_ctx = nullptr;
static uvc_device_handle_t *g_devh = nullptr;
static std::atomic<bool> g_streaming{false};
static std::mutex g_mutex;

// Java callback references
static JavaVM *g_jvm = nullptr;
static jobject g_callback_obj = nullptr;
static jmethodID g_callback_method = nullptr;

// Convert raw thermal value to Celsius
static inline float rawToCelsius(uint16_t raw) {
    return (raw / 64.0f) - 273.15f;
}

// Frame counter for debug logging
static int g_frame_count = 0;

// Frame callback from libuvc
static void frameCallback(uvc_frame_t *frame, void *user_ptr) {
    g_frame_count++;
    if (g_frame_count % 25 == 1) {  // Log every ~1 second at 25fps
        LOGI("Frame received #%d: %zu bytes, %dx%d", g_frame_count,
             frame ? frame->data_bytes : 0,
             frame ? (int)frame->width : 0,
             frame ? (int)frame->height : 0);
    }

    if (!frame || !frame->data || frame->data_bytes == 0) {
        LOGE("Invalid frame received");
        return;
    }

    // Expected: 256x384 YUYV = 256 * 384 * 2 bytes
    size_t expected_size = FRAME_WIDTH * FRAME_HEIGHT * 2;
    if (frame->data_bytes < expected_size) {
        LOGE("Frame too small: %zu < %zu", frame->data_bytes, expected_size);
        return;
    }

    uint8_t *data = static_cast<uint8_t*>(frame->data);
    size_t stride = FRAME_WIDTH * 2; // YUYV: 2 bytes per pixel

    // Allocate grayscale buffer for top half (visual image)
    // We extract Y channel from YUYV
    uint8_t grayscale[FRAME_WIDTH * IMAGE_HEIGHT];

    // Extract grayscale from top 192 rows (visual image)
    for (int y = 0; y < IMAGE_HEIGHT; y++) {
        for (int x = 0; x < FRAME_WIDTH; x++) {
            // YUYV format: Y0 U Y1 V - we just need Y values
            grayscale[y * FRAME_WIDTH + x] = data[y * stride + x * 2];
        }
    }

    // Process thermal data from bottom 192 rows
    // Each pixel is 16-bit LE stored in YUYV as: lo byte in Y, hi byte in U/V
    float minTemp = 1000.0f, maxTemp = -1000.0f, sumTemp = 0.0f;
    int minRow = 0, minCol = 0, maxRow = 0, maxCol = 0;

    uint8_t *thermal_data = data + (IMAGE_HEIGHT * stride);

    for (int y = 0; y < THERMAL_HEIGHT; y++) {
        for (int x = 0; x < FRAME_WIDTH; x++) {
            // Extract 16-bit LE value from YUYV
            // Low byte is in Y position, high byte follows
            uint8_t lo = thermal_data[y * stride + x * 2];
            uint8_t hi = thermal_data[y * stride + x * 2 + 1];
            uint16_t raw = lo | (hi << 8);
            float temp = rawToCelsius(raw);

            sumTemp += temp;

            if (temp < minTemp) {
                minTemp = temp;
                minRow = y;
                minCol = x;
            }
            if (temp > maxTemp) {
                maxTemp = temp;
                maxRow = y;
                maxCol = x;
            }
        }
    }

    float avgTemp = sumTemp / (THERMAL_HEIGHT * FRAME_WIDTH);

    // Center temperature (middle of thermal region)
    int centerY = THERMAL_HEIGHT / 2;
    int centerX = FRAME_WIDTH / 2;
    uint8_t clo = thermal_data[centerY * stride + centerX * 2];
    uint8_t chi = thermal_data[centerY * stride + centerX * 2 + 1];
    uint16_t centerRaw = clo | (chi << 8);
    float centerTemp = rawToCelsius(centerRaw);

    // Call Java callback
    if (g_jvm && g_callback_obj && g_callback_method) {
        JNIEnv *env;
        bool attached = false;
        int getEnvResult = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);

        if (getEnvResult == JNI_EDETACHED) {
            if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attached = true;
            } else {
                LOGE("Failed to attach thread to JVM");
                return;
            }
        } else if (getEnvResult != JNI_OK) {
            LOGE("Failed to get JNI environment");
            return;
        }

        // Create byte array for grayscale image
        jbyteArray imageArray = env->NewByteArray(FRAME_WIDTH * IMAGE_HEIGHT);
        env->SetByteArrayRegion(imageArray, 0, FRAME_WIDTH * IMAGE_HEIGHT,
                                reinterpret_cast<jbyte*>(grayscale));

        // Call: onFrame(byte[] image, float centerTemp, float minTemp, float maxTemp,
        //               float avgTemp, int minRow, int minCol, int maxRow, int maxCol)
        env->CallVoidMethod(g_callback_obj, g_callback_method,
                           imageArray,
                           centerTemp, minTemp, maxTemp, avgTemp,
                           minRow, minCol, maxRow, maxCol);

        env->DeleteLocalRef(imageArray);

        if (attached) {
            g_jvm->DetachCurrentThread();
        }
    }
}

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_jvm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_thermalcamera_MainActivity_getLibUvcVersion(
        JNIEnv *env,
        jobject /* this */) {
    const char *version = LIBUVC_VERSION_STR;
    LOGI("libuvc version: %s", version);
    return env->NewStringUTF(version);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_thermalcamera_MainActivity_initUvc(
        JNIEnv *env,
        jobject /* this */) {
    uvc_context_t *ctx = nullptr;
    uvc_error_t res = uvc_init(&ctx, nullptr);
    if (res < 0) {
        LOGE("uvc_init failed: %s", uvc_strerror(res));
        return JNI_FALSE;
    }
    LOGI("uvc_init succeeded");
    uvc_exit(ctx);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_thermalcamera_ThermalCamera_nativeOpen(
        JNIEnv *env,
        jobject /* this */,
        jint fd,
        jint vendorId,
        jint productId) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_devh != nullptr) {
        LOGE("Camera already open");
        return JNI_FALSE;
    }

    LOGI("Opening camera with fd=%d, VID=%04x, PID=%04x", fd, vendorId, productId);

    // On Android, we must set LIBUSB_OPTION_NO_DEVICE_DISCOVERY before init
    // because Android restricts direct access to /dev/bus/usb.
    // Setting on NULL context affects all future contexts (global default).
    int usb_res = libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    if (usb_res != LIBUSB_SUCCESS && usb_res != LIBUSB_ERROR_NOT_SUPPORTED) {
        LOGE("libusb_set_option(NO_DEVICE_DISCOVERY) failed: %s", libusb_strerror((libusb_error)usb_res));
        // Continue anyway - older libusb versions don't support this
    } else {
        LOGI("libusb_set_option(NO_DEVICE_DISCOVERY) success");
    }

    // Initialize UVC context - pass NULL so libuvc creates its own libusb context
    // and starts the event handler thread (required for receiving frames)
    uvc_error_t res = uvc_init(&g_ctx, nullptr);
    if (res < 0) {
        LOGE("uvc_init failed: %s", uvc_strerror(res));
        return JNI_FALSE;
    }
    LOGI("uvc_init succeeded");

    // Wrap the Android USB file descriptor directly with libuvc
    // uvc_wrap takes: file descriptor, context, output device handle
    res = uvc_wrap(fd, g_ctx, &g_devh);
    if (res < 0) {
        LOGE("uvc_wrap failed: %s", uvc_strerror(res));
        uvc_exit(g_ctx);
        g_ctx = nullptr;
        return JNI_FALSE;
    }

    LOGI("Camera opened successfully");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_thermalcamera_ThermalCamera_nativeStartStream(
        JNIEnv *env,
        jobject thiz,
        jobject callback) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_devh == nullptr) {
        LOGE("Camera not open");
        return JNI_FALSE;
    }

    if (g_streaming) {
        LOGE("Already streaming");
        return JNI_FALSE;
    }

    // Store callback reference
    g_callback_obj = env->NewGlobalRef(callback);
    jclass callbackClass = env->GetObjectClass(callback);
    // Signature: (byte[] image, float centerTemp, float minTemp, float maxTemp,
    //             float avgTemp, int minRow, int minCol, int maxRow, int maxCol)V
    g_callback_method = env->GetMethodID(callbackClass, "onFrame",
            "([BFFFFIIII)V");

    if (g_callback_method == nullptr) {
        LOGE("Failed to find onFrame method");
        env->DeleteGlobalRef(g_callback_obj);
        g_callback_obj = nullptr;
        return JNI_FALSE;
    }

    // Reset frame counter
    g_frame_count = 0;

    // Configure stream: 256x384 YUYV @ 25fps
    uvc_stream_ctrl_t ctrl;
    uvc_error_t res = uvc_get_stream_ctrl_format_size(
            g_devh, &ctrl,
            UVC_FRAME_FORMAT_YUYV,
            FRAME_WIDTH, FRAME_HEIGHT, 25);

    if (res < 0) {
        LOGE("uvc_get_stream_ctrl_format_size failed: %s", uvc_strerror(res));
        // Try without specific fps
        res = uvc_get_stream_ctrl_format_size(
                g_devh, &ctrl,
                UVC_FRAME_FORMAT_YUYV,
                FRAME_WIDTH, FRAME_HEIGHT, 0);
        if (res < 0) {
            LOGE("uvc_get_stream_ctrl_format_size (fps=0) failed: %s", uvc_strerror(res));
            env->DeleteGlobalRef(g_callback_obj);
            g_callback_obj = nullptr;
            return JNI_FALSE;
        }
    }

    LOGI("Stream ctrl: format=%d, frame=%d, interval=%u",
         ctrl.bFormatIndex, ctrl.bFrameIndex, ctrl.dwFrameInterval);

    // Start streaming
    res = uvc_start_streaming(g_devh, &ctrl, frameCallback, nullptr, 0);
    if (res < 0) {
        LOGE("uvc_start_streaming failed: %s", uvc_strerror(res));
        env->DeleteGlobalRef(g_callback_obj);
        g_callback_obj = nullptr;
        return JNI_FALSE;
    }

    g_streaming = true;
    LOGI("Streaming started");
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_thermalcamera_ThermalCamera_nativeStopStream(
        JNIEnv *env,
        jobject /* this */) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_streaming || g_devh == nullptr) {
        return;
    }

    uvc_stop_streaming(g_devh);
    g_streaming = false;

    if (g_callback_obj) {
        env->DeleteGlobalRef(g_callback_obj);
        g_callback_obj = nullptr;
    }
    g_callback_method = nullptr;

    LOGI("Streaming stopped");
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_thermalcamera_ThermalCamera_nativeClose(
        JNIEnv *env,
        jobject /* this */) {

    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_streaming) {
        uvc_stop_streaming(g_devh);
        g_streaming = false;
    }

    if (g_callback_obj) {
        env->DeleteGlobalRef(g_callback_obj);
        g_callback_obj = nullptr;
    }
    g_callback_method = nullptr;

    if (g_devh) {
        uvc_close(g_devh);
        g_devh = nullptr;
    }

    if (g_ctx) {
        uvc_exit(g_ctx);
        g_ctx = nullptr;
    }

    LOGI("Camera closed");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_thermalcamera_ThermalCamera_nativeIsStreaming(
        JNIEnv *env,
        jobject /* this */) {
    return g_streaming ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_thermalcamera_ThermalCamera_getFrameWidth(
        JNIEnv *env,
        jobject /* this */) {
    return FRAME_WIDTH;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_thermalcamera_ThermalCamera_getImageHeight(
        JNIEnv *env,
        jobject /* this */) {
    return IMAGE_HEIGHT;
}

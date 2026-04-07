#include <jni.h>
#include <string>
#include <android/log.h>
#include "libuvc/libuvc.h"

#define LOG_TAG "ThermalCamera"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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

package com.example.thermalcamera;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.util.Log;

/**
 * Manages the thermal camera lifecycle via libuvc JNI bindings.
 * Handles opening, streaming, and closing the USB camera.
 */
public class ThermalCamera {
    private static final String TAG = "ThermalCamera";

    private UsbDeviceConnection connection;
    private boolean isOpen = false;

    /**
     * Callback interface for receiving thermal frames.
     */
    public interface FrameCallback {
        /**
         * Called when a new frame is available.
         * Note: This is called from a native thread - post to main thread for UI updates.
         */
        void onThermalFrame(ThermalData data);
    }

    private FrameCallback frameCallback;

    /**
     * Opens the thermal camera using the provided USB connection.
     *
     * @param device The USB device
     * @param connection The opened USB connection (must have permission)
     * @return true if the camera was opened successfully
     */
    public boolean open(UsbDevice device, UsbDeviceConnection connection) {
        if (isOpen) {
            Log.w(TAG, "Camera already open");
            return false;
        }

        this.connection = connection;
        int fd = connection.getFileDescriptor();
        int vendorId = device.getVendorId();
        int productId = device.getProductId();

        Log.i(TAG, String.format("Opening camera: fd=%d, VID=%04x, PID=%04x",
                fd, vendorId, productId));

        boolean success = nativeOpen(fd, vendorId, productId);
        if (success) {
            isOpen = true;
            Log.i(TAG, "Camera opened successfully");
        } else {
            Log.e(TAG, "Failed to open camera");
        }
        return success;
    }

    /**
     * Starts streaming frames from the camera.
     *
     * @param callback The callback to receive frames
     * @return true if streaming started successfully
     */
    public boolean startStream(FrameCallback callback) {
        if (!isOpen) {
            Log.e(TAG, "Camera not open");
            return false;
        }

        this.frameCallback = callback;
        return nativeStartStream(new NativeFrameCallback());
    }

    /**
     * Stops the camera stream.
     */
    public void stopStream() {
        nativeStopStream();
        frameCallback = null;
    }

    /**
     * Closes the camera and releases resources.
     */
    public void close() {
        nativeClose();
        isOpen = false;
        frameCallback = null;

        if (connection != null) {
            connection.close();
            connection = null;
        }
    }

    /**
     * Returns whether the camera is currently streaming.
     */
    public boolean isStreaming() {
        return nativeIsStreaming();
    }

    /**
     * Returns whether the camera is open.
     */
    public boolean isOpen() {
        return isOpen;
    }

    /**
     * Internal callback class that receives frames from native code.
     * JNI calls onFrame() from the libuvc streaming thread.
     */
    private class NativeFrameCallback {
        @SuppressWarnings("unused") // Called from JNI
        public void onFrame(byte[] imageData,
                           float centerTemp, float minTemp, float maxTemp, float avgTemp,
                           int minRow, int minCol, int maxRow, int maxCol) {
            if (frameCallback != null) {
                ThermalData data = new ThermalData(
                        imageData,
                        centerTemp, minTemp, maxTemp, avgTemp,
                        minRow, minCol, maxRow, maxCol
                );
                frameCallback.onThermalFrame(data);
            }
        }
    }

    // Native methods
    private native boolean nativeOpen(int fd, int vendorId, int productId);
    private native boolean nativeStartStream(Object callback);
    private native void nativeStopStream();
    private native void nativeClose();
    private native boolean nativeIsStreaming();
    public native int getFrameWidth();
    public native int getImageHeight();
}

package com.example.thermalcamera;

import androidx.appcompat.app.AppCompatActivity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "ThermalCamera";
    private static final String ACTION_USB_PERMISSION = "com.example.thermalcamera.USB_PERMISSION";

    private UsbManager usbManager;
    private TextView statusText;

    static {
        System.loadLibrary("thermalcamera");
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        if (device != null) {
                            Log.i(TAG, "USB permission granted for device: " + device.getDeviceName());
                            onUsbPermissionGranted(device);
                        }
                    } else {
                        Log.w(TAG, "USB permission denied for device: " + device);
                        updateStatus("USB permission denied");
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (device != null) {
                    Log.i(TAG, "USB device attached: " + device.getDeviceName());
                    requestUsbPermission(device);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                Log.i(TAG, "USB device detached: " + device);
                updateStatus("USB device detached");
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.sample_text);
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);

        String version = getLibUvcVersion();
        boolean uvcInitOk = initUvc();
        Log.i(TAG, "libuvc version: " + version + ", init test: " + (uvcInitOk ? "OK" : "FAILED"));
        updateStatus("libuvc " + version + " | init: " + (uvcInitOk ? "OK" : "FAILED"));

        registerUsbReceiver();
        checkConnectedDevices();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(usbReceiver);
    }

    private void registerUsbReceiver() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
    }

    private void checkConnectedDevices() {
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            Log.i(TAG, "Found USB device: " + device.getDeviceName() +
                    " (VID: " + device.getVendorId() + ", PID: " + device.getProductId() + ")");
            requestUsbPermission(device);
        }
    }

    private void requestUsbPermission(UsbDevice device) {
        if (usbManager.hasPermission(device)) {
            onUsbPermissionGranted(device);
        } else {
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? PendingIntent.FLAG_MUTABLE
                    : 0;
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                    this, 0, new Intent(ACTION_USB_PERMISSION), flags);
            usbManager.requestPermission(device, permissionIntent);
            updateStatus("Requesting USB permission...");
        }
    }

    private void onUsbPermissionGranted(UsbDevice device) {
        updateStatus("USB ready: " + device.getDeviceName());
    }

    private void updateStatus(String status) {
        runOnUiThread(() -> statusText.setText(status));
    }

    /** Returns the libuvc version string (implemented in native-lib.cpp). */
    public native String getLibUvcVersion();

    /** Initialises a libuvc context (implemented in native-lib.cpp). */
    public native boolean initUvc();
}

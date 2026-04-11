package com.breyt.thermalcamera;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.PendingIntent;
import android.content.pm.PackageManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements ThermalCamera.FrameCallback {

    private static final String TAG = "ThermalCamera";
    private static final String ACTION_USB_PERMISSION = "com.breyt.thermalcamera.USB_PERMISSION";
    private static final String PREFS_NAME = "ThermalCameraPrefs";
    private static final String PREF_CONTRAST = "contrast";
    private static final String PREF_COLORMAP = "colormap";
    private static final String PREF_ROTATION = "rotation";
    private static final String PREF_MIRRORED = "mirrored";
    private static final String PREF_ROUNDING = "rounding";

    private UsbManager usbManager;
    private TextView statusText;
    private ThermalView thermalView;

    // Button state indicator views
    private TextView txtRotation;
    private View colormapPreview;
    private ImageButton btnMirror;

    private ThermalCamera thermalCamera;
    private UsbDevice currentDevice;
    private UsbDevice pendingDevice;  // Device waiting for permission retry
    private int currentRoundingMode = ThermalCamera.ROUNDING_NONE;

    // Track if usbReceiver is registered
    private boolean usbReceiverRegistered = false;
    // Track pending permission request to avoid duplicates (Android 14+ rate-limits these)
    private String pendingPermissionDeviceName = null;
    // Track device waiting for camera permission
    private UsbDevice deviceAwaitingCameraPermission = null;

    // Camera permission launcher - required for UVC devices on Android 9+
    private final ActivityResultLauncher<String> cameraPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    Log.i(TAG, "Camera permission granted");
                    // Now we can request USB permission
                    if (deviceAwaitingCameraPermission != null) {
                        requestUsbPermissionInternal(deviceAwaitingCameraPermission);
                        deviceAwaitingCameraPermission = null;
                    } else {
                        checkConnectedDevices();
                    }
                } else {
                    Log.w(TAG, "Camera permission denied - required for USB cameras on Android 9+");
                    updateStatus("Camera permission required for USB cameras");
                }
            });

    static {
        System.loadLibrary("thermalcamera");
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "BroadcastReceiver onReceive: action=" + action);
            if (ACTION_USB_PERMISSION.equals(action)) {
                synchronized (this) {
                    UsbDevice device = getUsbDevice(intent);
                    // Clear pending permission tracking
                    if (device != null && device.getDeviceName().equals(pendingPermissionDeviceName)) {
                        pendingPermissionDeviceName = null;
                    }
                    boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    Log.d(TAG, "USB permission result: granted=" + granted + ", device=" + device);
                    if (intent.getExtras() != null) {
                        for (String key : intent.getExtras().keySet()) {
                            Object value = intent.getExtras().get(key);
                            Log.d(TAG, "Extra: " + key + " = " + value);
                        }
                    }
                    if (granted) {
                        if (device != null) {
                            Log.i(TAG, "USB permission granted for device: " + device.getDeviceName());
                            openCamera(device);
                        }
                    } else {
                        // On Android 15+, the broadcast may say denied but hasPermission() might still work
                        // This happens when the device matches device_filter.xml
                        if (device != null && usbManager.hasPermission(device)) {
                            Log.i(TAG, "Broadcast said denied but hasPermission() is true - opening camera");
                            openCamera(device);
                        } else {
                            Log.w(TAG, "USB permission denied for device: " + device);
                            // On Android 14+, permission may be auto-denied if not user-initiated
                            // Suggest reconnecting the device to trigger the system dialog
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                                updateStatus("Permission denied - unplug & replug USB device");
                            } else {
                                updateStatus("USB permission denied - tap to retry");
                            }
                            // Store device for manual retry
                            pendingDevice = device;
                        }
                    }
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                UsbDevice device = getUsbDevice(intent);
                Log.d(TAG, "USB_DEVICE_ATTACHED: device=" + device);
                if (device != null) {
                    Log.i(TAG, "USB device attached: " + device.getDeviceName());
                    requestUsbPermission(device);
                }
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice device = getUsbDevice(intent);
                Log.d(TAG, "USB_DEVICE_DETACHED: device=" + device);
                Log.i(TAG, "USB device detached: " + device);
                // Compare by device name since UsbDevice.equals() may fail after detach
                if (device != null && currentDevice != null &&
                        device.getDeviceName().equals(currentDevice.getDeviceName())) {
                    closeCamera();
                    updateStatus("Camera disconnected");
                } else if (thermalCamera.isOpen()) {
                    // Fallback: close if any camera is open and a device was detached
                    Log.w(TAG, "Device mismatch on detach, closing camera anyway");
                    closeCamera();
                    updateStatus("Camera disconnected");
                }
            }
        }
    };

    @SuppressWarnings("deprecation")
    private UsbDevice getUsbDevice(Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        } else {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep screen on while viewing thermal camera
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.status_text);
        thermalView = findViewById(R.id.thermal_view);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        thermalCamera = new ThermalCamera();

        // Load saved settings
        loadSettings();

        // Setup button handlers
        setupButtons();

        // Tap status text to retry permission
        statusText.setOnClickListener(v -> {
            // Clear pending state so retry is treated as fresh user-initiated request
            pendingPermissionDeviceName = null;
            if (pendingDevice != null) {
                Log.i(TAG, "Retrying permission for pending device (user-initiated)");
                requestUsbPermission(pendingDevice);
            } else {
                // Re-scan for devices
                checkConnectedDevices();
            }
        });

        String version = getLibUvcVersion();
        Log.i(TAG, "libuvc version: " + version);
        // Note: uvc_init() test is skipped on Android because libusb can't enumerate
        // devices directly. We use uvc_wrap() with Android USB file descriptors instead.

        registerUsbReceiver();

        // Check if launched via USB device attachment intent (Android auto-grants permission)
        handleUsbAttachmentIntent(getIntent());

        // Also check for already-connected devices
        checkConnectedDevices();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Handle USB device attachment when app is already running
        handleUsbAttachmentIntent(intent);
    }

    private void handleUsbAttachmentIntent(Intent intent) {
        if (intent != null && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) {
            UsbDevice device = getUsbDevice(intent);
            if (device != null) {
                Log.i(TAG, "App launched/resumed via USB attachment intent for: " + device.getDeviceName());
                // When launched via the system USB dialog, permission is auto-granted
                if (usbManager.hasPermission(device)) {
                    Log.i(TAG, "Permission already granted via system USB dialog");
                    openCamera(device);
                } else {
                    // Shouldn't happen, but request just in case
                    requestUsbPermission(device);
                }
            }
        }
    }

    private void setupButtons() {
        // Initialize button state indicator views
        txtRotation = findViewById(R.id.txt_rotation);
        colormapPreview = findViewById(R.id.colormap_preview);
        btnMirror = findViewById(R.id.btn_mirror);

        // Colormap button - cycle through colormaps
        findViewById(R.id.btn_colormap).setOnClickListener(v -> {
            thermalView.nextColormap();
            updateColormapPreview();
            saveSettings();
        });

        // Rotate button - rotate 90 degrees
        findViewById(R.id.btn_rotate).setOnClickListener(v -> {
            thermalView.rotate();
            updateRotationLabel();
            saveSettings();
        });

        // Mirror button - toggle horizontal flip
        btnMirror.setOnClickListener(v -> {
            thermalView.toggleMirror();
            updateMirrorButton();
            saveSettings();
        });

        // Screenshot button
        findViewById(R.id.btn_screenshot).setOnClickListener(v -> {
            takeScreenshot();
        });

        // Overflow menu button
        ImageButton btnOverflow = findViewById(R.id.btn_overflow);
        btnOverflow.setOnClickListener(v -> showOverflowMenu(btnOverflow));

        // Initialize button states from loaded settings
        updateButtonStates();
    }

    private void showOverflowMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenuInflater().inflate(R.menu.overflow_menu, popup.getMenu());

        // Update rounding checkmarks
        popup.getMenu().findItem(R.id.menu_rounding_none).setChecked(
                currentRoundingMode == ThermalCamera.ROUNDING_NONE);
        popup.getMenu().findItem(R.id.menu_rounding_0_2).setChecked(
                currentRoundingMode == ThermalCamera.ROUNDING_0_2);
        popup.getMenu().findItem(R.id.menu_rounding_0_5).setChecked(
                currentRoundingMode == ThermalCamera.ROUNDING_0_5);

        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            boolean keepOpen = false;

            if (id == R.id.menu_contrast_down) {
                thermalView.adjustContrast(-0.2f);
                saveSettings();
                keepOpen = true;
            } else if (id == R.id.menu_contrast_up) {
                thermalView.adjustContrast(0.2f);
                saveSettings();
                keepOpen = true;
            } else if (id == R.id.menu_rounding_none) {
                currentRoundingMode = ThermalCamera.ROUNDING_NONE;
                thermalCamera.setRoundingMode(currentRoundingMode);
                saveSettings();
                keepOpen = true;
            } else if (id == R.id.menu_rounding_0_2) {
                currentRoundingMode = ThermalCamera.ROUNDING_0_2;
                thermalCamera.setRoundingMode(currentRoundingMode);
                saveSettings();
                keepOpen = true;
            } else if (id == R.id.menu_rounding_0_5) {
                currentRoundingMode = ThermalCamera.ROUNDING_0_5;
                thermalCamera.setRoundingMode(currentRoundingMode);
                saveSettings();
                keepOpen = true;
            }

            if (keepOpen) {
                // Re-show the menu to keep it open
                anchor.post(() -> showOverflowMenu(anchor));
            }
            return true;
        });

        popup.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // If camera is open but not streaming, start streaming
        if (thermalCamera.isOpen() && !thermalCamera.isStreaming()) {
            thermalCamera.startStream(this);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Stop streaming but keep camera open
        if (thermalCamera.isStreaming()) {
            thermalCamera.stopStream();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        closeCamera();
        if (usbReceiverRegistered) {
            try {
                unregisterReceiver(usbReceiver);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "usbReceiver was not registered or already unregistered");
            }
            usbReceiverRegistered = false;
        }
    }

    private void registerUsbReceiver() {
        if (!usbReceiverRegistered) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_USB_PERMISSION);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // RECEIVER_EXPORTED is required to receive USB permission results from the system
                registerReceiver(usbReceiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                registerReceiver(usbReceiver, filter);
            }
            usbReceiverRegistered = true;
        }
    }

    private void checkConnectedDevices() {
        for (UsbDevice device : usbManager.getDeviceList().values()) {
            Log.i(TAG, String.format("Found USB device: %s (VID: %04x, PID: %04x)",
                    device.getDeviceName(), device.getVendorId(), device.getProductId()));
            requestUsbPermission(device);
            break; // Only handle first device
        }
    }

    private void requestUsbPermission(UsbDevice device) {
        // On Android 9+, USB UVC cameras require CAMERA permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            Log.i(TAG, "Requesting CAMERA permission (required for USB UVC on Android 9+)");
            deviceAwaitingCameraPermission = device;
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA);
            updateStatus("Camera permission required...");
            return;
        }
        requestUsbPermissionInternal(device);
    }

    private void requestUsbPermissionInternal(UsbDevice device) {
        // Ensure receiver is registered before requesting permission
        if (!usbReceiverRegistered) {
            registerUsbReceiver();
        }
        if (usbManager.hasPermission(device)) {
            openCamera(device);
        } else {
            // Avoid duplicate permission requests - Android 14+ rate-limits and auto-denies these
            String deviceName = device.getDeviceName();
            if (deviceName.equals(pendingPermissionDeviceName)) {
                Log.d(TAG, "Permission request already pending for: " + deviceName);
                return;
            }
            pendingPermissionDeviceName = deviceName;

            // For Android 14+ (API 34+), implicit intents with FLAG_MUTABLE are disallowed
            // We must make the intent explicit by setting the package
            Intent intent = new Intent(ACTION_USB_PERMISSION);
            intent.setPackage(getPackageName());

            // FLAG_MUTABLE is required for USB permissions (system adds extras)
            // FLAG_UPDATE_CURRENT ensures the PendingIntent is updated with correct device info
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                    : PendingIntent.FLAG_UPDATE_CURRENT;
            // Use device ID as request code for unique PendingIntents per device
            int requestCode = device.getDeviceId();
            PendingIntent permissionIntent = PendingIntent.getBroadcast(
                    this, requestCode, intent, flags);
            usbManager.requestPermission(device, permissionIntent);
            updateStatus("Requesting USB permission...");
        }
    }

    private void openCamera(UsbDevice device) {
        if (thermalCamera.isOpen()) {
            Log.i(TAG, "Closing existing camera before opening new one");
            updateStatus("Reconnecting...");
            closeCamera();
        }

        updateStatus("Connecting to camera...");

        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            Log.e(TAG, "Failed to open USB device connection");
            updateStatus("Failed to open USB device");
            return;
        }

        currentDevice = device;
        updateStatus("Initializing camera...");

        if (thermalCamera.open(device, connection)) {
            updateStatus("Starting stream...");
            if (thermalCamera.startStream(this)) {
                statusText.setVisibility(View.GONE);
                Log.i(TAG, "Camera streaming started");
            } else {
                updateStatus("Failed to start stream");
            }
        } else {
            updateStatus("Failed to open camera");
            connection.close();
        }
    }

    private void closeCamera() {
        thermalCamera.close();
        currentDevice = null;
        statusText.setVisibility(View.VISIBLE);
    }

    @Override
    public void onThermalFrame(ThermalData data) {
        // Called from native thread - ThermalView.setThermalData posts to UI thread
        thermalView.setThermalData(data);
    }

    private void updateStatus(String status) {
        runOnUiThread(() -> {
            statusText.setText(status);
            statusText.setVisibility(View.VISIBLE);
        });
    }

    private void takeScreenshot() {
        // Capture the thermal view
        thermalView.setDrawingCacheEnabled(true);
        Bitmap bitmap = Bitmap.createBitmap(thermalView.getDrawingCache());
        thermalView.setDrawingCacheEnabled(false);

        // Generate filename with timestamp
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String filename = "thermal_" + timestamp + ".png";

        try {
            OutputStream out;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Use MediaStore for Android 10+
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ThermalCamera");

                Uri uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    throw new Exception("Failed to create media store entry");
                }
                out = getContentResolver().openOutputStream(uri);
            } else {
                // Legacy storage for older Android
                File dir = new File(Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_PICTURES), "ThermalCamera");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, filename);
                out = new FileOutputStream(file);
            }

            if (out != null) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                out.close();
                Toast.makeText(this, "Saved: " + filename, Toast.LENGTH_SHORT).show();
                Log.i(TAG, "Screenshot saved: " + filename);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to save screenshot", e);
            Toast.makeText(this, "Failed to save screenshot", Toast.LENGTH_SHORT).show();
        }
    }

    private void updateRotationLabel() {
        txtRotation.setText(thermalView.getImageRotation() + "°");
    }

    private void updateMirrorButton() {
        int tint = thermalView.isMirrored()
                ? ContextCompat.getColor(this, R.color.mirror_active)
                : Color.WHITE;
        btnMirror.setColorFilter(tint);
    }

    private void updateColormapPreview() {
        int[] colors = thermalView.getColormapColors();
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, colors);
        gradient.setCornerRadius(4f);
        colormapPreview.setBackground(gradient);
    }

    private void updateButtonStates() {
        updateRotationLabel();
        updateMirrorButton();
        updateColormapPreview();
    }

    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putFloat(PREF_CONTRAST, thermalView.getContrast());
        editor.putInt(PREF_COLORMAP, thermalView.getColormapIndex());
        editor.putInt(PREF_ROTATION, thermalView.getImageRotation());
        editor.putBoolean(PREF_MIRRORED, thermalView.isMirrored());
        editor.putInt(PREF_ROUNDING, currentRoundingMode);
        editor.apply();
    }

    private void loadSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        thermalView.setContrast(prefs.getFloat(PREF_CONTRAST, 1.0f));
        thermalView.setColormapIndex(prefs.getInt(PREF_COLORMAP, 0));
        thermalView.setRotation(prefs.getInt(PREF_ROTATION, 0));
        thermalView.setMirrored(prefs.getBoolean(PREF_MIRRORED, false));
        currentRoundingMode = prefs.getInt(PREF_ROUNDING, ThermalCamera.ROUNDING_NONE);
        thermalCamera.setRoundingMode(currentRoundingMode);
    }

    /** Returns the libuvc version string (implemented in native-lib.cpp). */
    public native String getLibUvcVersion();

    /** Initialises a libuvc context (implemented in native-lib.cpp). */
    public native boolean initUvc();
}

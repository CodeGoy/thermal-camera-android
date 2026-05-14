package com.breyt.thermalcamera;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.ConstraintSet;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.app.PendingIntent;
import android.content.pm.PackageManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.Configuration;
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
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ImageView;
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
    private static final String PREF_ROTATION_LOCK = "rotationLock";
    private static final String PREF_SHOW_SCALE = "showScale";
    private static final String PREF_SCALE_LOCK = "scaleLock";
    private static final String PREF_LOCKED_MIN_TEMP = "lockedMinTemp";
    private static final String PREF_LOCKED_MAX_TEMP = "lockedMaxTemp";

    private UsbManager usbManager;
    private TextView statusText;
    private ThermalView thermalView;
    private ConstraintLayout rootLayout;
    private LinearLayout controlBar;

    // Button state indicator views
    private TextView txtRotation;
    private View colormapPreview;
    private ImageButton btnMirror;
    private ImageView iconRotationLock;

    // Rotation lock - when enabled, image rotates with device orientation
    private boolean rotationLockEnabled = true;
    private int lastSystemRotation = -1;  // Tracks system display rotation (0, 90, 180, 270)

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
    // Track if we're currently in the process of opening a camera
    private volatile boolean isOpeningCamera = false;

    // Background thread for camera operations to avoid blocking UI
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

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
                            // Skip if already open/opening
                            if (thermalCamera.isOpen() || isOpeningCamera) {
                                Log.d(TAG, "Camera already open/opening, ignoring permission grant");
                                return;
                            }
                            Log.i(TAG, "USB permission granted for device: " + device.getDeviceName());
                            openCamera(device);
                        }
                    } else {
                        // On Android 15+, the broadcast may say denied but hasPermission() might still work
                        // This happens when the device matches device_filter.xml
                        if (device != null && usbManager.hasPermission(device)) {
                            // Skip if already open/opening
                            if (thermalCamera.isOpen() || isOpeningCamera) {
                                Log.d(TAG, "Camera already open/opening, ignoring permission grant");
                                return;
                            }
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
                    // Skip if camera is already open or being opened for this device
                    if (currentDevice != null &&
                            currentDevice.getDeviceName().equals(device.getDeviceName()) &&
                            (thermalCamera.isOpen() || isOpeningCamera)) {
                        Log.d(TAG, "Camera already open/opening for this device, ignoring attach broadcast");
                        return;
                    }
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

        // Hide status bar in landscape mode
        updateSystemBarsVisibility();

        statusText = findViewById(R.id.status_text);
        thermalView = findViewById(R.id.thermal_view);
        rootLayout = (ConstraintLayout) thermalView.getParent();
        controlBar = findViewById(R.id.control_bar);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        thermalCamera = new ThermalCamera();

        // Initialize background thread for camera operations
        cameraThread = new HandlerThread("CameraThread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        // Load saved settings
        loadSettings();

        // Setup button handlers
        setupButtons();

        // Initialize rotation tracking for rotation lock feature
        lastSystemRotation = getSystemRotationDegrees();

        // Set initial layout based on current orientation
        updateLayoutForOrientation(getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE);

        // Tap status text to retry connection
        statusText.setOnClickListener(v -> {
            // Clear pending state so retry is treated as fresh user-initiated request
            pendingPermissionDeviceName = null;
            if (pendingDevice != null) {
                // Verify device is still connected
                if (!usbManager.getDeviceList().containsKey(pendingDevice.getDeviceName())) {
                    Log.w(TAG, "Pending device no longer connected, scanning for devices");
                    pendingDevice = null;
                    checkConnectedDevices();
                    return;
                }
                Log.i(TAG, "Retrying connection for pending device (user-initiated)");
                // Check if we already have permission - if so, try opening directly
                if (usbManager.hasPermission(pendingDevice)) {
                    openCamera(pendingDevice);
                } else {
                    requestUsbPermission(pendingDevice);
                }
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
        boolean handledViaIntent = handleUsbAttachmentIntent(getIntent());

        // Only check for already-connected devices if we didn't already handle one via intent
        // This prevents race conditions when both paths try to open the same device
        if (!handledViaIntent) {
            checkConnectedDevices();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Handle USB device attachment when app is already running
        handleUsbAttachmentIntent(intent);
    }

    /**
     * Handles USB attachment intent. Returns true if a device was handled.
     */
    private boolean handleUsbAttachmentIntent(Intent intent) {
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
                return true;
            }
        }
        return false;
    }

    private void setupButtons() {
        // Initialize button state indicator views
        txtRotation = findViewById(R.id.txt_rotation);
        colormapPreview = findViewById(R.id.colormap_preview);
        btnMirror = findViewById(R.id.btn_mirror);
        iconRotationLock = findViewById(R.id.icon_rotation_lock);

        // Colormap button - cycle through colormaps
        findViewById(R.id.btn_colormap).setOnClickListener(v -> {
            thermalView.nextColormap();
            updateColormapPreview();
            saveSettings();
        });

        // Rotate button - rotate 90 degrees (manual rotation)
        ImageButton btnRotate = findViewById(R.id.btn_rotate);
        btnRotate.setOnClickListener(v -> {
            thermalView.rotate();
            updateRotationLabel();
            saveSettings();
        });

        // Long-press rotate button to toggle rotation lock
        btnRotate.setOnLongClickListener(v -> {
            rotationLockEnabled = !rotationLockEnabled;
            updateRotationLockIcon();
            saveSettings();
            String message = rotationLockEnabled ? "Rotation lock ON" : "Rotation lock OFF";
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            return true;
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

        // Update min/max points checkbox
        popup.getMenu().findItem(R.id.menu_show_scale).setChecked(thermalView.isShowingMinMaxPoints());

        // Update scale lock checkbox
        popup.getMenu().findItem(R.id.menu_scale_lock).setChecked(thermalView.isScaleLocked());

        // Only show scale adjust when scale is locked
        popup.getMenu().findItem(R.id.menu_scale_adjust).setVisible(thermalView.isScaleLocked());

        // update tempConversion menu item
        popup.getMenu().findItem(R.id.menu_temp_conversion).setChecked(thermalView.isTempConvert());
        
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
            } else if (id == R.id.menu_show_scale) {
                thermalView.setShowMinMaxPoints(!thermalView.isShowingMinMaxPoints());
                saveSettings();
                keepOpen = true;
            } else if (id == R.id.menu_scale_lock) {
                thermalView.setScaleLocked(!thermalView.isScaleLocked());
                saveSettings();
                keepOpen = true;
            } else if (id == R.id.menu_scale_adjust) {
                showScaleAdjustDialog();
            } else if (id == R.id.menu_camera_info) {
                showCameraInfoDialog();
            } else if (id == R.id.menu_temp_conversion) {
                thermalView.setTempConversion();
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
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Update system bars visibility for new orientation
        updateSystemBarsVisibility();
        // Rearrange control bar for new orientation
        updateLayoutForOrientation(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE);
        // Sync image rotation with system orientation change
        syncRotationWithSystem();
        // Update colormap preview gradient direction
        updateColormapPreview();
    }

    private int getSystemRotationDegrees() {
        int displayRotation = getWindowManager().getDefaultDisplay().getRotation();
        switch (displayRotation) {
            case android.view.Surface.ROTATION_90:  return 90;
            case android.view.Surface.ROTATION_180: return 180;
            case android.view.Surface.ROTATION_270: return 270;
            default: return 0;
        }
    }

    private void syncRotationWithSystem() {
        if (!rotationLockEnabled) {
            return;
        }

        int currentSystemRotation = getSystemRotationDegrees();

        // If there's a mismatch, sync the rotation
        if (currentSystemRotation != lastSystemRotation) {
            int rotationDelta = (currentSystemRotation - lastSystemRotation + 360) % 360;
            lastSystemRotation = currentSystemRotation;

            int currentImageRotation = thermalView.getImageRotation();
            int newImageRotation;
            if (thermalView.isMirrored()) {
                newImageRotation = (currentImageRotation - rotationDelta + 360) % 360;
            } else {
                newImageRotation = (currentImageRotation + rotationDelta) % 360;
            }
            thermalView.setRotation(newImageRotation);
            updateRotationLabel();
            saveSettings();
        }
    }

    private void updateLayoutForOrientation(boolean isLandscape) {
        ConstraintSet constraintSet = new ConstraintSet();
        constraintSet.clone(rootLayout);

        if (isLandscape) {
            // Control bar on the right side
            controlBar.setOrientation(LinearLayout.VERTICAL);
            controlBar.setPadding(dp(8), dp(8), dp(20), dp(8));

            // Update control bar constraints: right side, full height
            constraintSet.clear(R.id.control_bar, ConstraintSet.START);
            constraintSet.clear(R.id.control_bar, ConstraintSet.BOTTOM);
            constraintSet.connect(R.id.control_bar, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END);
            constraintSet.connect(R.id.control_bar, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP);
            constraintSet.connect(R.id.control_bar, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM);
            constraintSet.constrainWidth(R.id.control_bar, ConstraintSet.WRAP_CONTENT);
            constraintSet.constrainHeight(R.id.control_bar, ConstraintSet.MATCH_CONSTRAINT);

            // Update thermal view constraints: left of control bar
            constraintSet.connect(R.id.thermal_view, ConstraintSet.END, R.id.control_bar, ConstraintSet.START);
            constraintSet.connect(R.id.thermal_view, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM);

            // Update button margins for vertical layout
            updateButtonMarginsForOrientation(true);
        } else {
            // Control bar at the bottom
            controlBar.setOrientation(LinearLayout.HORIZONTAL);
            controlBar.setPadding(dp(8), dp(8), dp(8), dp(20));

            // Update control bar constraints: bottom, full width
            constraintSet.clear(R.id.control_bar, ConstraintSet.TOP);
            constraintSet.connect(R.id.control_bar, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START);
            constraintSet.connect(R.id.control_bar, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END);
            constraintSet.connect(R.id.control_bar, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM);
            constraintSet.constrainWidth(R.id.control_bar, ConstraintSet.MATCH_CONSTRAINT);
            constraintSet.constrainHeight(R.id.control_bar, ConstraintSet.WRAP_CONTENT);

            // Update thermal view constraints: above control bar
            constraintSet.connect(R.id.thermal_view, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END);
            constraintSet.connect(R.id.thermal_view, ConstraintSet.BOTTOM, R.id.control_bar, ConstraintSet.TOP);

            // Update button margins for horizontal layout
            updateButtonMarginsForOrientation(false);
        }

        constraintSet.applyTo(rootLayout);
    }

    private void updateButtonMarginsForOrientation(boolean isLandscape) {
        int marginDp = 12;
        for (int i = 0; i < controlBar.getChildCount(); i++) {
            View child = controlBar.getChildAt(i);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
            if (i < controlBar.getChildCount() - 1) {
                if (isLandscape) {
                    params.setMargins(0, 0, 0, dp(marginDp));
                    params.setMarginEnd(0);
                } else {
                    params.setMargins(0, 0, dp(marginDp), 0);
                    params.setMarginEnd(dp(marginDp));
                }
            } else {
                params.setMargins(0, 0, 0, 0);
                params.setMarginEnd(0);
            }
            child.setLayoutParams(params);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
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
        // Cancel any pending camera operations
        if (cameraHandler != null) {
            cameraHandler.removeCallbacksAndMessages(null);
        }
        closeCamera();
        // Shutdown camera thread
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
        }
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

        // Skip if we're already opening or have opened this device
        if (currentDevice != null &&
                currentDevice.getDeviceName().equals(device.getDeviceName()) &&
                (thermalCamera.isOpen() || isOpeningCamera)) {
            Log.d(TAG, "Camera already open/opening, skipping permission request");
            return;
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
        // Check if we're already opening this device
        if (isOpeningCamera) {
            Log.d(TAG, "Already opening a camera, ignoring duplicate request");
            return;
        }

        // Close existing camera first (on main thread for UI updates)
        if (thermalCamera.isOpen()) {
            Log.i(TAG, "Closing existing camera before opening new one");
            updateStatus("Reconnecting...");
        }
        closeCameraInternal();  // Don't reset isOpeningCamera

        // Set flag after close to avoid race
        isOpeningCamera = true;

        // Run camera open on background thread to avoid blocking UI
        cameraHandler.post(() -> openCameraWithRetry(device, 0));
    }

    private static final int MAX_OPEN_RETRIES = 2;
    private static final int OPEN_RETRY_DELAY_MS = 300;
    private static final int INITIAL_OPEN_DELAY_MS = 100;  // Wait for USB stack to settle

    private void openCameraWithRetry(UsbDevice device, int attempt) {
        // Add initial delay on first attempt to let USB stack settle after close
        if (attempt == 0) {
            try {
                Thread.sleep(INITIAL_OPEN_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                mainHandler.post(() -> {
                    isOpeningCamera = false;
                    updateStatus("Camera open interrupted");
                });
                return;
            }
        }

        // Verify device is still connected
        if (!usbManager.getDeviceList().containsKey(device.getDeviceName())) {
            Log.w(TAG, "Device disconnected before open attempt");
            mainHandler.post(() -> {
                isOpeningCamera = false;
                updateStatus("Camera disconnected");
            });
            return;
        }

        final int currentAttempt = attempt;
        mainHandler.post(() -> {
            if (currentAttempt > 0) {
                updateStatus("Retrying... (" + currentAttempt + "/" + MAX_OPEN_RETRIES + ")");
            } else {
                updateStatus("Connecting to camera...");
            }
        });
        Log.i(TAG, "Open attempt " + (attempt + 1) + "/" + (MAX_OPEN_RETRIES + 1));

        UsbDeviceConnection connection = usbManager.openDevice(device);
        if (connection == null) {
            Log.e(TAG, "Failed to open USB device connection");
            if (attempt < MAX_OPEN_RETRIES) {
                scheduleRetryOnBackground(device, attempt + 1);
            } else {
                mainHandler.post(() -> {
                    isOpeningCamera = false;
                    updateStatus("Failed to open USB device - tap to retry");
                    pendingDevice = device;
                });
            }
            return;
        }

        currentDevice = device;
        mainHandler.post(() -> updateStatus("Initializing camera..."));

        if (thermalCamera.open(device, connection)) {
            mainHandler.post(() -> updateStatus("Starting stream..."));
            if (thermalCamera.startStream(this)) {
                mainHandler.post(() -> {
                    statusText.setVisibility(View.GONE);
                    pendingDevice = null;
                    isOpeningCamera = false;
                });
                Log.i(TAG, "Camera streaming started");
            } else {
                mainHandler.post(() -> {
                    isOpeningCamera = false;
                    updateStatus("Failed to start stream - tap to retry");
                    pendingDevice = device;
                });
            }
        } else {
            Log.e(TAG, "Failed to open camera (attempt " + (attempt + 1) + ")");
            connection.close();
            currentDevice = null;

            if (attempt < MAX_OPEN_RETRIES) {
                scheduleRetryOnBackground(device, attempt + 1);
            } else {
                mainHandler.post(() -> {
                    isOpeningCamera = false;
                    updateStatus("Failed to open camera - tap to retry");
                    pendingDevice = device;
                });
            }
        }
    }

    private void scheduleRetryOnBackground(UsbDevice device, int attempt) {
        // Use cameraHandler to schedule retry with delay on background thread
        cameraHandler.postDelayed(() -> {
            // Verify device is still connected before retrying
            if (usbManager.getDeviceList().containsKey(device.getDeviceName())) {
                openCameraWithRetry(device, attempt);
            } else {
                Log.w(TAG, "Device disconnected, cancelling retry");
                mainHandler.post(() -> {
                    isOpeningCamera = false;
                    updateStatus("Camera disconnected");
                });
            }
        }, OPEN_RETRY_DELAY_MS);
    }

    private void closeCamera() {
        closeCameraInternal();
        isOpeningCamera = false;
    }

    private void closeCameraInternal() {
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

    private void showCameraInfoDialog() {
        String info = thermalCamera.getFormatInfo();
        if (info == null) {
            info = "Camera not connected";
        }

        new AlertDialog.Builder(this)
                .setTitle("Camera Info")
                .setMessage(info)
                .setPositiveButton("OK", null)
                .show();
    }

    private void showScaleAdjustDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_scale_adjust, null);
        EditText editMin = dialogView.findViewById(R.id.edit_min_temp);
        EditText editMax = dialogView.findViewById(R.id.edit_max_temp);

        // Pre-fill with current locked values
        editMin.setText(String.format(Locale.US, "%.1f", thermalView.getLockedMinTemp()));
        editMax.setText(String.format(Locale.US, "%.1f", thermalView.getLockedMaxTemp()));

        new AlertDialog.Builder(this)
                .setTitle("Adjust Scale Range")
                .setView(dialogView)
                .setPositiveButton("OK", (dialog, which) -> {
                    try {
                        float minTemp = Float.parseFloat(editMin.getText().toString());
                        float maxTemp = Float.parseFloat(editMax.getText().toString());

                        if (minTemp >= maxTemp) {
                            Toast.makeText(this, "Min must be less than max", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        thermalView.setLockedMinTemp(minTemp);
                        thermalView.setLockedMaxTemp(maxTemp);
                        saveSettings();
                    } catch (NumberFormatException e) {
                        Toast.makeText(this, "Invalid number format", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
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

    @SuppressWarnings("deprecation")
    private void updateSystemBarsVisibility() {
        boolean isLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        if (isLandscape) {
            // Hide status bar in landscape
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().getInsetsController().hide(WindowInsets.Type.statusBars());
                getWindow().getInsetsController().setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            }
        } else {
            // Show status bar in portrait
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                getWindow().getInsetsController().show(WindowInsets.Type.statusBars());
            } else {
                getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
            }
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
        // In landscape, the preview is horizontal (below icon); in portrait, it's vertical (right of icon)
        boolean isLandscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        GradientDrawable.Orientation orientation = isLandscape
                ? GradientDrawable.Orientation.LEFT_RIGHT
                : GradientDrawable.Orientation.TOP_BOTTOM;
        GradientDrawable gradient = new GradientDrawable(orientation, colors);
        gradient.setCornerRadius(2f);
        colormapPreview.setBackground(gradient);
    }

    private void updateRotationLockIcon() {
        iconRotationLock.setVisibility(rotationLockEnabled ? View.VISIBLE : View.GONE);
    }

    private void updateButtonStates() {
        updateRotationLabel();
        updateMirrorButton();
        updateColormapPreview();
        updateRotationLockIcon();
    }

    private void saveSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putFloat(PREF_CONTRAST, thermalView.getContrast());
        editor.putInt(PREF_COLORMAP, thermalView.getColormapIndex());
        editor.putInt(PREF_ROTATION, thermalView.getImageRotation());
        editor.putBoolean(PREF_MIRRORED, thermalView.isMirrored());
        editor.putInt(PREF_ROUNDING, currentRoundingMode);
        editor.putBoolean(PREF_ROTATION_LOCK, rotationLockEnabled);
        editor.putBoolean(PREF_SHOW_SCALE, thermalView.isShowingMinMaxPoints());
        editor.putBoolean(PREF_SCALE_LOCK, thermalView.isScaleLocked());
        editor.putFloat(PREF_LOCKED_MIN_TEMP, thermalView.getLockedMinTemp());
        editor.putFloat(PREF_LOCKED_MAX_TEMP, thermalView.getLockedMaxTemp());
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
        rotationLockEnabled = prefs.getBoolean(PREF_ROTATION_LOCK, true);
        thermalView.setShowMinMaxPoints(prefs.getBoolean(PREF_SHOW_SCALE, false));
        // Load scale lock settings - load min/max first, then locked state
        thermalView.setLockedMinTemp(prefs.getFloat(PREF_LOCKED_MIN_TEMP, 0f));
        thermalView.setLockedMaxTemp(prefs.getFloat(PREF_LOCKED_MAX_TEMP, 100f));
        if (prefs.getBoolean(PREF_SCALE_LOCK, false)) {
            // Directly set scaleLocked without capturing current temps (they're loaded above)
            thermalView.setScaleLocked(true);
        }
    }

    /** Returns the libuvc version string (implemented in native-lib.cpp). */
    public native String getLibUvcVersion();

    /** Initialises a libuvc context (implemented in native-lib.cpp). */
    public native boolean initUvc();
}

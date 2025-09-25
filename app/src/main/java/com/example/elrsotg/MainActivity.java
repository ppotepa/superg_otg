package com.example.elrsotg;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.*;
import android.hardware.input.InputManager;
import android.hardware.usb.*;
import android.os.*;
import android.view.*;
import android.widget.Button;
import android.widget.TextView;

public class MainActivity extends Activity implements InputManager.InputDeviceListener {
    static { System.loadLibrary("elrs_otg"); }

    private static final String ACTION_USB = "com.example.elrsotg.USB_PERMISSION";

    private UsbManager mgr;
    private PendingIntent permIntent;
    private BroadcastReceiver permRx;
    private InputManager input;

    // HUD
    private View statusSuperG, statusController;
    private TextView tvRoll, tvPitch, tvYaw, tvThr;
    
    // Debug UI
    private TextView tvGamepadDevice, tvGamepadButtons, tvGamepadAxes;
    private TextView tvControllerName;
    
    // TX Action Buttons
    private Button btnPair, btnIncSignal, btnDecSignal;
    private Button btnBind, btnReset, btnModelSelect;
    private TextView tvDeviceDetails;
    
    // TX/RX Graph
    private TxRxGraphView txRxGraph;
    private TextView tvTxRxStatus, tvPacketRate;
    
    // 3D View and Camera
    private TextView tv3DStatus, tvCameraStatus;
    private Rotation3DView rotation3DView;
    private TextureView cameraTextureView;
    private volatile boolean cameraConnected = false;
    
    // Safe Exit Mechanism
    private volatile boolean xButtonPressed = false;
    private volatile boolean bButtonPressed = false;
    private volatile long safeExitStartTime = 0;
    private static final long SAFE_EXIT_HOLD_DURATION = 3000; // 3 seconds
    private android.app.AlertDialog exitDialog;
    
    // Background Input Control
    private volatile boolean backgroundInputEnabled = true;

    // latest axes (for HUD)
    private float lastRoll=0f, lastPitch=0f, lastYaw=0f, lastThr=0f;
    
    // Device connection status
    private volatile boolean superGConnected = false;
    private volatile boolean controllerConnected = false;
    private volatile boolean usbPermissionRequested = false;
    private volatile boolean debugLoggingEnabled = false;

    // JNI
    public static native void nativeSetAxes(float roll, float pitch, float yaw, float thr);
    public static native void nativeStart();
    public static native void nativeStop();
    public static native boolean nativeSendCommand(String command);
    public static native void nativeStartTelemetry();
    public static native void nativeStopTelemetry();
    
    // Safety and arming controls
    public static native void nativeSetArmed(boolean armed);
    public static native boolean nativeIsArmed();
    public static native boolean nativeIsLinkOk();
    public static native void nativeSetSafetyOverride(boolean override);
    public static native void nativeEmergencyStop();
    public static native void registerTelemetryCallback();
    public static native void nativeSetDebugLogging(boolean enabled);

    private int controllerCheckCounter = 0;

    private static final long DEVICE_MONITOR_INTERVAL_MS = 1000L;
    private HandlerThread deviceMonitorThread;
    private Handler deviceMonitorHandler;

    private final Runnable deviceMonitorTask = new Runnable() {
        @Override public void run() {
            boolean superG = ensureSuperGConnected();
            updateSuperGStatus(superG);

            // Check if controller is still connected AND validate it's active
            if (controllerConnected && !validateControllerActive()) {
                android.util.Log.d("ELRS", "Controller disconnection detected by monitor");
                updateControllerStatus(false);
            } else if (!controllerConnected) {
                // Only scan for new controllers if none connected
                boolean controller = detectController(false);
                if (controller) {
                    updateControllerStatus(true);
                }
            }

            if (deviceMonitorHandler != null) {
                deviceMonitorHandler.postDelayed(this, DEVICE_MONITOR_INTERVAL_MS);
            }
        }
    };
    
    private final Runnable uiTick = new Runnable() {
        @Override public void run() {
            if (tvRoll != null) {
                // Update RC channel displays with compact formatting
                tvRoll.setText(String.format("R:%04.0f", (lastRoll * 500 + 1500)));
                tvPitch.setText(String.format("P:%04.0f", (lastPitch * 500 + 1500)));
                tvYaw.setText(String.format("Y:%04.0f", (lastYaw * 500 + 1500)));
                tvThr.setText(String.format("T:%04.0f", (lastThr * 500 + 1500)));
                
                // Update 3D orientation display
                if (tv3DStatus != null) {
                    tv3DStatus.setText(String.format("ROLL: %.0f°\nPITCH: %.0f°\nYAW: %.0f°", 
                        lastRoll * 45, lastPitch * 45, lastYaw * 45));
                }
                
                // Update 3D rotation view
                if (rotation3DView != null) {
                    rotation3DView.updateRotation(lastRoll, lastPitch, lastYaw);
                }
                
                // Update camera status
                if (tvCameraStatus != null) {
                    tvCameraStatus.setText("CAMERA: " + (cameraConnected ? "CONNECTED" : "DISCONNECTED"));
                }
                
                // Update TX/RX graph with channel data
                if (txRxGraph != null) {
                    txRxGraph.addChannelData(lastRoll, lastPitch, lastYaw, lastThr);
                }
                
                // Update packet rate display
                if (tvPacketRate != null && txRxGraph != null) {
                    tvPacketRate.setText(String.format("TX: %.0fHz RX: %.0fHz", 
                        txRxGraph.getTxRate(), txRxGraph.getRxRate()));
                }
                
                // Update TX/RX status
                if (tvTxRxStatus != null) {
                    if (!backgroundInputEnabled) {
                        tvTxRxStatus.setText("INPUT DISABLED");
                        tvTxRxStatus.setTextColor(0xFFFF0000); // Red color when disabled
                    } else {
                        boolean isActive = Math.abs(lastRoll) > 0.01f || Math.abs(lastPitch) > 0.01f || 
                                         Math.abs(lastYaw) > 0.01f || Math.abs(lastThr) > 0.01f;
                        tvTxRxStatus.setText(isActive ? "ACTIVE" : "IDLE");
                        tvTxRxStatus.setTextColor(isActive ? 0xFF00FF00 : 0xFFFFFFFF); // Green when active, white when idle
                    }
                }
            }
            
            // Periodically recheck controller status (every ~1 second)
            controllerCheckCounter++;
            if (controllerCheckCounter >= 60) { // 60 frames at 60Hz = 1 second
                controllerCheckCounter = 0;
                if (!controllerConnected) {
                    checkControllerStatus();
                }
            }
            
            // keep immersive each tick, just in case
            hideSystemUi();
            tvRoll.postDelayed(this, 16); // ~60 Hz
        }
    };



    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        currentInstance = this; // Set static reference for callbacks
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        setContentView(R.layout.activity_main);
        
        // Verify custom font is applied via theme
        android.util.Log.d("ELRS", "Font verification: Theme should apply BigBlueTermPlusNerdFontMono");
        
        statusSuperG = findViewById(R.id.statusSuperG);
        statusController = findViewById(R.id.statusController);
        tvRoll   = findViewById(R.id.tvRoll);
        tvPitch  = findViewById(R.id.tvPitch);
        tvYaw    = findViewById(R.id.tvYaw);
        tvThr    = findViewById(R.id.tvThr);
        
        // Debug UI
        tvGamepadDevice = findViewById(R.id.tvGamepadDevice);
        tvGamepadButtons = findViewById(R.id.tvGamepadButtons);
        tvGamepadAxes = findViewById(R.id.tvGamepadAxes);
        // tvGamepadTriggers removed in new layout
        tvControllerName = findViewById(R.id.tvControllerName);
        
        // TX Action Buttons
        btnPair = findViewById(R.id.btnPair);
        btnIncSignal = findViewById(R.id.btnIncSignal);
        btnDecSignal = findViewById(R.id.btnDecSignal);
        btnBind = findViewById(R.id.btnBind);
        btnReset = findViewById(R.id.btnReset);
        btnModelSelect = findViewById(R.id.btnModelSelect);
        tvDeviceDetails = findViewById(R.id.tvDeviceDetails);
        
        // TX/RX Graph
        txRxGraph = findViewById(R.id.txRxGraph);
        tvTxRxStatus = findViewById(R.id.tvTxRxStatus);
        tvPacketRate = findViewById(R.id.tvPacketRate);
        
        // 3D View and Camera
        tv3DStatus = findViewById(R.id.tv3DStatus);
        tvCameraStatus = findViewById(R.id.tvCameraStatus);
        rotation3DView = findViewById(R.id.rotation3DView);
        cameraTextureView = findViewById(R.id.cameraTextureView);

        clearControllerDebug();
        setupTxActionButtons();
        setupSafetyControls();

        mgr = (UsbManager)getSystemService(USB_SERVICE);
        input = (InputManager)getSystemService(INPUT_SERVICE);
        input.registerInputDeviceListener(this, new Handler(Looper.getMainLooper()));

        permIntent = PendingIntent.getBroadcast(
                this, 0, new Intent(ACTION_USB),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        permRx = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (!ACTION_USB.equals(i.getAction())) return;
                UsbDevice dev = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (dev == null) return;
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    usbPermissionRequested = false;
                    boolean ok = UsbBridge.open(mgr, dev);
                    updateSuperGStatus(ok);
                } else {
                    usbPermissionRequested = false;
                    updateSuperGStatus(false);
                }
            }
        };
        registerUsbReceiver();

        // Check for USB devices and update status
        boolean foundSuperG = false;
        if (!mgr.getDeviceList().isEmpty()) {
            for (UsbDevice d : mgr.getDeviceList().values()) {
                if (hasBulkOut(d)) {
                    foundSuperG = true;
                    if (mgr.hasPermission(d)) {
                        boolean ok = UsbBridge.open(mgr, d);
                        updateSuperGStatus(ok);
                    } else {
                        mgr.requestPermission(d, permIntent);
                        updateSuperGStatus(false); // Will be updated when permission is granted
                    }
                }
            }
        }
        
        if (!foundSuperG) {
            updateSuperGStatus(false);
        }

        // auto request for already-plugged device(s)
        for (UsbDevice d : mgr.getDeviceList().values()) {
            maybeRequest(d);
        }

        // Check initial controller status with delay to allow devices to initialize
        checkControllerStatus();
        
        // Also schedule a delayed check in case devices aren't ready immediately
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            android.util.Log.d("ELRS", "=== Delayed controller check ===");
            checkControllerStatus();
        }, 2000); // 2 second delay
        
        // Register telemetry callback
        registerTelemetryCallback();
        android.util.Log.d("ELRS", "Telemetry callback registered");
        
        startDeviceMonitor();

        // TODO: Re-enable when CMake build is fixed
        // nativeStart();
        tvRoll.post(uiTick);
    }

    private void updateSuperGStatus(boolean connected) {
        boolean wasConnected = superGConnected;
        superGConnected = connected;
        
        if (statusSuperG != null) {
            statusSuperG.post(() -> statusSuperG.setBackgroundResource(
                connected ? R.drawable.status_circle_green : R.drawable.status_circle_red));
        }
        
        // Start/stop telemetry and TX loop based on connection status
        if (connected && !wasConnected) {
            try {
                nativeStart(); // Start TX control loop
                nativeStartTelemetry(); // Start telemetry reader
                android.util.Log.d("ELRS", "TX control and telemetry started");
            } catch (Exception e) {
                android.util.Log.e("ELRS", "Failed to start native systems", e);
            }
        } else if (!connected && wasConnected) {
            try {
                nativeStop(); // Stop TX control loop
                nativeStopTelemetry(); // Stop telemetry reader
                android.util.Log.d("ELRS", "TX control and telemetry stopped");
            } catch (Exception e) {
                android.util.Log.e("ELRS", "Failed to stop native systems", e);
            }
        }
    }
    
    private void updateControllerStatus(boolean connected) {
        boolean changed = controllerConnected != connected;
        controllerConnected = connected;
        
        if (statusController != null) {
            statusController.post(() -> statusController.setBackgroundResource(
                connected ? R.drawable.status_circle_green : R.drawable.status_circle_red));
        }
        
        // Show/hide controller name
        if (tvControllerName != null) {
            if (connected) {
                tvControllerName.post(() -> tvControllerName.setVisibility(View.VISIBLE));
            } else {
                tvControllerName.post(() -> tvControllerName.setVisibility(View.INVISIBLE));
            }
        }
        
        if (!connected && changed) {
            clearControllerDebug();
        }
    }
    
    private void refreshAllDevices() {
        boolean superG = ensureSuperGConnected();
        updateSuperGStatus(superG);

        // Check direct USB devices first
        boolean controllerViaUsb = checkDirectUsbDevices();
        boolean controller = detectController(true) || controllerViaUsb;
        updateControllerStatus(controller);
    }
    
    private boolean checkDirectUsbDevices() {
        if (mgr == null) return false;
        
        android.util.Log.d("ELRS", "=== Checking Direct USB Devices ===");
        boolean found8BitDo = false;
        boolean foundCP2102 = false;
        
        for (UsbDevice device : mgr.getDeviceList().values()) {
            android.util.Log.d("ELRS", String.format("USB Device: %04X:%04X - %s", 
                device.getVendorId(), device.getProductId(), device.getProductName()));
            
            // Check for 8BitDo Ultimate Mobile Gaming Controller (2DC8:301F)
            if (device.getVendorId() == 0x2DC8 && device.getProductId() == 0x301F) {
                android.util.Log.d("ELRS", "Found 8BitDo Ultimate Mobile Gaming Controller via USB!");
                android.util.Log.d("ELRS", dumpDevice(device));
                
                String detectedName = "8BitDo Ultimate Mobile Gaming Controller";
                updateControllerNameUI(detectedName);
                found8BitDo = true;
            }
            
            // Check for CP2102 USB to UART Bridge (10C4:EA60)
            if (device.getVendorId() == 0x10C4 && device.getProductId() == 0xEA60) {
                android.util.Log.d("ELRS", "Found CP2102 USB to UART Bridge Controller!");
                foundCP2102 = true;
            }
        }
        
        // Try root-based detection as fallback
        if (!found8BitDo) {
            found8BitDo = checkUsbDevicesViaRoot();
        }
        
        // Also check for input devices via root
        if (!found8BitDo) {
            found8BitDo = checkInputDevicesViaRoot();
        }
        
        return found8BitDo;
    }
    
    private boolean checkInputDevicesViaRoot() {
        try {
            android.util.Log.d("ELRS", "=== Checking input devices via root ===");
            
            // Check /proc/bus/input/devices for our controller
            java.lang.Process process = Runtime.getRuntime().exec("su -c 'cat /proc/bus/input/devices'");
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            
            String line;
            boolean inOurDevice = false;
            String deviceName = "";
            String deviceVendor = "";
            String deviceProduct = "";
            
            while ((line = reader.readLine()) != null) {
                android.util.Log.d("ELRS", "input devices: " + line);
                
                if (line.startsWith("I: ")) {
                    // Reset for new device
                    inOurDevice = false;
                    deviceName = "";
                    deviceVendor = "";
                    deviceProduct = "";
                }
                
                if (line.startsWith("N: Name=")) {
                    deviceName = line.substring(8).replace("\"", "");
                    // Check if this is our 8BitDo controller
                    if (deviceName.toLowerCase().contains("8bitdo") ||
                        deviceName.toLowerCase().contains("ultimate mobile gaming")) {
                        inOurDevice = true;
                        android.util.Log.d("ELRS", "Found 8BitDo input device: " + deviceName);
                    }
                }
                
                if (inOurDevice && line.startsWith("I: ")) {
                    // Parse the I: line for vendor/product IDs
                    if (line.contains("Vendor=2dc8") && line.contains("Product=301f")) {
                        android.util.Log.d("ELRS", "Confirmed 8BitDo controller via input devices!");
                        updateControllerNameUI(deviceName + " (via /proc/bus/input)");
                        reader.close();
                        return true;
                    }
                }
            }
            
            reader.close();
            process.waitFor();
            
        } catch (Exception e) {
            android.util.Log.e("ELRS", "Error checking input devices via root", e);
        }
        
        return false;
    }
    
    private boolean checkUsbDevicesViaRoot() {
        try {
            // Use lsusb command to check for specific devices
            java.lang.Process process = Runtime.getRuntime().exec("su -c 'lsusb'");
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            
            String line;
            boolean found8BitDo = false;
            android.util.Log.d("ELRS", "=== Root lsusb output ===");
            
            while ((line = reader.readLine()) != null) {
                android.util.Log.d("ELRS", "lsusb: " + line);
                
                // Look for 8BitDo controller: 2dc8:301f
                if (line.toLowerCase().contains("2dc8:301f") || 
                    line.toLowerCase().contains("8bitdo") ||
                    line.toLowerCase().contains("ultimate mobile gaming")) {
                    android.util.Log.d("ELRS", "Found 8BitDo via root lsusb!");
                    updateControllerNameUI("8BitDo (found via root lsusb)");
                    found8BitDo = true;
                }
            }
            
            process.waitFor();
            reader.close();
            
            // Also try checking /sys/bus/usb/devices
            if (!found8BitDo) {
                found8BitDo = checkSysUsbDevices();
            }
            
            return found8BitDo;
            
        } catch (Exception e) {
            android.util.Log.e("ELRS", "Error running root lsusb command", e);
            return false;
        }
    }
    
    private boolean checkSysUsbDevices() {
        try {
            // Check /sys/bus/usb/devices for our specific devices
            java.lang.Process process = Runtime.getRuntime().exec("su -c 'find /sys/bus/usb/devices -name \"idVendor\" -exec grep -l \"2dc8\" {} \\;'");
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            
            String line;
            while ((line = reader.readLine()) != null) {
                android.util.Log.d("ELRS", "Found 2dc8 vendor in: " + line);
                
                // Check if this device also has product ID 301f
                String devicePath = line.replace("/idVendor", "");
                java.lang.Process pidProcess = Runtime.getRuntime().exec("su -c 'cat " + devicePath + "/idProduct'");
                java.io.BufferedReader pidReader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(pidProcess.getInputStream()));
                
                String productId = pidReader.readLine();
                if ("301f".equals(productId)) {
                    android.util.Log.d("ELRS", "Confirmed 8BitDo controller at: " + devicePath);
                    
                    // Try to get product name
                    try {
                        java.lang.Process nameProcess = Runtime.getRuntime().exec("su -c 'cat " + devicePath + "/product'");
                        java.io.BufferedReader nameReader = new java.io.BufferedReader(
                            new java.io.InputStreamReader(nameProcess.getInputStream()));
                        String productName = nameReader.readLine();
                        
                        updateControllerNameUI("8BitDo (" + productName + " via /sys)");
                        nameReader.close();
                    } catch (Exception e) {
                        updateControllerNameUI("8BitDo (via /sys/bus/usb)");
                    }
                    
                    pidReader.close();
                    return true;
                }
                pidReader.close();
            }
            
            reader.close();
            process.waitFor();
            
        } catch (Exception e) {
            android.util.Log.e("ELRS", "Error checking /sys/bus/usb/devices", e);
        }
        
        return false;
    }
    
    private void updateControllerNameUI(String name) {
        if (tvGamepadDevice != null) {
            final String nameLabel = "Device: " + name;
            tvGamepadDevice.post(() -> tvGamepadDevice.setText(nameLabel));
        }
        if (tvControllerName != null) {
            final String finalName = name;
            tvControllerName.post(() -> {
                tvControllerName.setText(finalName);
                tvControllerName.setVisibility(View.VISIBLE);
            });
        }
    }

    private boolean ensureSuperGConnected() {
        if (mgr == null) return false;

        UsbDevice target = null;
        for (UsbDevice device : mgr.getDeviceList().values()) {
            if (hasBulkOut(device)) {
                target = device;
                break;
            }
        }

        if (target == null) {
            UsbBridge.close();
            usbPermissionRequested = false;
            return false;
        }

        if (!mgr.hasPermission(target)) {
            if (!usbPermissionRequested) {
                mgr.requestPermission(target, permIntent);
                usbPermissionRequested = true;
            }
            return UsbBridge.isOpen();
        }

        if (!UsbBridge.isOpen()) {
            boolean opened = UsbBridge.open(mgr, target);
            if (!opened) {
                return false;
            }
        }

        usbPermissionRequested = false;
        return UsbBridge.isOpen();
    }

    private boolean detectController(boolean verboseLogging) {
        if (input == null) return false;

        int[] deviceIds = input.getInputDeviceIds();
        if (verboseLogging) {
            android.util.Log.d("ELRS", "=== Checking Input Devices ===");
            android.util.Log.d("ELRS", "Found " + deviceIds.length + " input devices");
        }

        boolean hasController = false;
        String detectedName = null;

        for (int deviceId : deviceIds) {
            InputDevice device = input.getInputDevice(deviceId);
            if (device == null) continue;

            int sources = device.getSources();
            boolean isController = (sources & InputDevice.SOURCE_JOYSTICK) != 0 ||
                                   (sources & InputDevice.SOURCE_GAMEPAD) != 0;

            if (verboseLogging) {
                android.util.Log.d("ELRS", String.format("Device[%d]: %s", deviceId, device.getName()));
                android.util.Log.d("ELRS", String.format("  Sources: 0x%08X", sources));
                android.util.Log.d("ELRS", String.format("  JOYSTICK: %s", (sources & InputDevice.SOURCE_JOYSTICK) != 0));
                android.util.Log.d("ELRS", String.format("  GAMEPAD: %s", (sources & InputDevice.SOURCE_GAMEPAD) != 0));
                android.util.Log.d("ELRS", String.format("  DPAD: %s", (sources & InputDevice.SOURCE_DPAD) != 0));
                android.util.Log.d("ELRS", String.format("  KEYBOARD: %s", (sources & InputDevice.SOURCE_KEYBOARD) != 0));
                android.util.Log.d("ELRS", String.format("  MOUSE: %s", (sources & InputDevice.SOURCE_MOUSE) != 0));
                android.util.Log.d("ELRS", String.format("  TOUCHSCREEN: %s", (sources & InputDevice.SOURCE_TOUCHSCREEN) != 0));
                android.util.Log.d("ELRS", String.format("  TRACKBALL: %s", (sources & InputDevice.SOURCE_TRACKBALL) != 0));
                android.util.Log.d("ELRS", String.format("  TOUCHPAD: %s", (sources & InputDevice.SOURCE_TOUCHPAD) != 0));
                android.util.Log.d("ELRS", String.format("  STYLUS: %s", (sources & InputDevice.SOURCE_STYLUS) != 0));
                android.util.Log.d("ELRS", String.format("  BLUETOOTH_STYLUS: %s", (sources & InputDevice.SOURCE_BLUETOOTH_STYLUS) != 0));
                android.util.Log.d("ELRS", String.format("  ROTARY_ENCODER: %s", (sources & InputDevice.SOURCE_ROTARY_ENCODER) != 0));
                
                // Check if this might be the 8BitDo controller based on name
                String deviceName = device.getName();
                if (deviceName != null && (deviceName.toLowerCase().contains("8bitdo") || 
                                         deviceName.toLowerCase().contains("ultimate") ||
                                         deviceName.toLowerCase().contains("gaming"))) {
                    android.util.Log.d("ELRS", "  *** POTENTIAL 8BITDO CONTROLLER FOUND ***");
                    android.util.Log.d("ELRS", "  Motion ranges available:");
                    for (InputDevice.MotionRange range : device.getMotionRanges()) {
                        android.util.Log.d("ELRS", String.format("    Axis %d (%s): min=%.2f max=%.2f", 
                            range.getAxis(), MotionEvent.axisToString(range.getAxis()), 
                            range.getMin(), range.getMax()));
                    }
                }
            }

            if (isController) {
                if (verboseLogging) {
                    android.util.Log.d("ELRS", "  -> CONTROLLER DETECTED!");
                }
                hasController = true;
                detectedName = device.getName();
                if (!verboseLogging) {
                    break;
                }
            } else {
                // Special case: Check for 8BitDo controller by name even if sources don't match
                String deviceName = device.getName();
                if (deviceName != null) {
                    boolean is8BitDoByName = deviceName.toLowerCase().contains("8bitdo") || 
                                           deviceName.toLowerCase().contains("ultimate mobile gaming");
                    boolean isDm8150Audio = deviceName.toLowerCase().contains("dm8150") && 
                                          deviceName.toLowerCase().contains("snd-card");
                    
                    // Check for dm8150 audio device that might be our controller
                    if (isDm8150Audio && !device.getMotionRanges().isEmpty()) {
                        if (verboseLogging) {
                            android.util.Log.d("ELRS", "  -> DM8150 AUDIO DEVICE WITH MOTION RANGES (likely 8BitDo)!");
                            android.util.Log.d("ELRS", "     Motion ranges: " + device.getMotionRanges().size());
                        }
                        hasController = true;
                        detectedName = "8BitDo (detected as " + deviceName + ")";
                        if (!verboseLogging) {
                            break;
                        }
                    }
                    // Original 8BitDo name detection
                    else if (is8BitDoByName) {
                        // Check if it has any motion ranges (axes) that suggest it's a controller
                        boolean hasAxes = false;
                        for (InputDevice.MotionRange range : device.getMotionRanges()) {
                            int axis = range.getAxis();
                            if (axis == MotionEvent.AXIS_X || axis == MotionEvent.AXIS_Y ||
                                axis == MotionEvent.AXIS_RX || axis == MotionEvent.AXIS_RY ||
                                axis == MotionEvent.AXIS_Z || axis == MotionEvent.AXIS_RZ) {
                                hasAxes = true;
                                break;
                            }
                        }
                        
                        if (hasAxes) {
                            if (verboseLogging) {
                                android.util.Log.d("ELRS", "  -> 8BITDO CONTROLLER DETECTED BY NAME & AXES!");
                            }
                            hasController = true;
                            detectedName = deviceName;
                            if (!verboseLogging) {
                                break;
                            }
                        }
                    }
                }
            }
        }

        if (verboseLogging) {
            android.util.Log.d("ELRS", "Controller status: " + hasController);
        }

        if (hasController && detectedName != null) {
            if (tvGamepadDevice != null) {
                final String nameLabel = "Device: " + detectedName;
                tvGamepadDevice.post(() -> tvGamepadDevice.setText(nameLabel));
            }
            if (tvControllerName != null) {
                final String finalDetectedName = detectedName;
                tvControllerName.post(() -> {
                    tvControllerName.setText(finalDetectedName);
                    tvControllerName.setVisibility(View.VISIBLE);
                });
            }
        }

        return hasController;
    }
    
    private void dumpSystemDeviceInfo() {
        android.util.Log.d("ELRS", "=== COMPREHENSIVE SYSTEM DEVICE DUMP ===");
        
        // Expected devices based on your lsusb output:
        // 01 002 1a86:8091 - USB HUB
        // 01 005 10c4:ea60 - CP2102 USB to UART Bridge Controller (ExpressLRS)
        // 01 003 2dc8:301f - 8BitDo Ultimate Mobile Gaming Controller
        // 01 001 1d6b:0002 - xHCI Host Controller
        
        checkExpectedDevices();
        dumpUsbHierarchy();
        dumpInputDeviceMapping();
    }
    
    private void checkExpectedDevices() {
        android.util.Log.d("ELRS", "--- Checking Expected Devices ---");
        
        // Check for CP2102 (ExpressLRS module)
        boolean foundCP2102 = false;
        // Check for 8BitDo controller
        boolean found8BitDo = false;
        
        if (mgr != null) {
            for (UsbDevice device : mgr.getDeviceList().values()) {
                int vid = device.getVendorId();
                int pid = device.getProductId();
                
                if (vid == 0x10C4 && pid == 0xEA60) {
                    foundCP2102 = true;
                    android.util.Log.d("ELRS", "✓ Found CP2102 USB to UART Bridge Controller (ExpressLRS)");
                }
                
                if (vid == 0x2DC8 && pid == 0x301F) {
                    found8BitDo = true;
                    android.util.Log.d("ELRS", "✓ Found 8BitDo Ultimate Mobile Gaming Controller");
                }
            }
        }
        
        if (!foundCP2102) {
            android.util.Log.w("ELRS", "✗ CP2102 USB to UART Bridge Controller NOT FOUND");
        }
        
        if (!found8BitDo) {
            android.util.Log.w("ELRS", "✗ 8BitDo Ultimate Mobile Gaming Controller NOT FOUND via USB");
            android.util.Log.d("ELRS", "   Checking if it's detected as audio device...");
            checkFor8BitDoAsAudioDevice();
        }
    }
    
    private void checkFor8BitDoAsAudioDevice() {
        if (input == null) return;
        
        int[] deviceIds = input.getInputDeviceIds();
        for (int deviceId : deviceIds) {
            InputDevice device = input.getInputDevice(deviceId);
            if (device == null) continue;
            
            String deviceName = device.getName();
            if (deviceName != null) {
                if (deviceName.toLowerCase().contains("dm8150") && 
                    deviceName.toLowerCase().contains("snd-card")) {
                    
                    android.util.Log.w("ELRS", "⚠ Found dm8150 snd-card device (likely misdetected 8BitDo): " + deviceName);
                    android.util.Log.d("ELRS", "   Motion ranges: " + device.getMotionRanges().size());
                    
                    if (!device.getMotionRanges().isEmpty()) {
                        android.util.Log.w("ELRS", "   → This audio device has motion ranges - definitely our controller!");
                    }
                }
            }
        }
    }
    
    private void dumpUsbHierarchy() {
        try {
            android.util.Log.d("ELRS", "--- USB Device Hierarchy (via root) ---");
            java.lang.Process process = Runtime.getRuntime().exec("su -c 'lsusb -t 2>/dev/null || echo \"lsusb -t not available\"'");
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            
            String line;
            while ((line = reader.readLine()) != null) {
                android.util.Log.d("ELRS", "USB tree: " + line);
            }
            
            reader.close();
            process.waitFor();
        } catch (Exception e) {
            android.util.Log.e("ELRS", "Could not get USB hierarchy", e);
        }
    }
    
    private void dumpInputDeviceMapping() {
        try {
            android.util.Log.d("ELRS", "--- Input Device to USB Mapping ---");
            
            // Try to correlate input devices with USB devices
            if (input != null) {
                int[] deviceIds = input.getInputDeviceIds();
                for (int deviceId : deviceIds) {
                    InputDevice device = input.getInputDevice(deviceId);
                    if (device == null) continue;
                    
                    String deviceName = device.getName();
                    String descriptor = device.getDescriptor();
                    
                    android.util.Log.d("ELRS", "Input Device: " + deviceName);
                    android.util.Log.d("ELRS", "  Descriptor: " + descriptor);
                    
                    // Try to extract USB info from descriptor
                    if (descriptor != null && descriptor.contains("usb")) {
                        android.util.Log.d("ELRS", "  → USB-based input device");
                        
                        // Check if this matches our expected devices
                        if (deviceName != null) {
                            if (deviceName.toLowerCase().contains("8bitdo") ||
                                deviceName.toLowerCase().contains("ultimate mobile gaming")) {
                                android.util.Log.d("ELRS", "  → This is our 8BitDo controller!");
                            } else if (deviceName.toLowerCase().contains("dm8150") &&
                                      deviceName.toLowerCase().contains("snd-card")) {
                                android.util.Log.w("ELRS", "  → This might be our 8BitDo misdetected as audio!");
                            }
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            android.util.Log.e("ELRS", "Error mapping input devices", e);
        }
    }

    private void setupTxActionButtons() {
        // Setup PAIR button
        if (btnPair != null) {
            btnPair.setOnClickListener(v -> {
                android.util.Log.d("ELRS", "TX Action: PAIR requested");
                sendTxCommand("PAIR");
            });
        }
        
        // Setup INC SIGNAL button
        if (btnIncSignal != null) {
            btnIncSignal.setOnClickListener(v -> {
                android.util.Log.d("ELRS", "TX Action: INC SIGNAL requested");
                sendTxCommand("INC_SIGNAL");
            });
        }
        
        // Setup DEC SIGNAL button
        if (btnDecSignal != null) {
            btnDecSignal.setOnClickListener(v -> {
                android.util.Log.d("ELRS", "TX Action: DEC SIGNAL requested");
                sendTxCommand("DEC_SIGNAL");
            });
        }
        
        // Setup BIND button
        if (btnBind != null) {
            btnBind.setOnClickListener(v -> {
                android.util.Log.d("ELRS", "TX Action: BIND requested");
                sendTxCommand("BIND");
            });
        }
        
        // Setup RESET button
        if (btnReset != null) {
            btnReset.setOnClickListener(v -> {
                android.util.Log.d("ELRS", "TX Action: RESET requested");
                sendTxCommand("RESET");
            });
        }
        
        // Setup MODEL SELECT button
        if (btnModelSelect != null) {
            btnModelSelect.setOnClickListener(v -> {
                android.util.Log.d("ELRS", "TX Action: MODEL SELECT requested");
                sendTxCommand("MODEL_SELECT");
            });
        }
        
        // Start real-time axis monitoring
        startAxisMonitoring();
    }
    
    private void sendTxCommand(String command) {
        android.util.Log.d("ELRS", "Sending TX command: " + command);
        
        // Check if SuperG is connected
        if (!superGConnected) {
            updateCommandStatus(command, "FAILED", "SuperG not connected");
            return;
        }
        
        // Send command via native layer
        boolean success = false;
        try {
            success = nativeSendCommand(command);
        } catch (Exception e) {
            android.util.Log.e("ELRS", "Error sending command: " + command, e);
        }
        
        // Update UI with command result
        String status = success ? "SUCCESS" : "FAILED";
        String details = success ? "Command sent to ELRS TX" : "Command transmission failed";
        updateCommandStatus(command, status, details);
        
        // Special handling for PAIR command
        if ("PAIR".equals(command) && success) {
            showPairingDialog();
        }
    }
    
    private void updateCommandStatus(String command, String status, String details) {
        // Update device details with command status
        if (tvDeviceDetails != null) {
            tvDeviceDetails.post(() -> 
                tvDeviceDetails.setText("TX CMD: " + command + "\nStatus: " + status + "\n" + details + "\nTime: " + 
                    new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()))
            );
        }
        
        // Flash TX status to show activity
        if (tvTxRxStatus != null) {
            int color = "SUCCESS".equals(status) ? 0xFF00AA00 : "FAILED".equals(status) ? 0xFFAA0000 : 0xFFFFAA00;
            
            tvTxRxStatus.post(() -> {
                tvTxRxStatus.setText("TX: " + command);
                tvTxRxStatus.setTextColor(color);
            });
            
            // Reset status after 3 seconds
            tvTxRxStatus.postDelayed(() -> {
                if (tvTxRxStatus != null) {
                    tvTxRxStatus.setText("IDLE");
                    tvTxRxStatus.setTextColor(0xFFFFFFFF); // White
                }
            }, 3000);
        }
    }
    
    private void showPairingDialog() {
        if (exitDialog != null && exitDialog.isShowing()) {
            return; // Don't show multiple dialogs
        }
        
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("ELRS Pairing Mode")
               .setMessage("Transmitter is now in pairing mode.\n\nPut your receiver in bind mode now.\n\nPairing will timeout in 60 seconds.")
               .setPositiveButton("OK", (dialog, which) -> dialog.dismiss())
               .setCancelable(true);
        
        exitDialog = builder.create();
        exitDialog.show();
        
        // Auto-dismiss after 60 seconds
        exitDialog.getWindow().getDecorView().postDelayed(() -> {
            if (exitDialog != null && exitDialog.isShowing()) {
                exitDialog.dismiss();
            }
        }, 60000);
    }
    

    
    private void dumpDeviceInfo(InputDevice device) {
        StringBuilder info = new StringBuilder();
        info.append("=== DEVICE INFO ===\n");
        info.append("ID: ").append(device.getId()).append("\n");
        info.append("Name: ").append(device.getName()).append("\n");
        info.append("Descriptor: ").append(device.getDescriptor()).append("\n");
        
        int sources = device.getSources();
        info.append("Sources: 0x").append(Integer.toHexString(sources)).append("\n");
        
        // Check all potential sources
        String[] sourceNames = {
            "KEYBOARD", "DPAD", "GAMEPAD", "JOYSTICK", 
            "MOUSE", "STYLUS", "TOUCHSCREEN", "TOUCHPAD"
        };
        int[] sourceFlags = {
            InputDevice.SOURCE_KEYBOARD, InputDevice.SOURCE_DPAD,
            InputDevice.SOURCE_GAMEPAD, InputDevice.SOURCE_JOYSTICK,
            InputDevice.SOURCE_MOUSE, InputDevice.SOURCE_STYLUS, 
            InputDevice.SOURCE_TOUCHSCREEN, InputDevice.SOURCE_TOUCHPAD
        };
        
        for (int i = 0; i < sourceNames.length; i++) {
            boolean hasSource = (sources & sourceFlags[i]) != 0;
            info.append("  ").append(sourceNames[i]).append(": ").append(hasSource).append("\n");
        }
        
        // List all available motion ranges
        info.append("Motion Ranges:\n");
        for (InputDevice.MotionRange range : device.getMotionRanges()) {
            info.append("  Axis ").append(MotionEvent.axisToString(range.getAxis()))
                .append(" (").append(range.getAxis()).append(")")
                .append(" min=").append(String.format("%.2f", range.getMin()))
                .append(" max=").append(String.format("%.2f", range.getMax()))
                .append("\n");
        }
        
        // Add special case detection info
        info.append("\nSpecial Detection:\n");
        String deviceName = device.getName();
        boolean is8BitDoByName = deviceName != null && 
            (deviceName.toLowerCase().contains("8bitdo") ||
             deviceName.toLowerCase().contains("ultimate mobile"));
        boolean isDm8150Audio = deviceName != null && 
            deviceName.toLowerCase().contains("dm8150") &&
            deviceName.toLowerCase().contains("snd-card");
        
        info.append("  Matches 8BitDo pattern: ").append(is8BitDoByName).append("\n");
        info.append("  Matches DM8150 audio: ").append(isDm8150Audio).append("\n");
        info.append("  Has motion ranges: ").append(!device.getMotionRanges().isEmpty()).append("\n");
        
        // Log to ADB
        android.util.Log.d("ELRS", "DEVICE DUMP: " + info.toString());
        
        // Update UI
        if (tvDeviceDetails != null) {
            tvDeviceDetails.post(() -> tvDeviceDetails.setText(info.toString()));
        }
    }
    
    private void startAxisMonitoring() {
        Handler handler = new Handler(Looper.getMainLooper());
        
        handler.post(new Runnable() {
            @Override
            public void run() {
                // Axis info now shown in device details section
                if (false && input != null) {
                    // Get all connected devices with motion ranges
                    int[] deviceIds = input.getInputDeviceIds();
                    StringBuilder axisInfo = new StringBuilder();
                    axisInfo.append("Real-time Axis Monitor:\n");
                    
                    for (int deviceId : deviceIds) {
                        InputDevice device = input.getInputDevice(deviceId);
                        if (device == null || device.getMotionRanges().isEmpty()) continue;
                        
                        String deviceName = device.getName();
                        if (deviceName != null && deviceName.length() > 30) {
                            deviceName = deviceName.substring(0, 27) + "...";
                        }
                        
                        axisInfo.append("Device: ").append(deviceName).append("\n");
                        
                        // Show available axes (we can't read current values without active input)
                        int axisCount = 0;
                        for (InputDevice.MotionRange range : device.getMotionRanges()) {
                            if (axisCount >= 4) { // Limit to first 4 axes for display
                                axisInfo.append("  ... (").append(device.getMotionRanges().size() - axisCount).append(" more)\n");
                                break;
                            }
                            axisInfo.append("  ").append(MotionEvent.axisToString(range.getAxis()))
                                   .append(": <idle> (").append(String.format("%.1f", range.getMin()))
                                   .append(" to ").append(String.format("%.1f", range.getMax())).append(")\n");
                            axisCount++;
                        }
                        axisInfo.append("\n");
                        
                        // Only show first device with motion ranges to avoid clutter
                        break;
                    }
                    
                    if (axisInfo.length() <= 30) { // If no devices found
                        axisInfo.append("No devices with motion ranges found\n");
                    }
                    
                    // Axis info now shown in device details
                }
                
                handler.postDelayed(this, 1000); // Update every second
            }
        });
    }

    private void clearControllerDebug() {
        if (tvGamepadDevice != null) {
            tvGamepadDevice.post(() -> tvGamepadDevice.setText("Device: None"));
        }
        if (tvGamepadButtons != null) {
            tvGamepadButtons.post(() -> tvGamepadButtons.setText("Buttons: ---"));
        }
        if (tvGamepadAxes != null) {
            tvGamepadAxes.post(() -> tvGamepadAxes.setText("Axes: ---"));
        }
        // tvGamepadTriggers removed in new layout
        if (tvControllerName != null) {
            tvControllerName.post(() -> tvControllerName.setVisibility(View.INVISIBLE));
        }
    }

    private void startDeviceMonitor() {
        if (deviceMonitorThread != null) return;

        deviceMonitorThread = new HandlerThread("DeviceMonitor");
        deviceMonitorThread.start();
        deviceMonitorHandler = new Handler(deviceMonitorThread.getLooper());
        deviceMonitorHandler.post(deviceMonitorTask);
    }

    private void stopDeviceMonitor() {
        if (deviceMonitorHandler != null) {
            deviceMonitorHandler.removeCallbacksAndMessages(null);
            deviceMonitorHandler = null;
        }
        if (deviceMonitorThread != null) {
            deviceMonitorThread.quitSafely();
            deviceMonitorThread = null;
        }
    }

    private boolean validateControllerActive() {
        if (input == null) return false;
        
        // Get all controllers that are currently connected
        int[] deviceIds = input.getInputDeviceIds();
        for (int deviceId : deviceIds) {
            InputDevice device = input.getInputDevice(deviceId);
            if (device != null) {
                int sources = device.getSources();
                if ((sources & InputDevice.SOURCE_JOYSTICK) != 0 || 
                    (sources & InputDevice.SOURCE_GAMEPAD) != 0) {
                    // Found active controller
                    return true;
                }
            }
        }
        // No active controller found
        return false;
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerUsbReceiver() {
        IntentFilter f = new IntentFilter(ACTION_USB);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(permRx, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(permRx, f);
        }
    }

    private void maybeRequest(UsbDevice d){
        if (d.getVendorId()==0x10C4 && d.getProductId()==0xEA60) {
            if (mgr.hasPermission(d)) {
                boolean ok = UsbBridge.open(mgr, d);
                updateSuperGStatus(ok);
            } else {
                if (!usbPermissionRequested) {
                    mgr.requestPermission(d, permIntent);
                    usbPermissionRequested = true;
                }
                updateSuperGStatus(false); // Will be updated when permission is granted
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        hideSystemUi();
        
        // Re-establish connection if SuperG is still connected
        if (superGConnected) {
            android.util.Log.d("ELRS", "App resuming - attempting to restart TX systems");
            try {
                nativeStart(); // Restart TX loop (will start disarmed due to emergency stop)
            } catch (Exception e) {
                android.util.Log.e("ELRS", "Error restarting TX on resume", e);
            }
        }
    }
    
    @Override protected void onPause() {
        super.onPause();
        
        // CRITICAL SAFETY: Emergency stop when app goes to background
        try {
            android.util.Log.w("ELRS", "App pausing - engaging emergency failsafe");
            nativeEmergencyStop(); // Disarm and zero all controls
            nativeStop(); // Stop TX loop
        } catch (Exception e) {
            android.util.Log.e("ELRS", "Error during pause failsafe", e);
        }
    }

    private void hideSystemUi() {
        final View v = getWindow().getDecorView();
        v.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        // If exit dialog is showing, block all motion events to prevent interference
        if (exitDialog != null && exitDialog.isShowing()) {
            android.util.Log.d("ELRS", "Exit dialog active - blocking motion event");
            return true; // Consume the event
        }
        
        // More inclusive check for gamepad input - also check for 8BitDo by name
        boolean isGamepadSource = ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != 0 ||
                                  (e.getSource() & InputDevice.SOURCE_GAMEPAD) != 0);
        
        // Special case for 8BitDo controller that might not report correct sources
        boolean is8BitDo = false;
        boolean isDm8150Audio = false;
        InputDevice device = e.getDevice();
        if (device != null) {
            String deviceName = device.getName();
            if (deviceName != null) {
                is8BitDo = deviceName.toLowerCase().contains("8bitdo") || 
                          deviceName.toLowerCase().contains("ultimate mobile gaming");
                isDm8150Audio = deviceName.toLowerCase().contains("dm8150") && 
                               deviceName.toLowerCase().contains("snd-card");
            }
        }
        
        if ((isGamepadSource || is8BitDo || isDm8150Audio) && e.getAction() == MotionEvent.ACTION_MOVE) {

            // Debug: Log motion event details
            if (!controllerConnected) {
                android.util.Log.d("ELRS", "Motion event from device: " + 
                    (device != null ? device.getName() : "unknown"));
                android.util.Log.d("ELRS", String.format("Source: 0x%08X", e.getSource()));
                String detectionMethod = isGamepadSource ? "Standard gamepad source" : 
                                       is8BitDo ? "8BitDo name match" : 
                                       isDm8150Audio ? "dm8150 audio (forced)" : "Unknown";
                android.util.Log.d("ELRS", "Detected via: " + detectionMethod);
            }

            // Controller is active - update status
            if (!controllerConnected) {
                android.util.Log.d("ELRS", "Controller detected via motion event!");
                updateControllerStatus(true);
            }

            // Update debug display with device info (device already declared above)
            if (device != null) {
                String deviceName = device.getName();
                if (tvGamepadDevice != null) {
                    tvGamepadDevice.post(() -> tvGamepadDevice.setText("Device: " + deviceName));
                }
                if (tvControllerName != null) {
                    tvControllerName.post(() -> {
                        tvControllerName.setText(deviceName);
                        tvControllerName.setVisibility(View.VISIBLE);
                    });
                }
            }

            float rx = getAxis(e, MotionEvent.AXIS_X);
            if (rx == 0f) rx = getAxis(e, MotionEvent.AXIS_RX);
            float ry = -getAxis(e, MotionEvent.AXIS_Y);
            if (ry == 0f) ry = -getAxis(e, MotionEvent.AXIS_RY);
            float rz = getAxis(e, MotionEvent.AXIS_Z);
            if (rz == 0f) rz = getAxis(e, MotionEvent.AXIS_HAT_X); // fallback
            float thr = (getAxis(e, MotionEvent.AXIS_RZ)+1f)*0.5f;
            if (thr == 0.5f) { // some pads use LTRIGGER/RT analogs
                float lt = (getAxis(e, MotionEvent.AXIS_LTRIGGER)+1f)*0.5f;
                float rt = (getAxis(e, MotionEvent.AXIS_RTRIGGER)+1f)*0.5f;
                if (rt > 0.05f || lt > 0.05f) thr = rt; // pick RT as throttle
            }

            // Update debug display with axes values
            if (tvGamepadAxes != null) {
                final String axesText = String.format("Axes: X:%.2f Y:%.2f RX:%.2f RY:%.2f", 
                    getAxis(e, MotionEvent.AXIS_X), getAxis(e, MotionEvent.AXIS_Y),
                    getAxis(e, MotionEvent.AXIS_RX), getAxis(e, MotionEvent.AXIS_RY));
                tvGamepadAxes.post(() -> tvGamepadAxes.setText(axesText));
            }
            
            // Update debug display with trigger values
            // Trigger info now included in axes display
            
            // Live axis values now shown in device details section

            lastRoll = rx; lastPitch = ry; lastYaw = rz; lastThr = thr;
            
            // Only send axes to native layer if background input is enabled
            // This stops drone control when B+X exit sequence is active
            if (backgroundInputEnabled) {
                nativeSetAxes(rx, ry, rz, thr);
            } else {
                // Log occasionally that input is being blocked (not every frame to avoid spam)
                if (System.currentTimeMillis() % 1000 < 50) { // Log roughly once per second
                    android.util.Log.d("ELRS", "Controller input blocked - axes not sent to drone (B+X active)");
                }
            }
            return true;
        }
        return super.onGenericMotionEvent(e);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Debug: Log all key events to help with troubleshooting
        android.util.Log.d("ELRS", "KeyDown: code=" + keyCode + " (" + KeyEvent.keyCodeToString(keyCode) + "), repeat=" + event.getRepeatCount() + ", source=0x" + Integer.toHexString(event.getSource()));
        
        // If exit dialog is showing, let it handle the key events
        if (exitDialog != null && exitDialog.isShowing()) {
            android.util.Log.d("ELRS", "Exit dialog active - delegating key event: " + keyCode);
            return true; // Consume the event
        }

        
        // Check if this is a gamepad key or 8BitDo controller
        boolean isGamepadSource = ((event.getSource() & InputDevice.SOURCE_GAMEPAD) != 0) ||
                                 ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != 0);
        
        // Special case for 8BitDo controller and dm8150 audio
        boolean is8BitDo = false;
        boolean isDm8150Audio = false;
        InputDevice device = event.getDevice();
        if (device != null) {
            String deviceName = device.getName();
            if (deviceName != null) {
                is8BitDo = deviceName.toLowerCase().contains("8bitdo") || 
                          deviceName.toLowerCase().contains("ultimate mobile gaming");
                isDm8150Audio = deviceName.toLowerCase().contains("dm8150") && 
                               deviceName.toLowerCase().contains("snd-card");
            }
        }
        
        // Handle safe exit sequence - B + X combination (prevent multiple registrations)
        if ((keyCode == KeyEvent.KEYCODE_X || keyCode == KeyEvent.KEYCODE_BUTTON_X) && event.getRepeatCount() == 0) {
            xButtonPressed = true;
            checkSafeExitSequence();
            android.util.Log.d("ELRS", "X button pressed - safe exit sequence checking");
        }
        
        if ((keyCode == KeyEvent.KEYCODE_B || keyCode == KeyEvent.KEYCODE_BUTTON_B) && event.getRepeatCount() == 0) {
            bButtonPressed = true;
            checkSafeExitSequence();
            android.util.Log.d("ELRS", "B button pressed - safe exit sequence checking");
        }
        
        // Handle Y key for debug logging toggle (both keyboard Y and gamepad Y button)
        if ((keyCode == KeyEvent.KEYCODE_Y || keyCode == KeyEvent.KEYCODE_BUTTON_Y) && event.getRepeatCount() == 0) {
            debugLoggingEnabled = true;
            nativeSetDebugLogging(true);
            String buttonType = (keyCode == KeyEvent.KEYCODE_Y) ? "Keyboard Y" : "Gamepad Y Button";
            android.util.Log.i("ELRS", "🔍 DEBUG LOGGING: ENABLED via " + buttonType + " - Release Y to disable");
            if (tvGamepadButtons != null) {
                tvGamepadButtons.post(() -> tvGamepadButtons.setText("DEBUG LOGGING: ON (" + buttonType + ")"));
            }
            return true;
        }

        // Block system back button
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            android.util.Log.d("ELRS", "System back blocked - use B + X to exit safely");
            if (tvGamepadButtons != null) {
                tvGamepadButtons.post(() -> tvGamepadButtons.setText("EXIT BLOCKED - Use B + X"));
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (tvGamepadButtons != null) {
                        tvGamepadButtons.setText("Buttons: ---");
                    }
                }, 2000);
            }
            return true; // Consume the event to block exit
        }

        if (isGamepadSource || is8BitDo || isDm8150Audio) {
            // Controller is active - update status
            if (!controllerConnected) {
                android.util.Log.d("ELRS", "Key event from device: " + 
                    (device != null ? device.getName() : "unknown"));
                android.util.Log.d("ELRS", "Controller detected via key event!");
                String detectionMethod = isGamepadSource ? "Standard gamepad source" : 
                                       is8BitDo ? "8BitDo name match" : 
                                       isDm8150Audio ? "dm8150 audio (forced)" : "Unknown";
                android.util.Log.d("ELRS", "Detected via: " + detectionMethod);
                updateControllerStatus(true);
            }
            
            // Update controller name display
            if (device != null && tvControllerName != null) {
                String deviceName = device.getName();
                tvControllerName.post(() -> {
                    tvControllerName.setText(deviceName);
                    tvControllerName.setVisibility(View.VISIBLE);
                });
            }
            
            // Update debug display with button press (including B and X for exit sequence)
            if (tvGamepadButtons != null && keyCode != KeyEvent.KEYCODE_BACK) {
                String buttonName = KeyEvent.keyCodeToString(keyCode);
                final String buttonText = "Button: " + buttonName + " DOWN";
                
                // Show exit sequence status if both buttons pressed
                if ((keyCode == KeyEvent.KEYCODE_B || keyCode == KeyEvent.KEYCODE_BUTTON_B) && xButtonPressed) {
                    tvGamepadButtons.post(() -> tvGamepadButtons.setText("EXIT SEQUENCE: B + X"));
                } else if ((keyCode == KeyEvent.KEYCODE_X || keyCode == KeyEvent.KEYCODE_BUTTON_X) && bButtonPressed) {
                    tvGamepadButtons.post(() -> tvGamepadButtons.setText("EXIT SEQUENCE: B + X"));
                } else {
                    tvGamepadButtons.post(() -> tvGamepadButtons.setText(buttonText));
                }
                
                // Clear button display after a short delay (unless exit sequence is active)
                if (safeExitStartTime == 0) {
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (tvGamepadButtons != null && safeExitStartTime == 0) {
                            tvGamepadButtons.setText("Buttons: ---");
                        }
                    }, 500);
                }
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        // Debug: Log all key events to help with troubleshooting
        android.util.Log.d("ELRS", "KeyUp: code=" + keyCode + " (" + KeyEvent.keyCodeToString(keyCode) + "), source=0x" + Integer.toHexString(event.getSource()));
        
        // If exit dialog is showing, let it handle the key events
        if (exitDialog != null && exitDialog.isShowing()) {
            android.util.Log.d("ELRS", "Exit dialog active - delegating key up event: " + keyCode);
            return true; // Consume the event
        }

        
        // Check if this is a gamepad key or 8BitDo controller
        boolean isGamepadSource = ((event.getSource() & InputDevice.SOURCE_GAMEPAD) != 0) ||
                                 ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != 0);
        
        // Special case for 8BitDo controller and dm8150 audio
        boolean is8BitDo = false;
        boolean isDm8150Audio = false;
        InputDevice device = event.getDevice();
        if (device != null) {
            String deviceName = device.getName();
            if (deviceName != null) {
                is8BitDo = deviceName.toLowerCase().contains("8bitdo") || 
                          deviceName.toLowerCase().contains("ultimate mobile gaming");
                isDm8150Audio = deviceName.toLowerCase().contains("dm8150") && 
                               deviceName.toLowerCase().contains("snd-card");
            }
        }
        
        // Handle Y key release for debug logging toggle (both keyboard Y and gamepad Y button)
        if (keyCode == KeyEvent.KEYCODE_Y || keyCode == KeyEvent.KEYCODE_BUTTON_Y) {
            debugLoggingEnabled = false;
            nativeSetDebugLogging(false);
            String buttonType = (keyCode == KeyEvent.KEYCODE_Y) ? "Keyboard Y" : "Gamepad Y Button";
            android.util.Log.i("ELRS", "🔍 DEBUG LOGGING: DISABLED via " + buttonType);
            if (tvGamepadButtons != null) {
                tvGamepadButtons.post(() -> tvGamepadButtons.setText("DEBUG LOGGING: OFF"));
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (tvGamepadButtons != null) {
                        tvGamepadButtons.setText("Buttons: ---");
                    }
                }, 1000);
            }
            return true;
        }
        
        // Handle safe exit sequence release
        if (keyCode == KeyEvent.KEYCODE_X || keyCode == KeyEvent.KEYCODE_BUTTON_X) {
            xButtonPressed = false;
            resetSafeExitSequence();
            android.util.Log.d("ELRS", "X button released - safe exit sequence deactivated");
        }
        
        if (keyCode == KeyEvent.KEYCODE_B || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            bButtonPressed = false;
            resetSafeExitSequence();
            android.util.Log.d("ELRS", "B button released - safe exit sequence deactivated");
        }

        if (isGamepadSource || is8BitDo || isDm8150Audio) {
            
            // Update debug display with button release (except for blocked keys)
            if (tvGamepadButtons != null && keyCode != KeyEvent.KEYCODE_B && keyCode != KeyEvent.KEYCODE_BUTTON_B && keyCode != KeyEvent.KEYCODE_BACK) {
                String buttonName = KeyEvent.keyCodeToString(keyCode);
                final String buttonText = "Button: " + buttonName + " UP";
                tvGamepadButtons.post(() -> tvGamepadButtons.setText(buttonText));
                
                // Clear button display after a short delay
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (tvGamepadButtons != null) {
                        tvGamepadButtons.setText("Buttons: ---");
                    }
                }, 500);
            }
        }
        return super.onKeyUp(keyCode, event);
    }

    private static float getAxis(MotionEvent e, int axis) {
        InputDevice device = e.getDevice();
        InputDevice.MotionRange range = device.getMotionRange(axis, e.getSource());
        if (range == null) return 0f;
        float v = e.getAxisValue(axis);
        float dz = Math.max(0.05f, range.getFlat() / Math.max(1f, range.getRange()));
        if (Math.abs(v) < dz) v = 0f;
        if (v < -1f) v = -1f; if (v > 1f) v = 1f;
        return v;
    }


    
    // Override back button to prevent accidental exits
    @Override
    public void onBackPressed() {
        android.util.Log.d("ELRS", "Back button blocked - use B + X to exit safely");
        if (tvGamepadButtons != null) {
            tvGamepadButtons.post(() -> tvGamepadButtons.setText("EXIT BLOCKED - Use B + X"));
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (tvGamepadButtons != null) {
                    tvGamepadButtons.setText("Buttons: ---");
                }
            }, 2000);
        }
        // Don't call super.onBackPressed() to block the exit
    }
    
    private void checkSafeExitSequence() {
        if (xButtonPressed && bButtonPressed) {
            if (safeExitStartTime == 0) {
                safeExitStartTime = System.currentTimeMillis();
                android.util.Log.d("ELRS", "Safe exit sequence started - hold for 3 seconds");
                
                // STOP background input processing when B+X are pressed
                backgroundInputEnabled = false;
                android.util.Log.d("ELRS", "Background input processing DISABLED - drone control stopped");
                
                // Show danger dialog with countdown
                showExitCountdownDialog();
            }
        }
    }
    
    private void showExitCountdownDialog() {
        runOnUiThread(() -> {
            if (exitDialog != null && exitDialog.isShowing()) {
                exitDialog.dismiss();
            }
            
            // Create custom view with red background and blinking border
            android.widget.LinearLayout customLayout = new android.widget.LinearLayout(this);
            customLayout.setOrientation(android.widget.LinearLayout.VERTICAL);
            customLayout.setPadding(40, 40, 40, 40);
            customLayout.setBackgroundColor(0xFF330000); // Dark red background
            
            // Make layout focusable to capture input events
            customLayout.setFocusable(true);
            customLayout.setFocusableInTouchMode(true);
            
            // Danger icon
            android.widget.TextView iconView = new android.widget.TextView(this);
            iconView.setText("⚠️ DANGER ⚠️");
            iconView.setTextSize(24);
            iconView.setTextColor(0xFFFF0000); // Bright red
            iconView.setGravity(android.view.Gravity.CENTER);
            iconView.setPadding(0, 0, 0, 20);
            customLayout.addView(iconView);
            
            // Warning message
            android.widget.TextView messageView = new android.widget.TextView(this);
            messageView.setText("FLIGHT CONTROLLER EXIT\nThis may cause drone crash!\nHold B + X to continue");
            messageView.setTextSize(16);
            messageView.setTextColor(0xFFFFAAAA);
            messageView.setGravity(android.view.Gravity.CENTER);
            messageView.setPadding(0, 0, 0, 20);
            customLayout.addView(messageView);
            
            // Countdown display
            android.widget.TextView countdownView = new android.widget.TextView(this);
            countdownView.setText("3");
            countdownView.setTextSize(48);
            countdownView.setTextColor(0xFFFF0000); // Bright red countdown
            countdownView.setGravity(android.view.Gravity.CENTER);
            countdownView.setTypeface(null, android.graphics.Typeface.BOLD);
            customLayout.addView(countdownView);
            
            // Create dialog with special flags to prevent dismissal
            android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
            builder.setView(customLayout);
            builder.setCancelable(false); // Prevent back button dismissal
            builder.setPositiveButton("CANCEL", (dialog, id) -> {
                resetSafeExitSequence();
                dialog.dismiss();
            });
            
            exitDialog = builder.create();
            
            // Set dialog to be persistent and immune to external events
            exitDialog.setCancelable(false);
            exitDialog.setCanceledOnTouchOutside(false);
            
            // Override key events to prevent controller input from dismissing dialog
            exitDialog.setOnKeyListener((dialog, keyCode, event) -> {
                android.util.Log.d("ELRS", "Dialog received key event: " + keyCode + " action: " + event.getAction());
                
                // Handle B and X button events within dialog context
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_B || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                        bButtonPressed = true;
                        android.util.Log.d("ELRS", "Dialog: B button pressed");
                    } else if (keyCode == KeyEvent.KEYCODE_X || keyCode == KeyEvent.KEYCODE_BUTTON_X) {
                        xButtonPressed = true;
                        android.util.Log.d("ELRS", "Dialog: X button pressed");
                    }
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (keyCode == KeyEvent.KEYCODE_B || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                        bButtonPressed = false;
                        android.util.Log.d("ELRS", "Dialog: B button released - cancelling exit");
                        resetSafeExitSequence();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_X || keyCode == KeyEvent.KEYCODE_BUTTON_X) {
                        xButtonPressed = false;
                        android.util.Log.d("ELRS", "Dialog: X button released - cancelling exit");
                        resetSafeExitSequence();
                        return true;
                    }
                }
                
                // Consume all key events to prevent them from reaching the main activity
                return true;
            });
            
            exitDialog.show();
            
            // Configure window flags to make dialog persistent
            android.view.Window window = exitDialog.getWindow();
            if (window != null) {
                window.setBackgroundDrawableResource(android.R.color.transparent);
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);
            }
            
            // Request focus for the custom layout to capture input
            customLayout.requestFocus();
            
            // Start countdown with blinking effect
            Handler handler = new Handler(Looper.getMainLooper());
            Runnable exitCountdown = new Runnable() {
                int countdown = 3;
                boolean blink = false;
                
                @Override
                public void run() {
                    if (xButtonPressed && bButtonPressed && safeExitStartTime > 0 && exitDialog != null && exitDialog.isShowing()) {
                        if (countdown > 0) {
                            // Update countdown text in red
                            countdownView.setText(String.valueOf(countdown));
                            countdownView.setTextColor(blink ? 0xFFFF0000 : 0xFFFF6666); // Blinking red
                            
                            // Update gamepad display
                            if (tvGamepadButtons != null) {
                                tvGamepadButtons.post(() -> tvGamepadButtons.setText("⚠️ DANGER EXIT: " + countdown + "s"));
                            }
                            
                            blink = !blink;
                            countdown--;
                            handler.postDelayed(this, 500); // Blink every 500ms, countdown every 1000ms
                        } else {
                            // Exit approved
                            android.util.Log.d("ELRS", "Safe exit sequence completed - exiting application");
                            countdownView.setText("EXITING!");
                            countdownView.setTextColor(0xFFFF0000);
                            
                            if (tvGamepadButtons != null) {
                                tvGamepadButtons.post(() -> tvGamepadButtons.setText("⚠️ EXITING SAFELY..."));
                            }
                            
                            // Give a moment for the message to show
                            handler.postDelayed(() -> {
                                if (exitDialog != null && exitDialog.isShowing()) {
                                    exitDialog.dismiss();
                                }
                                finish();
                                System.exit(0);
                            }, 1000);
                        }
                    } else {
                        if (exitDialog != null && exitDialog.isShowing()) {
                            exitDialog.dismiss();
                        }
                        resetSafeExitSequence();
                    }
                }
            };
            handler.post(exitCountdown);
        });
    }
    
    private void resetSafeExitSequence() {
        if (safeExitStartTime > 0) {
            android.util.Log.d("ELRS", "Safe exit sequence cancelled");
            safeExitStartTime = 0;
            
            // RE-ENABLE background input processing when B+X are released
            backgroundInputEnabled = true;
            android.util.Log.d("ELRS", "Background input processing ENABLED - drone control resumed");
            
            // Dismiss dialog if showing
            if (exitDialog != null && exitDialog.isShowing()) {
                exitDialog.dismiss();
            }
            
            if (tvGamepadButtons != null) {
                tvGamepadButtons.post(() -> tvGamepadButtons.setText("EXIT CANCELLED"));
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (tvGamepadButtons != null) {
                        tvGamepadButtons.setText("Buttons: ---");
                    }
                }, 1000);
            }
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        currentInstance = null; // Clear static reference
        
        // Clean up exit dialog
        if (exitDialog != null && exitDialog.isShowing()) {
            exitDialog.dismiss();
        }
        
        stopDeviceMonitor();
        try { unregisterReceiver(permRx); } catch (Exception ignored) {}
        input.unregisterInputDeviceListener(this);
        UsbBridge.close();
        // TODO: Re-enable when CMake build is fixed
        // nativeStop();
    }

    private String dumpDevice(UsbDevice d){
        StringBuilder s = new StringBuilder();
        s.append(String.format("%04x:%04x ifaces=%d ", d.getVendorId(), d.getProductId(), d.getInterfaceCount()));
        for (int i=0;i<d.getInterfaceCount();i++){
            UsbInterface inf = d.getInterface(i);
            s.append("[i").append(i).append(":");
            for (int e=0;e<inf.getEndpointCount();e++){
                UsbEndpoint ep = inf.getEndpoint(e);
                String dir = ep.getDirection()==UsbConstants.USB_DIR_OUT?"OUT":"IN";
                String typ = (ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK)?"BULK":
                        (ep.getType()==UsbConstants.USB_ENDPOINT_XFER_INT) ?"INT":
                                (ep.getType()==UsbConstants.USB_ENDPOINT_XFER_ISOC)?"ISOC":"CTRL";
                s.append(typ).append(dir).append("#").append(e).append(" ");
            }
            s.append("] ");
        }
        return s.toString();
    }

    // Returns true if the device exposes at least one BULK OUT endpoint
    private static boolean hasBulkOut(UsbDevice d){
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface iface = d.getInterface(i);
            for (int e = 0; e < iface.getEndpointCount(); e++) {
                UsbEndpoint ep = iface.getEndpoint(e);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                    return true;
                }
            }
        }
        return false;
    }


    @Override public void onInputDeviceAdded(int id) {
        android.util.Log.d("ELRS", "Input device added: " + id);
        checkControllerStatus();
    }
    
    @Override public void onInputDeviceRemoved(int id) {
        android.util.Log.d("ELRS", "Input device removed: " + id);
        // Force controller check with explicit remove event
        boolean controller = detectController(true);
        if (!controller) {
            android.util.Log.d("ELRS", "Controller disconnected by removal event");
            updateControllerStatus(false);
        } else {
            checkControllerStatus();
        }
    }
    
    @Override public void onInputDeviceChanged(int id) {
        android.util.Log.d("ELRS", "Input device changed: " + id);
        checkControllerStatus();
    }
    
    private void checkControllerStatus() {
        boolean controller = detectController(true);
        updateControllerStatus(controller);
    }
    
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // Prevent any input events from interfering with the exit dialog
        if (exitDialog != null && exitDialog.isShowing()) {
            // Check if it's B or X button press/release
            if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_B || 
                event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_X) {
                // Let the dialog handle these specific buttons
                return exitDialog.dispatchKeyEvent(event);
            }
            // Block all other input events
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void setupSafetyControls() {
        Button btnArmDisarm = findViewById(R.id.btnArmDisarm);
        Button btnEmergencyStop = findViewById(R.id.btnEmergencyStop);
        
        if (btnArmDisarm != null) {
            btnArmDisarm.setOnClickListener(v -> toggleArmState());
        }
        
        if (btnEmergencyStop != null) {
            btnEmergencyStop.setOnClickListener(v -> emergencyStop());
        }
        
        // Start safety status update timer
        startSafetyStatusUpdates();
    }
    
    private void toggleArmState() {
        try {
            boolean currentlyArmed = nativeIsArmed();
            
            if (!currentlyArmed) {
                // Attempting to arm - check safety gates
                if (!canArm()) {
                    showArmingFailedDialog();
                    return;
                }
                
                // Show arming confirmation dialog
                showArmingDialog();
            } else {
                // Disarming
                nativeSetArmed(false);
                updateSafetyStatus();
                android.util.Log.d("ELRS", "Disarmed by user");
            }
        } catch (Exception e) {
            android.util.Log.e("ELRS", "Error toggling arm state", e);
        }
    }
    
    private boolean canArm() {
        // Check safety gates
        boolean superGConnected = this.superGConnected;
        // Note: g_thr is not accessible here, we'll check throttle in native code
        
        return superGConnected;
    }
    
    private void showArmingDialog() {
        if (isFinishing()) return;
        
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("ARM CONFIRMATION")
               .setMessage("⚠️ DANGER: This will ARM the aircraft!\n\n" +
                          "Ensure:\n" +
                          "• Props are OFF or quad is secure\n" +
                          "• Throttle is at minimum\n" +
                          "• You are ready to fly\n\n" +
                          "Proceed with ARMING?")
               .setIcon(android.R.drawable.ic_dialog_alert)
               .setPositiveButton("ARM", (dialog, which) -> {
                   nativeSetArmed(true);
                   updateSafetyStatus();
                   android.util.Log.d("ELRS", "Armed by user confirmation");
               })
               .setNegativeButton("Cancel", null)
               .create()
               .show();
    }
    
    private void showArmingFailedDialog() {
        if (isFinishing()) return;
        
        String reason = "";
        if (!superGConnected) reason += "• SuperG not connected\n";
        
        android.app.AlertDialog.Builder builder = new android.app.AlertDialog.Builder(this);
        builder.setTitle("CANNOT ARM")
               .setMessage("Safety gates failed:\n\n" + reason + 
                          "\nFix these issues before arming.")
               .setIcon(android.R.drawable.ic_dialog_alert)
               .setPositiveButton("OK", null)
               .create()
               .show();
    }
    
    private void emergencyStop() {
        try {
            nativeEmergencyStop();
            updateSafetyStatus();
            android.util.Log.w("ELRS", "EMERGENCY STOP activated");
            
            // Show brief confirmation
            android.widget.Toast.makeText(this, "🛑 EMERGENCY STOP", android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            android.util.Log.e("ELRS", "Error during emergency stop", e);
        }
    }
    
    private void startSafetyStatusUpdates() {
        // Update safety status every 500ms
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                if (!isFinishing()) {
                    updateSafetyStatus();
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this, 500);
                }
            }
        });
    }
    
    private void updateSafetyStatus() {
        try {
            boolean armed = nativeIsArmed();
            boolean linkOk = nativeIsLinkOk();
            
            Button btnArmDisarm = findViewById(R.id.btnArmDisarm);
            TextView tvSafetyStatus = findViewById(R.id.tvSafetyStatus);
            TextView tvLinkStatus = findViewById(R.id.tvLinkStatus);
            
            if (btnArmDisarm != null) {
                btnArmDisarm.setText(armed ? "DISARM" : "ARM");
                btnArmDisarm.setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                    armed ? 0xff00cc00 : 0xffcc3333)); // Green when armed, red when disarmed
            }
            
            if (tvSafetyStatus != null) {
                tvSafetyStatus.setText(armed ? "SAFETY: ARMED" : "SAFETY: DISARMED");
                tvSafetyStatus.setTextColor(armed ? 0xffff3333 : 0xff00ff00); // Red when armed, green when disarmed
            }
            
            if (tvLinkStatus != null) {
                tvLinkStatus.setText(linkOk ? "LINK: OK" : "LINK: UNKNOWN");
                tvLinkStatus.setTextColor(linkOk ? 0xff00ff00 : 0xff888888); // Green when OK, gray when unknown
            }
        } catch (Exception e) {
            android.util.Log.e("ELRS", "Error updating safety status", e);
        }
    }

    // Static reference to current instance for callbacks
    private static MainActivity currentInstance = null;

    // Telemetry callback from native layer
    public static void onTelemetryData(String type, int param1, int param2, int param3, int param4, int param5) {
        MainActivity instance = currentInstance;
        if (instance == null) return;
        
        // This is called from native thread, need to post to UI thread
        if ("LINK_STATS".equals(type)) {
            int rssi1 = param1;
            int rssi2 = param2;
            int lq = param3;
            int snr = param4;
            int txPower = param5;
            
            // Post to UI thread
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.post(() -> instance.updateLinkTelemetry(rssi1, rssi2, lq, snr, txPower));
        } else if ("BATTERY".equals(type)) {
            int voltage = param1; // mV
            int current = param2; // mA
            int capacity = param3; // mAh
            
            // Post to UI thread
            android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
            handler.post(() -> instance.updateBatteryTelemetry(voltage, current, capacity));
        }
    }
    
    private void updateLinkTelemetry(int rssi1, int rssi2, int lq, int snr, int txPower) {
        TextView tvRSSI = findViewById(R.id.tvRSSI);
        TextView tvLinkQuality = findViewById(R.id.tvLinkQuality);
        
        if (tvRSSI != null) {
            int bestRSSI = Math.max(rssi1, rssi2);
            String rssiText = String.format("RSSI: %d dBm", bestRSSI);
            tvRSSI.setText(rssiText);
            
            // Color code based on signal strength
            if (bestRSSI > -70) {
                tvRSSI.setTextColor(0xff00ff00); // Green - excellent
            } else if (bestRSSI > -85) {
                tvRSSI.setTextColor(0xffffff00); // Yellow - good
            } else if (bestRSSI > -100) {
                tvRSSI.setTextColor(0xffff8800); // Orange - poor
            } else {
                tvRSSI.setTextColor(0xffff0000); // Red - critical
            }
        }
        
        if (tvLinkQuality != null) {
            String lqText = String.format("LQ: %d%%", lq);
            tvLinkQuality.setText(lqText);
            
            // Color code based on link quality
            if (lq > 80) {
                tvLinkQuality.setTextColor(0xff00ff00); // Green - excellent
            } else if (lq > 60) {
                tvLinkQuality.setTextColor(0xffffff00); // Yellow - good
            } else if (lq > 40) {
                tvLinkQuality.setTextColor(0xffff8800); // Orange - poor
            } else {
                tvLinkQuality.setTextColor(0xffff0000); // Red - critical
            }
        }
    }
    
    private void updateBatteryTelemetry(int voltageMv, int currentMa, int capacityMah) {
        TextView tvBattery = findViewById(R.id.tvBattery);
        
        if (tvBattery != null) {
            float voltageV = voltageMv / 1000.0f;
            String batteryText = String.format("BATT: %.2fV", voltageV);
            tvBattery.setText(batteryText);
            
            // Color code based on voltage (assuming 4S LiPo)
            if (voltageV > 15.6f) {
                tvBattery.setTextColor(0xff00ff00); // Green - full
            } else if (voltageV > 14.8f) {
                tvBattery.setTextColor(0xffffff00); // Yellow - good
            } else if (voltageV > 14.0f) {
                tvBattery.setTextColor(0xffff8800); // Orange - low
            } else {
                tvBattery.setTextColor(0xffff0000); // Red - critical
            }
        }
    }
}

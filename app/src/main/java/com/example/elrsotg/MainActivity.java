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
    private Button btnRefreshDevices;
    private TextView tvGamepadDevice, tvGamepadButtons, tvGamepadAxes, tvGamepadTriggers;
    private TextView tvControllerName;
    
    // Enhanced debug UI
    private Button btnCycleDevices, btnDumpAllDevices, btnToggleRawMode;
    private Button btnTestRoot, btnRootDetect;
    private TextView tvDeviceDetails, tvRawAxes;
    private java.util.concurrent.atomic.AtomicInteger currentDeviceIndex = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile boolean forceEnableDm8150 = false;

    // latest axes (for HUD)
    private float lastRoll=0f, lastPitch=0f, lastYaw=0f, lastThr=0f;
    
    // Device connection status
    private volatile boolean superGConnected = false;
    private volatile boolean controllerConnected = false;
    private volatile boolean usbPermissionRequested = false;

    // JNI
    public static native void nativeSetAxes(float roll, float pitch, float yaw, float thr);
    public static native void nativeStart();
    public static native void nativeStop();

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
                tvRoll.setText(String.format("ROLL: %.2f", lastRoll));
                tvPitch.setText(String.format("PITCH: %.2f", lastPitch));
                tvYaw.setText(String.format("YAW: %.2f", lastYaw));
                tvThr.setText(String.format("THR: %.2f", lastThr));
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
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);
        statusSuperG = findViewById(R.id.statusSuperG);
        statusController = findViewById(R.id.statusController);
        tvRoll   = findViewById(R.id.tvRoll);
        tvPitch  = findViewById(R.id.tvPitch);
        tvYaw    = findViewById(R.id.tvYaw);
        tvThr    = findViewById(R.id.tvThr);
        
        // Debug UI
        btnRefreshDevices = findViewById(R.id.btnRefreshDevices);
        tvGamepadDevice = findViewById(R.id.tvGamepadDevice);
        tvGamepadButtons = findViewById(R.id.tvGamepadButtons);
        tvGamepadAxes = findViewById(R.id.tvGamepadAxes);
        tvGamepadTriggers = findViewById(R.id.tvGamepadTriggers);
        tvControllerName = findViewById(R.id.tvControllerName);
        
        // Enhanced debug UI
        btnCycleDevices = findViewById(R.id.btnCycleDevices);
        btnDumpAllDevices = findViewById(R.id.btnDumpAllDevices);
        btnToggleRawMode = findViewById(R.id.btnToggleRawMode);
        tvDeviceDetails = findViewById(R.id.tvDeviceDetails);
        tvRawAxes = findViewById(R.id.tvRawAxes);
        
        // Root testing buttons
        btnTestRoot = findViewById(R.id.btnTestRoot);
        btnRootDetect = findViewById(R.id.btnRootDetect);

        clearControllerDebug();
        setupDebugButtons();

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
        
        startDeviceMonitor();

        nativeStart();
        tvRoll.post(uiTick);
    }

    private void updateSuperGStatus(boolean connected) {
        superGConnected = connected;
        if (statusSuperG != null) {
            statusSuperG.post(() -> statusSuperG.setBackgroundResource(
                connected ? R.drawable.status_circle_green : R.drawable.status_circle_red));
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
                    if (isDm8150Audio && (forceEnableDm8150 || !device.getMotionRanges().isEmpty())) {
                        if (verboseLogging) {
                            android.util.Log.d("ELRS", "  -> DM8150 AUDIO DEVICE WITH MOTION RANGES (likely 8BitDo)!");
                            android.util.Log.d("ELRS", "     Force enabled: " + forceEnableDm8150);
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

    private void setupDebugButtons() {
        // Setup refresh button
        if (btnRefreshDevices != null) {
            btnRefreshDevices.setOnClickListener(v -> {
                android.util.Log.d("ELRS", "=== MANUAL DEVICE REFRESH ===");
                refreshAllDevices();
            });
        }
        
        // Setup device cycling button
        if (btnCycleDevices != null) {
            btnCycleDevices.setOnClickListener(v -> cycleToNextDevice());
        }
        
        // Setup dump all devices button
        if (btnDumpAllDevices != null) {
            btnDumpAllDevices.setOnClickListener(v -> {
                dumpAllInputDevices();
                dumpSystemDeviceInfo();
            });
        }
        
        // Setup toggle raw mode (force enable dm8150)
        if (btnToggleRawMode != null) {
            btnToggleRawMode.setOnClickListener(v -> {
                forceEnableDm8150 = !forceEnableDm8150;
                btnToggleRawMode.setText(forceEnableDm8150 ? "Disable dm8150" : "Force Enable dm8150");
                android.util.Log.d("ELRS", "Force enable dm8150: " + forceEnableDm8150);
                refreshAllDevices();
            });
        }
        
        // Setup root testing buttons
        if (btnTestRoot != null) {
            btnTestRoot.setOnClickListener(v -> testRootAccess());
        }
        
        if (btnRootDetect != null) {
            btnRootDetect.setOnClickListener(v -> runRootBasedDetection());
        }
        
        // Start real-time axis monitoring
        startAxisMonitoring();
    }
    
    private void testRootAccess() {
        android.util.Log.d("ELRS", "=== Testing Root Access ===");
        try {
            java.lang.Process process = Runtime.getRuntime().exec("su -c 'id'");
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            
            String line = reader.readLine();
            if (line != null && line.contains("uid=0")) {
                android.util.Log.d("ELRS", "✓ Root access confirmed: " + line);
                if (tvDeviceDetails != null) {
                    tvDeviceDetails.post(() -> tvDeviceDetails.setText("Root Access: CONFIRMED\n" + line));
                }
            } else {
                android.util.Log.w("ELRS", "✗ Root access failed: " + line);
                if (tvDeviceDetails != null) {
                    tvDeviceDetails.post(() -> tvDeviceDetails.setText("Root Access: FAILED\n" + line));
                }
            }
            
            reader.close();
            process.waitFor();
        } catch (Exception e) {
            android.util.Log.e("ELRS", "Root test failed", e);
            if (tvDeviceDetails != null) {
                tvDeviceDetails.post(() -> tvDeviceDetails.setText("Root Access: ERROR\n" + e.getMessage()));
            }
        }
    }
    
    private void runRootBasedDetection() {
        android.util.Log.d("ELRS", "=== Running Root-Based Detection ===");
        
        // Run comprehensive root-based device detection
        boolean foundViaRoot = checkUsbDevicesViaRoot();
        
        if (foundViaRoot) {
            android.util.Log.d("ELRS", "✓ 8BitDo controller found via root methods!");
            updateControllerStatus(true);
        } else {
            android.util.Log.w("ELRS", "✗ 8BitDo controller not found via root methods");
        }
        
        // Update UI with results
        if (tvDeviceDetails != null) {
            String result = foundViaRoot ? "Root Detection: 8BitDo FOUND" : "Root Detection: 8BitDo NOT FOUND";
            tvDeviceDetails.post(() -> tvDeviceDetails.setText(result + "\nCheck ADB logs for details"));
        }
    }
    
    private void cycleToNextDevice() {
        if (input == null) return;
        
        int[] deviceIds = input.getInputDeviceIds();
        if (deviceIds.length == 0) {
            if (tvDeviceDetails != null) {
                tvDeviceDetails.post(() -> tvDeviceDetails.setText("No input devices found"));
            }
            return;
        }
        
        int index = currentDeviceIndex.incrementAndGet() % deviceIds.length;
        currentDeviceIndex.set(index);
        InputDevice device = input.getInputDevice(deviceIds[index]);
        if (device != null) {
            dumpDeviceInfo(device);
        }
    }
    
    private void dumpAllInputDevices() {
        if (input == null) return;
        
        int[] deviceIds = input.getInputDeviceIds();
        android.util.Log.d("ELRS", "=== DUMPING ALL INPUT DEVICES ===");
        android.util.Log.d("ELRS", "Found " + deviceIds.length + " input devices");
        
        StringBuilder summary = new StringBuilder();
        summary.append("All Devices (").append(deviceIds.length).append("):\n");
        
        for (int i = 0; i < deviceIds.length; i++) {
            InputDevice device = input.getInputDevice(deviceIds[i]);
            if (device != null) {
                android.util.Log.d("ELRS", "--- Device " + i + " ---");
                dumpDeviceInfo(device);
                
                summary.append(i).append(": ").append(device.getName()).append("\n");
            }
        }
        
        if (tvDeviceDetails != null) {
            tvDeviceDetails.post(() -> tvDeviceDetails.setText(summary.toString()));
        }
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
                if (tvRawAxes != null && input != null) {
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
                    
                    tvRawAxes.post(() -> tvRawAxes.setText(axisInfo.toString()));
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
        if (tvGamepadTriggers != null) {
            tvGamepadTriggers.post(() -> tvGamepadTriggers.setText("Triggers: ---"));
        }
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
        
        if ((isGamepadSource || is8BitDo || (isDm8150Audio && forceEnableDm8150)) && e.getAction() == MotionEvent.ACTION_MOVE) {

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
            if (tvGamepadTriggers != null) {
                final String triggerText = String.format("Triggers: LT:%.2f RT:%.2f RZ:%.2f", 
                    getAxis(e, MotionEvent.AXIS_LTRIGGER), getAxis(e, MotionEvent.AXIS_RTRIGGER),
                    getAxis(e, MotionEvent.AXIS_RZ));
                tvGamepadTriggers.post(() -> tvGamepadTriggers.setText(triggerText));
            }
            
            // Update real-time axis monitor with live values
            if (tvRawAxes != null && device != null) {
                StringBuilder liveAxes = new StringBuilder();
                liveAxes.append("LIVE: ").append(device.getName()).append("\n");
                liveAxes.append("X:").append(String.format("%.2f", getAxis(e, MotionEvent.AXIS_X)));
                liveAxes.append(" Y:").append(String.format("%.2f", getAxis(e, MotionEvent.AXIS_Y)));
                liveAxes.append(" RX:").append(String.format("%.2f", getAxis(e, MotionEvent.AXIS_RX)));
                liveAxes.append(" RY:").append(String.format("%.2f", getAxis(e, MotionEvent.AXIS_RY))).append("\n");
                liveAxes.append("LT:").append(String.format("%.2f", getAxis(e, MotionEvent.AXIS_LTRIGGER)));
                liveAxes.append(" RT:").append(String.format("%.2f", getAxis(e, MotionEvent.AXIS_RTRIGGER)));
                liveAxes.append(" RZ:").append(String.format("%.2f", getAxis(e, MotionEvent.AXIS_RZ)));
                
                tvRawAxes.post(() -> tvRawAxes.setText(liveAxes.toString()));
            }

            lastRoll = rx; lastPitch = ry; lastYaw = rz; lastThr = thr;
            nativeSetAxes(rx, ry, rz, thr);
            return true;
        }
        return super.onGenericMotionEvent(e);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
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
        
        if (isGamepadSource || is8BitDo || (isDm8150Audio && forceEnableDm8150)) {
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
            
            // Update debug display with button press
            if (tvGamepadButtons != null) {
                String buttonName = KeyEvent.keyCodeToString(keyCode);
                final String buttonText = "Button: " + buttonName + " DOWN";
                tvGamepadButtons.post(() -> tvGamepadButtons.setText(buttonText));
                
                // Clear button display after a short delay
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    if (tvGamepadButtons != null) {
                        tvGamepadButtons.setText("Buttons: ---");
                    }
                }, 500);
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
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
        
        if (isGamepadSource || is8BitDo || (isDm8150Audio && forceEnableDm8150)) {
            
            // Update debug display with button release
            if (tvGamepadButtons != null) {
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

    @Override protected void onDestroy() {
        super.onDestroy();
        stopDeviceMonitor();
        try { unregisterReceiver(permRx); } catch (Exception ignored) {}
        input.unregisterInputDeviceListener(this);
        UsbBridge.close();
        nativeStop();
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
}

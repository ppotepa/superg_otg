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

        clearControllerDebug();        // Setup refresh button
        btnRefreshDevices.setOnClickListener(v -> {
            android.util.Log.d("ELRS", "=== MANUAL DEVICE REFRESH ===");
            refreshAllDevices();
        });

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

        boolean controller = detectController(true);
        updateControllerStatus(controller);
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
        // More inclusive check for gamepad input
        if (((e.getSource() & InputDevice.SOURCE_JOYSTICK) != 0 ||
             (e.getSource() & InputDevice.SOURCE_GAMEPAD) != 0) &&
             e.getAction() == MotionEvent.ACTION_MOVE) {

            // Debug: Log motion event details
            if (!controllerConnected) {
                InputDevice device = e.getDevice();
                android.util.Log.d("ELRS", "Motion event from device: " + 
                    (device != null ? device.getName() : "unknown"));
                android.util.Log.d("ELRS", String.format("Source: 0x%08X", e.getSource()));
            }

            // Controller is active - update status
            if (!controllerConnected) {
                android.util.Log.d("ELRS", "Controller detected via motion event!");
                updateControllerStatus(true);
            }

            // Update debug display with device info
            InputDevice device = e.getDevice();
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

            lastRoll = rx; lastPitch = ry; lastYaw = rz; lastThr = thr;
            nativeSetAxes(rx, ry, rz, thr);
            return true;
        }
        return super.onGenericMotionEvent(e);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Check if this is a gamepad key
        if (((event.getSource() & InputDevice.SOURCE_GAMEPAD) != 0) ||
            ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != 0)) {
            
            // Controller is active - update status
            InputDevice device = event.getDevice();
            if (!controllerConnected) {
                android.util.Log.d("ELRS", "Key event from device: " + 
                    (device != null ? device.getName() : "unknown"));
                android.util.Log.d("ELRS", "Controller detected via key event!");
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
        // Check if this is a gamepad key
        if (((event.getSource() & InputDevice.SOURCE_GAMEPAD) != 0) ||
            ((event.getSource() & InputDevice.SOURCE_JOYSTICK) != 0)) {
            
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

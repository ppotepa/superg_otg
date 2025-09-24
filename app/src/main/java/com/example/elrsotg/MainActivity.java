package com.example.elrsotg;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.*;
import android.hardware.input.InputManager;
import android.hardware.usb.*;
import android.os.*;
import android.view.*;
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

    // latest axes (for HUD)
    private float lastRoll=0f, lastPitch=0f, lastYaw=0f, lastThr=0f;
    
    // Device connection status
    private boolean superGConnected = false;
    private boolean controllerConnected = false;

    // JNI
    public static native void nativeSetAxes(float roll, float pitch, float yaw, float thr);
    public static native void nativeStart();
    public static native void nativeStop();

    private final Runnable uiTick = new Runnable() {
        @Override public void run() {
            if (tvRoll != null) {
                tvRoll.setText(String.format("ROLL: %.2f", lastRoll));
                tvPitch.setText(String.format("PITCH: %.2f", lastPitch));
                tvYaw.setText(String.format("YAW: %.2f", lastYaw));
                tvThr.setText(String.format("THR: %.2f", lastThr));
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
                    boolean ok = UsbBridge.open(mgr, dev);
                    updateSuperGStatus(ok);
                } else {
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

        // Check initial controller status
        checkControllerStatus();
        
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
        controllerConnected = connected;
        if (statusController != null) {
            statusController.post(() -> statusController.setBackgroundResource(
                connected ? R.drawable.status_circle_green : R.drawable.status_circle_red));
        }
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
                mgr.requestPermission(d, permIntent);
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
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != 0 &&
                e.getAction() == MotionEvent.ACTION_MOVE) {

            // Controller is active - update status
            if (!controllerConnected) {
                updateControllerStatus(true);
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

            lastRoll = rx; lastPitch = ry; lastYaw = rz; lastThr = thr;
            nativeSetAxes(rx, ry, rz, thr);
            return true;
        }
        return super.onGenericMotionEvent(e);
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
        checkControllerStatus();
    }
    
    @Override public void onInputDeviceRemoved(int id) {
        checkControllerStatus();
    }
    
    @Override public void onInputDeviceChanged(int id) {
        checkControllerStatus();
    }
    
    private void checkControllerStatus() {
        boolean hasController = false;
        int[] deviceIds = input.getInputDeviceIds();
        for (int deviceId : deviceIds) {
            InputDevice device = input.getInputDevice(deviceId);
            if (device != null && (device.getSources() & InputDevice.SOURCE_JOYSTICK) != 0) {
                hasController = true;
                break;
            }
        }
        updateControllerStatus(hasController);
    }
}

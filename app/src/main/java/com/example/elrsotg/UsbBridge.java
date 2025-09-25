
package com.example.elrsotg;

import android.hardware.usb.*;

public class UsbBridge {
    private static UsbDeviceConnection conn;
    private static UsbEndpoint epOut;
    private static UsbEndpoint epIn;
    private static UsbInterface claimed;

    public static synchronized boolean open(UsbManager mgr, UsbDevice dev){
        close();
        conn = mgr.openDevice(dev);
        if (conn == null) return false;

        // Find interface + BULK OUT/IN endpoints
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface iface = dev.getInterface(i);
            UsbEndpoint out = null, in = null;
            
            // Find both OUT and IN endpoints
            for (int e = 0; e < iface.getEndpointCount(); e++) {
                UsbEndpoint ep = iface.getEndpoint(e);
                if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    if (ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                        out = ep;
                    } else if (ep.getDirection() == UsbConstants.USB_DIR_IN) {
                        in = ep;
                    }
                }
            }
            
            // Claim interface if we found both endpoints
            if (out != null && in != null) {
                if (!conn.claimInterface(iface, true)) continue;
                epOut = out;
                epIn = in;
                claimed = iface;

                // CP210x init sequence
                // enable UART
                conn.controlTransfer(0x41, 0x00, 0x0001, claimed.getId(), null, 0, 1000);
                // 8N1
                conn.controlTransfer(0x41, 0x03, 0x0800, claimed.getId(), null, 0, 1000);
                // baud 460800 (0x00070800 LE)
                byte[] b = new byte[]{ (byte)0x00, (byte)0x08, (byte)0x07, (byte)0x00 };
                conn.controlTransfer(0x41, 0x1E, 0, claimed.getId(), b, 4, 1000);
                // DTR/RTS ON (0x0101 | 0x0202)
                conn.controlTransfer(0x41, 0x07, 0x0303, claimed.getId(), null, 0, 1000);

                return true;
            }
        }
        close();
        return false;
    }

    public static synchronized void close(){
        if (conn != null) {
            try { if (claimed != null) conn.releaseInterface(claimed); } catch (Exception ignored) {}
            try { conn.close(); } catch (Exception ignored) {}
        }
        claimed = null;
        epOut = null;
        epIn = null;
        conn = null;
    }

    public static synchronized boolean isOpen() {
        return conn != null && epOut != null && epIn != null;
    }

    // JNI entry point to write bytes
    public static synchronized int write(byte[] data, int len, int timeoutMs){
        if (conn == null || epOut == null) return -1;
        if (len > data.length) len = data.length;
        return conn.bulkTransfer(epOut, data, len, timeoutMs);
    }

    // JNI entry point to read bytes for telemetry
    public static synchronized int read(byte[] buffer, int timeoutMs){
        if (conn == null || epIn == null) return -1;
        return conn.bulkTransfer(epIn, buffer, buffer.length, timeoutMs);
    }
}

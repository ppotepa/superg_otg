
alright Pawel — here’s a clean, end-to-end, 10-step plan to turn your Android phone into a “software radio” that talks to the SuperG nano TX over USB-OTG, speaks CRSF/ELRS, pairs, and flies the quad. I’ll anchor the tricky protocol bits with sources and point out a couple of fixes in your C++.

---

# 1) Topology, safety, and prerequisites (don’t skip)

**Topology**: Android (USB host) → SuperG nano TX (ELRS 2.4) → RF link → ELRS RX on the quad → FC UART (CRSF) → Betaflight.
**Safety**: test with props off; set a hard **disarm** default; stop sending frames on app pause/USB detach.
**Versions**: keep TX & RX on similar ELRS major/minor.
**Betaflight**: on the FC, enable **Serial RX** on the correct UART and set **Receiver mode = Serial (via UART)** + **Serial Provider = CRSF**; save. ([Betaflight][1])

**Binding (high-level)**:

* Easiest & most robust: **Bind Phrase**: set the *same* phrase on both TX & RX (via Configurator/WebUI). No button dance needed. ([ExpressLRS][2])
* Alternative: **Manual bind** (button or Betaflight “Bind” in Receiver tab on recent ELRS). ([Facebook][3])

---

# 2) Android USB “bridge” (Java/Kotlin)

You already have a JNI hook to a `UsbBridge.write(byte[],len,timeout)`. Implement it with Android’s USB Host API:

```java
public final class UsbBridge {
  private static UsbDeviceConnection conn;
  private static UsbEndpoint epOut;

  public static boolean open(Context ctx, UsbDevice dev) {
    UsbManager mgr = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
    if (!mgr.hasPermission(dev)) return false; // request via PendingIntent first

    UsbInterface intf = null; UsbEndpoint out = null;
    for (int i=0;i<dev.getInterfaceCount();i++) {
      UsbInterface itf = dev.getInterface(i);
      // many ELRS TX enumerate as CDC-ACM; pick the bulk OUT endpoint
      for (int e=0;e<itf.getEndpointCount();e++) {
        UsbEndpoint ep = itf.getEndpoint(e);
        if (ep.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK &&
            ep.getDirection()==UsbConstants.USB_DIR_OUT) { intf = itf; out = ep; break; }
      }
      if (out!=null) break;
    }
    if (out==null) return false;

    UsbDeviceConnection c = mgr.openDevice(dev);
    if (c==null) return false;
    if (!c.claimInterface(intf, true)) { c.close(); return false; }

    // Optional (CDC-ACM): set line coding to ~420000 8N1; some firmwares ignore it.
    // c.controlTransfer(0x21, 0x20, 0, 0, new byte[]{(byte)0xA0,0x86,0x06,0,0,0,8}, 7, 100);

    conn = c; epOut = out; return true;
  }

  public static int write(byte[] buf, int len, int timeoutMs) {
    if (conn==null || epOut==null) return -1;
    return conn.bulkTransfer(epOut, buf, len, timeoutMs);
  }

  public static void close() {
    if (conn!=null) { conn.close(); conn=null; epOut=null; }
  }
}
```

That gives your native code a reliable bulk pipe to the TX module.

---

# 3) CRSF packet basics you must get right

* **Serial**: CRSF over UART uses 420000 baud, 8N1, not inverted; max frame is **64 bytes** (USB CDC often ignores “baud” but the link past the TX expects CRSF timing). ([Gist][4])
* **Framing**: `[SYNC=0xC8][LEN][TYPE][PAYLOAD...][CRC]` with **CRC-8 poly 0xD5 over bytes from TYPE to end of payload**. Your CRC implementation matches this. ([Medium][5])
* **RC channels**: use **TYPE 0x16 = RC_CHANNELS_PACKED**, 16 channels packed 11-bit each, 172..1811 counts (~1000..2000 µs). Your packer is fine. ([GitHub][6])

---

# 4) Fixes to your C++ (two important ones)

1. **MSP-over-CRSF frame** (your `buildMspCommand`)
   CRSF uses **extended-header** frames for MSP: after TYPE you must include **dest_addr** and **orig_addr**, and the **LEN** must count `TYPE + dest + orig + msp_fields + CRC`. A correct LEN for MSP-REQ is `6 + payloadSize` (TYPE + DEST + ORIG + FUNC + SIZE + payload + CRC). You currently use `8 + payloadSize` — that’s off by 2. Also set addresses correctly (see next point). ([Gist][4])

2. **Addresses**
   Use the proper CRSF addresses in the extended header:

* **DEST = 0xC8 (Flight Controller)**
* **ORIG = 0xEE (Crossfire/ELRS Transmitter)**
  Your code had `dest=0xEE` and `orig=0xEA` — flip/fix them. ([Scribd][7])

A corrected builder:

```cpp
static void buildMspCommand(uint8_t function, const uint8_t* payload, uint8_t payloadSize,
                            std::array<uint8_t,64>& out, uint8_t& outSize) {
    uint8_t idx = 0;
    out[idx++] = 0xC8;                 // SYNC
    out[idx++] = 6 + payloadSize;      // LEN = TYPE+DEST+ORIG+FUNC+SIZE+PAY + CRC
    out[idx++] = 0x7A;                 // TYPE = CRSF_FRAMETYPE_MSP_REQ
    out[idx++] = 0xC8;                 // DEST = Flight Controller
    out[idx++] = 0xEE;                 // ORIG = Transmitter
    out[idx++] = function;             // MSP function
    out[idx++] = payloadSize;          // MSP payload size
    for (uint8_t i=0; i<payloadSize; ++i) out[idx++] = payload[i];
    out[idx]   = crsf_crc8(&out[2], idx-2); // CRC over TYPE..end
    outSize    = idx + 1;
}
```

Why 0x7A? Because **MSP over CRSF uses 0x7A for requests and 0x7B for responses**. ([GitHub][8])

---

# 5) RC control loop (your `txLoop`) — the “software radio”

* **Rate**: 250 Hz (4 ms) is a good default; later add a setting for 100/250/500 Hz to match radio packet rates.
* **Mapping**: stick axes in **[-1..+1] → 1000..2000 µs → 172..1811** (your `map_stick/map_thr` do that). Keep **thr** at 1000 (min) until you explicitly ARM via AUX.
* **Channels** (typical AETR):
  ch0 Roll, ch1 Pitch, ch2 Throttle, ch3 Yaw, ch4.. for AUX. Put **AUX1 (ch4) = arm** (low=disarmed). BF expects AUX1 as arm by convention with ELRS. ([Oscar Liang][9])
* **Failsafe**: if USB disconnects or app pauses, set `g_run=false`, send one final neutral/disarm frame, then stop.

---

# 6) Pairing from the app (three reliable options)

**Option A — Bind Phrase (best):**
Require the user to set the **same Bind Phrase** on TX & RX in advance (or let your app open the TX WebUI when module is in Wi-Fi). No on-air bind needed. ([ExpressLRS][2])

**Option B — “Bind” via Betaflight (MSP over CRSF):**
Betaflight’s Receiver tab “Bind” button triggers an MSP command to put the ELRS **receiver** into bind mode. Your `nativeSendCommand("BIND")` can call `buildMspCommand(<ELRS_BIND_ID>, nullptr, 0, ...)` and send it. (The exact MSP ID is the same one BF uses; keep it configurable because IDs can shift between firmware lines. The transport is **MSP-REQ 0x7A** via extended header as above.) ([Facebook][3])

**Option C — Manual bind:**
Expose a helper page telling the user to press the RX boot/bind button, or use the TX/Lua bind, if phrases aren’t set. ([Facebook][3])

> Tip: also expose **Model Match** on/off in TX config; mismatches cause “linked but no sticks” confusion.

---

# 7) Telemetry back from the quad (status, LQ/RSSI, battery)

Add a reader thread that parses inbound CRSF frames from the TX’s **bulk IN** endpoint (or CDC read). Key types to decode first:

* **0x14 LINK_STATISTICS** → RSSI dBm, LQ, SNR (overlay in your UI; gate arming if LQ too low).
* (Optional) **0x08 BATTERY_SENSOR**, **0x1E ATTITUDE**, **0x21 FLIGHT_MODE** if your FC exposes them. ([Dokumentacja PX4][10])

This helps you confirm you’re actually linked before letting throttle move.

---

# 8) Joystick/gamepad & touch → RC mixing

On Android:

* Read sticks from **GameController API** (or on-screen UI) → normalize to **[-1..+1]**.
* Add **deadzone**, **expo**, and **rate** curves in Java before pushing into your atomics (`g_roll` etc.).
* Map buttons to **ARM (AUX1)**, **ANGLE/HORIZON/ACRO (AUX2/3)**, **BEEPER (AUX4)**, etc.
* Keep a visible **ARMED** indicator and block arming unless (a) throttle at min, (b) link ok, (c) user long-presses ARM.

---

# 9) End-to-end bring-up checklist (with what you’ll actually click)

1. Flash or confirm ELRS TX & RX versions; set **same Bind Phrase** on both. ([ExpressLRS][2])
2. Betaflight: set **Serial RX on the right UART**; **Receiver=Serial**; **Provider=CRSF**; save. ([Oscar Liang][9])
3. Power the quad (props off). RX LED should show “waiting for TX” then “linked” once the SuperG is up. ([Oscar Liang][9])
4. Plug SuperG to Android via OTG; your app **opens UsbBridge** and starts **telemetry reader** (optional).
5. Tap **Connect** → show link stats (0x14) to verify you’re actually getting traffic. ([Dokumentacja PX4][10])
6. Open **Betaflight Receiver tab** on a PC (USB to FC) — move your virtual sticks in the app and confirm channels move. ([SpeedyBee][11])
7. Assign **ARM to AUX1** in BF Modes; verify ARM toggles via app. ([Oscar Liang][9])
8. Set **rate** (100/250/500 Hz) in app to match your link profile.
9. Verify **failsafe**: unplug OTG → quad should disarm (and/or FC failsafe kicks).
10. Only then put props on and test hover in **Angle** mode first.

---

# 10) Reference constants & gotchas (copy-paste friendly)

* **SYNC**: `0xC8` (serial CRSF). **Max frame**: 64 bytes. **CRC-8**: poly `0xD5`, from TYPE..payload end. ([Medium][5])
* **Types**: `0x16` = **RC_CHANNELS_PACKED**, `0x7A`/`0x7B` = **MSP REQ/RESP**. ([Gist][4])
* **Addresses (extended header)**: `0xC8=Flight Controller`, `0xEE=Transmitter` (use these for DEST/ORIG). ([Scribd][7])
* **11-bit channel range**: **172..1811** (~1000..2000 µs mapping). ([GitHub][6])
* **Binding**: Prefer **Bind Phrase**; otherwise BF Receiver tab **Bind** uses MSP over CRSF under the hood. ([ExpressLRS][2])

---

## Small polish items for your native code

* **Detach** the JNI thread on exit (`g_vm->DetachCurrentThread()` after loop).
* **Check** `CallStaticIntMethod` return and log partial writes.
* **Throttle gating**: hold `ch2` at 1000 until ARM is asserted.
* **AUX defaults**: disarm + beeper off + modes safe.

---

If you want, I can turn this into a minimal repo layout (Gradle module + `UsbBridge`, your JNI `.so`, and a tiny activity with a virtual gamepad). But with the 10 steps above and the two key fixes (MSP frame LEN & addresses), you’ve got all pieces to **pair, send commands, and drive the quad** from Android using your current C++ core.

[1]: https://betaflight.com/docs/wiki/getting-started/setup-guide?utm_source=chatgpt.com "Setup Guide"
[2]: https://www.expresslrs.org/quick-start/binding/?utm_source=chatgpt.com "Binding ExpressLRS"
[3]: https://www.facebook.com/groups/636441730280366/posts/1390986858159179/?utm_source=chatgpt.com "Hello guys, I have a problem with my ELRS receiver, it ..."
[4]: https://gist.github.com/GOROman/9c7eadf78eb522bbb801beb9162a8db5?utm_source=chatgpt.com "ExpressLRS / CRSF Protocol 解析メモ"
[5]: https://medium.com/%40mike_polo/parsing-crsf-protocol-from-a-flight-controller-with-a-raspberry-pi-for-telemetry-data-79e9426ff943?utm_source=chatgpt.com "Parsing CRSF Protocol from a Flight Controller with ..."
[6]: https://github.com/ExpressLRS/ExpressLRS/discussions/2086?utm_source=chatgpt.com "Add support for CRSF/CrossFire 'Packed Channels' to ..."
[7]: https://www.scribd.com/document/781193820/CRSF-Rev07?utm_source=chatgpt.com "CRSF Rev07 | PDF | Parameter (Computer Programming)"
[8]: https://github.com/tbs-fpv/freedomtx/issues/26?utm_source=chatgpt.com "CRSF Protocol Repo · Issue #26 · tbs-fpv/freedomtx"
[9]: https://oscarliang.com/setup-expresslrs-2-4ghz/?utm_source=chatgpt.com "A Complete Guide to Flashing and Setting Up ExpressLRS"
[10]: https://docs.px4.io/v1.14/en/telemetry/crsf_telemetry.html?utm_source=chatgpt.com "CRSF Telemetry (TBS Crossfire Telemetry) - PX4 docs"
[11]: https://speedybee.zendesk.com/hc/en-us/articles/20286176786459-How-to-set-up-TBS-or-ELRS-receiver-in-Betaflight-configurator-on-SpeedyBee-F405-V4-flight-controller?utm_source=chatgpt.com "How to set up TBS or ELRS receiver in Betaflight configurator ..."


# A) USB & Device I/O (MVP)

* [ ] **USB permission & discovery**

  * Detect SuperG on attach; prompt for permission; remember selection.
  * ✅ *AC:* Device appears in app with VID:PID + interface info.
* [ ] **Endpoint selection & open/close**

  * Find/capture correct interface; bulk OUT for TX; bulk IN for telemetry.
  * ✅ *AC:* `open()` returns true; `close()` cleans up; no descriptor leaks.
* [ ] **Robust bulk writes**

  * Single writer thread; handles partial writes; retries on `-ETIMEDOUT`.
  * ✅ *AC:* Sustained 250 Hz frames for ≥10 min without error.
* [ ] **Telemetry read loop**

  * Non-blocking bulk IN with ring buffer; safe thread shutdown.
  * ✅ *AC:* CRSF Link Statistics frames parsed at expected rate.

# B) Native (JNI) Transport Core (MVP)

* [ ] **JNI plumbing**

  * `UsbBridge.write([B,len,timeout)` wired; thread attach/detach handled.
  * ✅ *AC:* No JNI local ref leaks; threads detach on stop.
* [ ] **CRSF builders/parsers**

  * RC_CHANNELS_PACKED (0x16) builder (your packer).
  * MSP-over-CRSF REQ (0x7A) builder (fixed LEN & addresses).
  * Generic parser for: 0x14 (Link), 0x08 (Battery), 0x1E (Attitude), 0x21 (Flight Mode).
  * ✅ *AC:* Unit tests for CRC8(0xD5); golden vectors pass.
* [ ] **Real-time TX loop**

  * 100/250/500 Hz selectable; monotonic sleep; jitter metric.
  * ✅ *AC:* Mean period within ±0.2 ms of target; <1% misses.

# C) Binding & Pairing (MVP)

* [ ] **Bind Phrase flow**

  * UI checklist guiding to ensure TX & RX share phrase (and model match).
  * Optional: TX Wi-Fi helper (open module AP URL) (NTH).
  * ✅ *AC:* If phrases match, link establishes without manual bind.
* [ ] **Bind command via MSP**

  * One-tap “Bind RX” → MSP-over-CRSF request to FC to trigger ELRS bind.
  * ✅ *AC:* RX enters bind mode; link forms within expected window.
* [ ] **Manual bind helper**

  * Instructions & timing; LED state hints; retries.
  * ✅ *AC:* User can complete manual bind with on-screen guide.

# D) Receiver/Betaflight Integration (MVP)

* [ ] **CRSF receiver profile sanity check**

  * Preflight checklist: UART set to Serial RX, Provider=CRSF.
  * ✅ *AC:* App can verify via MSP (if enabled) or present checklist.
* [ ] **Modes mapping aid**

  * Arm on AUX1 default; mode toggles on AUX2/AUX3; editable.
  * ✅ *AC:* BF Modes tab reacts to app toggles (observed via USB to FC).
* [ ] **Model Match indicator**

  * Warn if sticks move but FC shows no channel change; suggest correct model.
  * ✅ *AC:* Clear banner when model idx mismatch suspected.

# E) Controls, Mixing & Safety (MVP)

* [ ] **Input sources**

  * Physical gamepad (Android GameController), on-screen twin sticks, optional phone gyro (NTH).
  * ✅ *AC:* Any enabled source drives channels.
* [ ] **Curves & filters**

  * Deadzone, expo, rates per axis; throttle limit; smoothing (1-pole).
  * ✅ *AC:* Curve previews; measurable output change.
* [ ] **Arming logic gate**

  * Block arm unless: throttle min, link OK (LQ/RSSI thresholds), user long-press.
  * ✅ *AC:* Cannot arm if any gate fails; haptic/audio cue explains why.
* [ ] **Failsafe**

  * On USB detach/app pause: push disarm + neutral; stop TX loop.
  * ✅ *AC:* BF enters failsafe or disarms within 0.2 s.

# F) Telemetry & HUD (MVP)

* [ ] **Link panel**

  * RSSI dBm, LQ %, SNR, RF mode/packet rate if present.
  * ✅ *AC:* Values update ~5–10 Hz; thresholds colorized.
* [ ] **Flight data**

  * Battery voltage (0x08), flight mode (0x21), attitude (0x1E) (NTH if FC exports).
  * ✅ *AC:* Fields populate on supported FC targets.
* [ ] **Alarms**

  * Low RSSI/LQ, low voltage, radio link lost → sound/vibrate + on-screen toast.
  * ✅ *AC:* Triggers at configured thresholds.

# G) MSP Utilities (NTH, high value)

* [ ] **Receiver tab shortcuts**

  * In-app “Bind”, “Beeper”, “Calibrate” via MSP calls.
  * ✅ *AC:* Commands reflect in BF GUI if connected simultaneously.
* [ ] **VTX power/band (if wired)**

  * Basic MSP VTX set (where applicable).
  * ✅ *AC:* VTX table read/write works on supported targets.
* [ ] **Blackbox start/stop**

  * Quick toggle (if FC supports via MSP).
  * ✅ *AC:* FC acknowledges state change.

# H) Profiles, Persistence & UX (MVP)

* [ ] **Model profiles**

  * Save per-model: channel map, rates, expo, arm switch, packet rate, UI layout.
  * ✅ *AC:* Switching profiles updates behavior instantly.
* [ ] **Calibration**

  * Gamepad axes centering, range; on-screen stick calibration.
  * ✅ *AC:* Stored calibration applied after restart.
* [ ] **Session recorder (NTH)**

  * Log RC frames + telemetry to file; replay in “bench mode”.
  * ✅ *AC:* Replay reproduces stick timeline.

# I) Diagnostics & Testability (MVP)

* [ ] **Frame inspector**

  * Hex dump of last N CRSF frames (TX/RX), type breakdown.
  * ✅ *AC:* Developers can confirm LEN/CRC/types visually.
* [ ] **Self-test**

  * CRC test vectors; pack/unpack round-trip for 16ch 11-bit.
  * ✅ *AC:* All tests pass on CI and device.
* [ ] **Latency & jitter meter**

  * Measure end-to-end (input → frame sent); display rolling stats.
  * ✅ *AC:* Numbers stable within expected ranges.

# J) App Lifecycle, Errors & Power (MVP)

* [ ] **Lifecycle hooks**

  * Pause → failsafe & stop TX; Resume → re-arm allowed only after gates pass.
  * ✅ *AC:* No RC output while backgrounded.
* [ ] **Hotplug handling**

  * Detect detach/attach; prompt to reconnect; graceful recovery.
  * ✅ *AC:* No crash on cable yank.
* [ ] **Power mgmt**

  * Keep-screen-on (while armed), prevent doze on TX loop; battery usage budget.
  * ✅ *AC:* No throttling-induced frame drops.

# K) Packaging & Compatibility (MVP)

* [ ] **ABIs**

  * `arm64-v8a`, `armeabi-v7a`, `x86_64` builds of the JNI lib.
  * ✅ *AC:* App installs & runs on common devices.
* [ ] **ProGuard/R8 rules**

  * Keep JNI signatures, UsbManager classes.
  * ✅ *AC:* Release build functions identically to debug.
* [ ] **Minimum Android version**

  * Decide (e.g., API 24+); runtime USB permission flow compatible.
  * ✅ *AC:* Tested on minSDK & recent SDK.

# L) Advanced ELRS Controls (NTH / power user)

* [ ] **Device menu over CRSF**

  * Read/Write ELRS TX params (power level, TLM ratio, packet rate) if exposed.
  * ✅ *AC:* Changing value reflects on TX OLED/Lua equivalent.
* [ ] **Model Match id setter**

  * Per-profile selection; warn on mismatch.
  * ✅ *AC:* FC channel activity matches selected model.

# M) Safety UX (MVP)

* [ ] **Big red E-STOP**

  * Always-visible button; instantly sends disarm + neutral; haptic feedback.
  * ✅ *AC:* Works regardless of app state (foreground).
* [ ] **Pre-arm checklist**

  * Props off hint on first run; link quality; throttle low; mode; battery OK.
  * ✅ *AC:* Users can’t arm until checklist passes (with override option off by default).

# N) Internationalization & Accessibility (NTH)

* [ ] **Language selection**

  * EN/PL at minimum.
  * ✅ *AC:* Strings externalized; toggling language updates UI.
* [ ] **Haptics / audio cues**

  * Arm/disarm/link lost/low batt tones; vibration patterns.
  * ✅ *AC:* Users can toggle per event.

# O) Documentation & Support (MVP)

* [ ] **In-app quickstart**

  * 10-step bring-up, pictures of LEDs, common failure modes.
  * ✅ *AC:* New user can pair in <5 minutes.
* [ ] **Troubleshooting guide**

  * No link (phrase mismatch, model match), no sticks (BF UART/CRSF), bind fails, telemetry zeros.
  * ✅ *AC:* Linked from errors and alerts.

---

## Acceptance Test (End-to-End)

* [ ] With props off, fresh install → bind via phrase OR one-tap bind → confirm telemetry → see BF Receiver channels move → set modes → arm gate passes → throttle responds → unplug OTG triggers failsafe → zero warnings on re-connect.

want me to turn this into a living checklist (with priorities/owners/dates) or a GitHub project board?

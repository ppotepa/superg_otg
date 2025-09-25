#include <jni.h>
#include <android/log.h>
#include <array>
#include <atomic>
#include <thread>
#include <chrono>
#include <cstring>
#include <cmath>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ELRS", __VA_ARGS__)
#define LOGD(...)              \
    if (g_debugLogging.load()) \
    __android_log_print(ANDROID_LOG_DEBUG, "ELRS_DEBUG", __VA_ARGS__)

// ---- JNI bridge to UsbBridge.write(byte[],len,timeout) ----
static JavaVM *g_vm = nullptr;
static jclass g_bridgeClass = nullptr;
static jmethodID g_write = nullptr;

static std::atomic<float> g_roll{0}, g_pitch{0}, g_yaw{0}, g_thr{0};
static std::atomic<bool> g_run{false};
static std::atomic<bool> g_armed{false};
static std::atomic<bool> g_linkOk{false};
static std::atomic<bool> g_safetyOverride{false};

// Telemetry UI callback globals
static jobject g_telemetryClass = nullptr;
static jmethodID g_telemetryUICallback = nullptr;

// Debug logging control
static std::atomic<bool> g_debugLogging{false};

// ---- CRSF helpers ----
static inline uint8_t crsf_crc8(const uint8_t *p, int n)
{
    uint8_t c = 0;
    for (int i = 0; i < n; i++)
    {
        uint8_t b = uint8_t(p[i] ^ c);
        for (int j = 0; j < 8; j++)
            b = (b & 0x80) ? uint8_t((b << 1) ^ 0xD5) : uint8_t(b << 1);
        c = b;
    }
    return c;
}
static inline uint16_t us2val(float us)
{
    float v = 172.f + (us - 1000.f) * ((1811.f - 172.f) / 1000.f);
    if (v < 172)
        v = 172;
    if (v > 1811)
        v = 1811;
    return (uint16_t)v;
}
static inline uint16_t map_stick(float x) { return us2val(1500.f + x * 500.f); }
static inline uint16_t map_thr(float t) { return us2val(1000.f + t * 1000.f); }

static void pack(const uint16_t ch[16], uint8_t out[22])
{
    uint32_t acc = 0;
    int bits = 0, idx = 0;
    for (int i = 0; i < 16; i++)
    {
        acc |= (uint32_t(ch[i] & 0x7FF) << bits);
        bits += 11;
        while (bits >= 8)
        {
            out[idx++] = (uint8_t)(acc & 0xFF);
            acc >>= 8;
            bits -= 8;
        }
    }
    if (idx < 22)
        out[idx] = (uint8_t)(acc & 0xFF);
}
static void build(const uint16_t ch[16], std::array<uint8_t, 26> &f)
{
    f[0] = 0xC8;
    f[1] = 1 + 22 + 1;
    f[2] = 0x16;
    pack(ch, &f[3]);
    f.back() = crsf_crc8(&f[2], 23);
}

static JNIEnv *envGet()
{
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv((void **)&env, JNI_VERSION_1_6) != JNI_OK)
        g_vm->AttachCurrentThread(&env, nullptr);
    return env;
}

static void txLoop()
{
    auto period = std::chrono::milliseconds(4); // ~250 Hz
    while (g_run.load())
    {
        uint16_t ch[16];
        // Initialize all channels to safe defaults
        for (int i = 0; i < 16; i++)
            ch[i] = 992; // ~1500us neutral

        // Apply control inputs
        ch[0] = map_stick(g_roll.load());  // Roll
        ch[1] = map_stick(g_pitch.load()); // Pitch
        ch[3] = map_stick(g_yaw.load());   // Yaw

        // Throttle safety logic
        float thr = g_thr.load();
        bool armed = g_armed.load();
        bool linkOk = g_linkOk.load();
        bool safetyOverride = g_safetyOverride.load();

        // Safety gates for throttle
        if (!armed || (!linkOk && !safetyOverride))
        {
            thr = 0.0f; // Force throttle to minimum
        }

        // Apply throttle with safety check
        ch[2] = map_thr(thr);

        // AUX channels for modes (AETR1234 mapping)
        ch[4] = armed ? 1811 : 172; // AUX1 - ARM channel (high=armed, low=disarmed)
        ch[5] = 992;                // AUX2 - Flight mode (neutral = default mode)
        ch[6] = 992;                // AUX3 - Additional mode switch
        ch[7] = 992;                // AUX4 - Beeper/other functions

        // Send frame
        std::array<uint8_t, 26> frame;
        build(ch, frame);

        JNIEnv *env = envGet();
        jbyteArray arr = env->NewByteArray((jsize)frame.size());
        env->SetByteArrayRegion(arr, 0, (jsize)frame.size(), (jbyte *)frame.data());
        int result = env->CallStaticIntMethod(g_bridgeClass, g_write, arr, (jint)frame.size(), 20);
        env->DeleteLocalRef(arr);

        // Enhanced logging for command tracking
        static int counter = 0;
        static int lastArmed = -1;
        static float lastThr = -999.0f;
        static bool lastLinkOk = false;

        bool stateChanged = (armed != lastArmed) || (std::abs(thr - lastThr) > 0.05f) || (linkOk != lastLinkOk);

        // Always log USB write results when debug logging is enabled, or occasionally when not
        if (g_debugLogging.load() || (++counter % 50 == 0) || stateChanged)
        { // Debug: every frame, Normal: every ~200ms or on state change
            LOGD("🚁 TX_FRAME_SENT: Armed=%d, LinkOK=%d, Thr=%.2f, Roll=%.2f, Pitch=%.2f, Yaw=%.2f, USB_Result=%d",
                 armed, linkOk, thr, g_roll.load(), g_pitch.load(), g_yaw.load(), result);

            if (result <= 0)
            {
                LOGD("⚠️ TX_FRAME_FAILED: USB write failed with result=%d", result);
            }
            else
            {
                LOGD("✅ TX_FRAME_CONFIRMED: %d bytes sent to transmitter", result);

                // Show raw frame data when debug logging is enabled
                char hexStr[128] = {0};
                char *p = hexStr;
                for (int i = 0; i < 26 && i < frame.size(); i++)
                {
                    p += sprintf(p, "%02X ", frame[i]);
                }
                LOGD("📤 TX_RAW_FRAME: %s", hexStr);
                LOGD("📤 TX_CHANNELS: Ch1=%d Ch2=%d Ch3=%d Ch4=%d Ch5=%d", ch[0], ch[1], ch[2], ch[3], ch[4]);
            }

            lastArmed = armed;
            lastThr = thr;
            lastLinkOk = linkOk;
        }

        std::this_thread::sleep_for(period);
    }
}

extern "C" jint JNI_OnLoad(JavaVM *vm, void *)
{
    g_vm = vm;
    JNIEnv *env = envGet();
    jclass cls = env->FindClass("com/example/elrsotg/UsbBridge");
    g_bridgeClass = (jclass)env->NewGlobalRef(cls);
    g_write = env->GetStaticMethodID(g_bridgeClass, "write", "([BII)I");
    return JNI_VERSION_1_6;
}

// ---- ELRS MSP Command helpers ----
static void buildMspCommand(uint8_t function, const uint8_t *payload, uint8_t payloadSize, std::array<uint8_t, 64> &out, uint8_t &outSize)
{
    // CRSF MSP frame: SYNC LEN TYPE DEST ORIG FUNCTION PAYLOADSIZE [PAYLOAD] CRC
    // Fixed: LEN = 6 + payloadSize (TYPE + DEST + ORIG + FUNC + SIZE + payload + CRC)
    // Fixed: DEST = 0xC8 (Flight Controller), ORIG = 0xEE (Transmitter)
    uint8_t idx = 0;
    out[idx++] = 0xC8;            // CRSF sync byte
    out[idx++] = 6 + payloadSize; // frame length: TYPE+DEST+ORIG+FUNC+SIZE+payload+CRC
    out[idx++] = 0x7A;            // MSP command frame type (MSP_REQ)
    out[idx++] = 0xC8;            // destination (Flight Controller)
    out[idx++] = 0xEE;            // origin (Transmitter)
    out[idx++] = function;        // MSP function
    out[idx++] = payloadSize;     // payload size

    // Add payload
    for (uint8_t i = 0; i < payloadSize; i++)
    {
        out[idx++] = payload[i];
    }

    // Calculate CRC over everything except sync and length
    out[idx] = crsf_crc8(&out[2], idx - 2);
    outSize = idx + 1;
}

static bool sendMspCommand(uint8_t function, const uint8_t *payload = nullptr, uint8_t payloadSize = 0)
{
    std::array<uint8_t, 64> frame;
    uint8_t frameSize;
    buildMspCommand(function, payload, payloadSize, frame, frameSize);

    // Log the raw frame data being sent
    char hexStr[256] = {0};
    char *p = hexStr;
    for (int i = 0; i < frameSize && i < 32; i++)
    {
        p += sprintf(p, "%02X ", frame[i]);
    }
    LOGD("📤 MSP_COMMAND_PREP: Function=0x%02X, PayloadSize=%d, FrameSize=%d", function, payloadSize, frameSize);
    LOGD("📤 MSP_RAW_DATA: %s", hexStr);

    JNIEnv *env = envGet();
    if (!env)
    {
        LOGI("❌ MSP_COMMAND_FAILED: JNI environment not available");
        return false;
    }

    jbyteArray arr = env->NewByteArray(frameSize);
    env->SetByteArrayRegion(arr, 0, frameSize, (jbyte *)frame.data());
    int result = env->CallStaticIntMethod(g_bridgeClass, g_write, arr, frameSize, 100);
    env->DeleteLocalRef(arr);

    if (result > 0)
    {
        LOGD("✅ MSP_COMMAND_SENT: Function=0x%02X confirmed, %d bytes transmitted to TX", function, result);
    }
    else
    {
        LOGD("❌ MSP_COMMAND_FAILED: Function=0x%02X, USB write failed with result=%d", function, result);
    }

    return result > 0;
}

// ELRS bind command based on elrsv3.lua analysis
// Implements: crossfireTelemetryPush(0x2D, { deviceId, handsetId, fieldId, status })
static bool sendElrsBindCommand()
{
    // From elrsv3.lua: deviceId = 0xEE, handsetId = 0xEF for ELRS TX module
    // fieldCommandSave sends status = 1 to execute bind command
    uint8_t payload[] = {
        0xEE, // Device ID (TX module from elrsv3.lua)
        0xEF, // Handset ID (ELRS Lua from elrsv3.lua)
        0x00, // Field ID (0 for bind command based on Lua analysis)
        0x01  // Status (1 = execute bind from fieldCommandSave in Lua)
    };

    LOGD("🔍 ELRS_BIND_PREP: DeviceID=0x%02X, HandsetID=0x%02X, FieldID=0x%02X, Status=0x%02X",
         payload[0], payload[1], payload[2], payload[3]);

    return sendMspCommand(0x2D, payload, sizeof(payload));
}

// ELRS device discovery command based on elrsv3.lua analysis
// Implements: crossfireTelemetryPush(0x28, { 0x00, 0xEA })
static bool sendElrsDeviceDiscovery()
{
    uint8_t payload[] = {
        0x00, // Broadcast address (from Lua: { 0x00, 0xEA })
        0xEA  // Standard handset ID for device discovery
    };

    LOGD("🔍 ELRS_DISCOVERY_PREP: Broadcasting device enumeration request");

    return sendMspCommand(0x28, payload, sizeof(payload));
}

// ELRS link statistics request based on elrsv3.lua analysis
// Implements: crossfireTelemetryPush(0x2D, { deviceId, handsetId, 0x0, 0x0 })
static bool sendElrsLinkStatsRequest()
{
    uint8_t payload[] = {
        0xEE, // Device ID (TX module)
        0xEF, // Handset ID (ELRS Lua)
        0x00, // Field ID (0 for link stats)
        0x00  // Status (0 for request)
    };

    LOGD("📊 ELRS_LINKSTATS_PREP: Requesting telemetry data");

    return sendMspCommand(0x2D, payload, sizeof(payload));
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_elrsotg_MainActivity_nativeSetAxes(JNIEnv *, jclass, jfloat r, jfloat p, jfloat y, jfloat t)
{
    // Log significant control changes
    static float lastR = 0, lastP = 0, lastY = 0, lastT = 0;
    static int inputCounter = 0;

    bool significantChange = (std::abs(r - lastR) > 0.1f) || (std::abs(p - lastP) > 0.1f) ||
                             (std::abs(y - lastY) > 0.1f) || (std::abs(t - lastT) > 0.1f);

    g_roll = r;
    g_pitch = p;
    g_yaw = y;
    g_thr = t;

    if (significantChange || (++inputCounter % 100 == 0))
    {
        LOGI("🎮 CONTROLLER_INPUT: R=%.2f, P=%.2f, Y=%.2f, T=%.2f", r, p, y, t);
        LOGI("🎮 INPUT_CONFIRMED: ✅ Values stored, will be sent in next TX frame");

        if (t > 0.1f && !g_armed.load())
        {
            LOGI("⚠️ THROTTLE_WARNING: Throttle input detected but drone is DISARMED - throttle will be forced to 0");
        }

        lastR = r;
        lastP = p;
        lastY = y;
        lastT = t;
    }
}
extern "C" JNIEXPORT void JNICALL
Java_com_example_elrsotg_MainActivity_nativeStart(JNIEnv *, jclass)
{
    g_run = true;
    std::thread(txLoop).detach();
    LOGI("🚁 TX_LOOP_START: ✅ CRSF transmitter control active at 250Hz!");
    LOGI("🚁 TX_LOOP_ACTIVE: Sending channel data to transmitter every 4ms");
    LOGI("🚁 TX_LOOP_CHANNELS: AETR1234 mapping - Roll/Pitch/Throttle/Yaw + AUX channels");
}
extern "C" JNIEXPORT void JNICALL
Java_com_example_elrsotg_MainActivity_nativeStop(JNIEnv *, jclass)
{
    g_run = false;
    LOGI("🚁 TX_LOOP_STOP: ✅ CRSF transmission stopped - no more commands to TX");
    LOGI("🚁 TX_LOOP_INACTIVE: Transmitter should show 'No Signal' or failsafe");
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_elrsotg_MainActivity_nativeSendCommand(JNIEnv *env, jclass, jstring command)
{
    const char *cmd = env->GetStringUTFChars(command, nullptr);
    bool result = false;

    LOGI("📨 COMMAND_RECEIVED: %s", cmd);

    if (strcmp(cmd, "PAIR") == 0)
    {
        LOGI("🔗 USER_COMMAND: PAIR/BIND initiated - Starting ELRS binding process");
        // ELRS bind command - MSP 0x2D based on elrsv3.lua analysis
        // crossfireTelemetryPush(0x2D, { deviceId=0xEE, handsetId=0xEF, fieldId=bind, status=1 })
        result = sendElrsBindCommand();
        if (result)
        {
            LOGI("🔗 PAIR_COMMAND: ✅ CONFIRMED SENT TO TX - Binding mode should be active on transmitter");
        }
        else
        {
            LOGI("🔗 PAIR_COMMAND: ❌ TRANSMISSION FAILED - Check USB connection");
        }
    }
    else if (strcmp(cmd, "INC_SIGNAL") == 0)
    {
        LOGI("📶 USER_COMMAND: INCREASE_POWER initiated - Boosting TX power");
        // Increase power - could be MSP_SET_TX_INFO or custom ELRS command
        // For now, we'll use a placeholder MSP command
        uint8_t payload[] = {1}; // increase power flag
        result = sendMspCommand(0xF5, payload, 1);
        LOGI("📶 POWER_INCREASE: %s - %s", result ? "✅ CONFIRMED SENT TO TX" : "❌ TRANSMISSION FAILED",
             result ? "TX power level should increase" : "Check USB connection");
    }
    else if (strcmp(cmd, "DEC_SIGNAL") == 0)
    {
        LOGI("📉 USER_COMMAND: DECREASE_POWER initiated - Reducing TX power");
        // Decrease power
        uint8_t payload[] = {0}; // decrease power flag
        result = sendMspCommand(0xF5, payload, 1);
        LOGI("📉 POWER_DECREASE: %s - %s", result ? "✅ CONFIRMED SENT TO TX" : "❌ TRANSMISSION FAILED",
             result ? "TX power level should decrease" : "Check USB connection");
    }
    else if (strcmp(cmd, "BIND") == 0)
    {
        LOGI("🔗 USER_COMMAND: BIND initiated - Starting ELRS binding process (same as PAIR)");
        // ELRS bind command - MSP 0x2D based on elrsv3.lua analysis
        result = sendElrsBindCommand();
        LOGI("🔗 BIND_COMMAND: %s - %s", result ? "✅ CONFIRMED SENT TO TX" : "❌ TRANSMISSION FAILED",
             result ? "Binding mode should be active on transmitter" : "Check USB connection");
    }
    else if (strcmp(cmd, "RESET") == 0)
    {
        LOGI("🔄 USER_COMMAND: RESET initiated - Rebooting transmitter");
        // Reset/reboot command - MSP reboot
        result = sendMspCommand(0x68); // MSP_REBOOT
        LOGI("🔄 RESET_COMMAND: %s - %s", result ? "✅ CONFIRMED SENT TO TX" : "❌ TRANSMISSION FAILED",
             result ? "Transmitter should reboot now" : "Check USB connection");
    }
    else if (strcmp(cmd, "MODEL_SELECT") == 0)
    {
        LOGI("🔀 USER_COMMAND: MODEL_SELECT initiated - Switching to next model");
        // Model select - could cycle through model slots
        uint8_t payload[] = {1};                   // next model
        result = sendMspCommand(0xF6, payload, 1); // Custom model select command
        LOGI("🔀 MODEL_SELECT: %s - %s", result ? "✅ CONFIRMED SENT TO TX" : "❌ TRANSMISSION FAILED",
             result ? "Should switch to next model slot" : "Check USB connection");
    }
    else
    {
        LOGI("Unknown command: %s", cmd);
    }

    env->ReleaseStringUTFChars(command, cmd);
    return result;
}

// ---- Telemetry Reading Support ----
static jmethodID g_read = nullptr;
static jmethodID g_telemetryCallback = nullptr;
static std::atomic<bool> g_telemetryRun{false};

// Forward declaration
static void processTelemetryFrame(const uint8_t *frame, int len);

static void telemetryLoop()
{
    uint8_t buffer[128];
    uint8_t frame[64];
    int framePos = 0;
    bool inFrame = false;
    uint8_t expectedLen = 0;

    // Periodic request timing (based on elrsv3.lua timing)
    auto lastLinkStatsRequest = std::chrono::steady_clock::now();
    auto linkStatsInterval = std::chrono::milliseconds(1000); // 1 second like Lua (100 * 10ms)

    while (g_telemetryRun.load())
    {
        JNIEnv *env = envGet();
        jbyteArray arr = env->NewByteArray(128);

        // Read from USB with short timeout
        int bytesRead = env->CallStaticIntMethod(g_bridgeClass, g_read, arr, 50);

        if (bytesRead > 0)
        {
            env->GetByteArrayRegion(arr, 0, bytesRead, (jbyte *)buffer);

            // Log raw RX data when debug logging is enabled
            if (g_debugLogging.load())
            {
                char hexStr[512] = {0};
                char *p = hexStr;
                for (int i = 0; i < bytesRead && i < 64; i++)
                {
                    p += sprintf(p, "%02X ", buffer[i]);
                }
                LOGD("📥 RX_RAW_DATA: %d bytes: %s", bytesRead, hexStr);
            }

            // Parse CRSF frames from the data
            for (int i = 0; i < bytesRead; i++)
            {
                uint8_t byte = buffer[i];

                if (!inFrame && byte == 0xC8)
                {
                    // Start of frame
                    frame[0] = byte;
                    framePos = 1;
                    inFrame = true;
                    expectedLen = 0;
                }
                else if (inFrame)
                {
                    frame[framePos++] = byte;

                    if (framePos == 2)
                    {
                        // Got length byte
                        expectedLen = byte;
                        if (expectedLen > 62)
                        { // Invalid length
                            inFrame = false;
                            framePos = 0;
                        }
                    }
                    else if (framePos >= 3 && framePos == expectedLen + 2)
                    {
                        // Complete frame received
                        processTelemetryFrame(frame, framePos);
                        inFrame = false;
                        framePos = 0;
                    }
                    else if (framePos >= 64)
                    {
                        // Frame too long, reset
                        inFrame = false;
                        framePos = 0;
                    }
                }
            }
        }

        // Periodic link statistics request (like elrsv3.lua linkstatTimeout)
        auto now = std::chrono::steady_clock::now();
        if (now - lastLinkStatsRequest >= linkStatsInterval)
        {
            sendElrsLinkStatsRequest();
            lastLinkStatsRequest = now;
            LOGD("📊 PERIODIC_REQUEST: Link statistics requested");
        }

        env->DeleteLocalRef(arr);
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
}

static void processTelemetryFrame(const uint8_t *frame, int len)
{
    if (len < 4)
        return;

    uint8_t type = frame[2];
    LOGD("📥 RX_CRSF_FRAME: type=0x%02X, len=%d", type, len);

    // Show raw frame data when debug logging is enabled
    if (g_debugLogging.load())
    {
        char hexStr[256] = {0};
        char *p = hexStr;
        for (int i = 0; i < len && i < 32; i++)
        {
            p += sprintf(p, "%02X ", frame[i]);
        }
        LOGD("📥 RX_FRAME_DATA: %s", hexStr);
    }

    switch (type)
    {
    case 0x14: // LINK_STATISTICS
        if (len >= 12)
        {
            int8_t rssi1 = (int8_t)frame[3];
            int8_t rssi2 = (int8_t)frame[4];
            uint8_t lq = frame[5];
            int8_t snr = (int8_t)frame[6];
            uint8_t antenna = frame[7];
            uint8_t rf_mode = frame[8];
            uint8_t tx_power = frame[9];

            // Update link quality status for safety gates
            // Consider link OK if LQ > 50% and RSSI > -100dBm
            bool newLinkOk = (lq > 50) && (rssi1 > -100 || rssi2 > -100);
            g_linkOk.store(newLinkOk);

            // Send link stats to Java UI
            if (g_telemetryClass && g_telemetryUICallback && g_vm)
            {
                JNIEnv *env = nullptr;
                if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK)
                {
                    jstring typeStr = env->NewStringUTF("LINK_STATS");
                    env->CallStaticVoidMethod((jclass)g_telemetryClass, g_telemetryUICallback, typeStr,
                                              (jint)rssi1, (jint)rssi2, (jint)lq, (jint)snr, (jint)tx_power);
                    env->DeleteLocalRef(typeStr);
                    g_vm->DetachCurrentThread();
                }
            }

            static int logCounter = 0;
            if (++logCounter % 50 == 0)
            { // Log every ~5 seconds
                LOGI("Link Stats: RSSI1=%ddBm, RSSI2=%ddBm, LQ=%d%%, SNR=%ddB, LinkOK=%d",
                     rssi1, rssi2, lq, snr, newLinkOk);
            }
        }
        break;

    case 0x08: // BATTERY_SENSOR
        if (len >= 8)
        {
            uint16_t voltage = (frame[3] << 8) | frame[4];                     // mV
            uint16_t current = (frame[5] << 8) | frame[6];                     // mA
            uint32_t capacity = (frame[7] << 16) | (frame[8] << 8) | frame[9]; // mAh

            // Send battery data to Java UI
            if (g_telemetryClass && g_telemetryUICallback && g_vm)
            {
                JNIEnv *env = nullptr;
                if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK)
                {
                    jstring typeStr = env->NewStringUTF("BATTERY");
                    env->CallStaticVoidMethod((jclass)g_telemetryClass, g_telemetryUICallback, typeStr,
                                              (jint)voltage, (jint)current, (jint)capacity, 0, 0);
                    env->DeleteLocalRef(typeStr);
                    g_vm->DetachCurrentThread();
                }
            }

            LOGI("Battery: %dmV, %dmA, %dmAh", voltage, current, capacity);
        }
        break;

    case 0x1E: // ATTITUDE
        if (len >= 8)
        {
            int16_t pitch = (int16_t)((frame[3] << 8) | frame[4]);
            int16_t roll = (int16_t)((frame[5] << 8) | frame[6]);
            int16_t yaw = (int16_t)((frame[7] << 8) | frame[8]);

            LOGI("Attitude: Pitch=%d, Roll=%d, Yaw=%d", pitch, roll, yaw);
        }
        break;

    case 0x21: // FLIGHT_MODE
        if (len >= 4)
        {
            uint8_t mode = frame[3];
            LOGI("Flight Mode: %d", mode);
        }
        break;

    default:
        LOGI("Unknown telemetry frame type: 0x%02X", type);
        break;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_elrsotg_MainActivity_nativeStartTelemetry(JNIEnv *env, jclass clazz)
{
    LOGI("📡 TELEMETRY_START: Initializing bidirectional communication with TX");

    if (!g_read)
    {
        g_read = env->GetStaticMethodID(g_bridgeClass, "read", "([BI)I");
        LOGI("📡 TELEMETRY_SETUP: ✅ USB read method configured");
    }

    if (!g_telemetryCallback)
    {
        g_telemetryCallback = env->GetStaticMethodID(clazz, "onTelemetryData", "(Ljava/lang/String;IIIII)V");
        LOGI("📡 TELEMETRY_SETUP: ✅ UI callback method configured");
    }

    g_telemetryRun = true;
    std::thread(telemetryLoop).detach();
    LOGI("📡 TELEMETRY_ACTIVE: ✅ Reader thread started - listening for TX data");
    LOGI("📡 TELEMETRY_EXPECTING: Link stats, battery data, attitude, flight modes");

    // Send ELRS device discovery command (based on elrsv3.lua)
    // crossfireTelemetryPush(0x28, { 0x00, 0xEA })
    sendElrsDeviceDiscovery();
    LOGI("🔍 ELRS_DISCOVERY: Device enumeration command sent");
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_elrsotg_MainActivity_nativeStopTelemetry(JNIEnv *, jclass)
{
    g_telemetryRun = false;
    LOGI("📡 TELEMETRY_STOP: ✅ Reader thread stopping - no more data from TX");
    LOGI("📡 TELEMETRY_INACTIVE: Bidirectional communication suspended");
}

// ---- Safety and Arming Controls ----
extern "C" JNIEXPORT void JNICALL
Java_com_example_elrsotg_MainActivity_nativeSetArmed(JNIEnv *, jclass, jboolean armed)
{
    g_armed.store(armed);
    if (armed)
    {
        LOGI("🔴 CRITICAL_COMMAND: ARM initiated - DRONE IS NOW ARMED AND DANGEROUS!");
        LOGI("🔴 ARM_STATE: ✅ CONFIRMED - AUX1 channel will be HIGH on next TX frame");
        LOGI("🔴 ARM_WARNING: Propellers may spin - ensure safe distance!");
    }
    else
    {
        LOGI("🟢 SAFETY_COMMAND: DISARM initiated - Drone is now SAFE");
        LOGI("🟢 DISARM_STATE: ✅ CONFIRMED - AUX1 channel will be LOW on next TX frame");
        LOGI("🟢 DISARM_CONFIRMED: Propellers should stop spinning");
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_elrsotg_MainActivity_nativeIsArmed(JNIEnv *, jclass)
{
    return g_armed.load();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_elrsotg_MainActivity_nativeIsLinkOk(JNIEnv *, jclass)
{
    return g_linkOk.load();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_elrsotg_MainActivity_nativeSetSafetyOverride(JNIEnv *, jclass, jboolean override)
{
    g_safetyOverride.store(override);
    LOGI("Safety override: %s", override ? "ENABLED" : "DISABLED");
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_elrsotg_MainActivity_nativeEmergencyStop(JNIEnv *, jclass)
{
    g_armed.store(false);
    g_roll.store(0.0f);
    g_pitch.store(0.0f);
    g_yaw.store(0.0f);
    g_thr.store(0.0f);
    LOGI("🚨 EMERGENCY_STOP: CRITICAL SAFETY ACTION INITIATED!");
    LOGI("🚨 EMERGENCY_STOP: ✅ ARMED=FALSE, ALL AXES ZEROED");
    LOGI("🚨 EMERGENCY_STOP: ✅ CONFIRMED - Next TX frame will cut all power");
    LOGI("🚨 EMERGENCY_STOP: Roll=0, Pitch=0, Yaw=0, Throttle=0, AUX1=LOW");
}

// Global telemetry UI callback reference (separate from existing g_telemetryCallback method ID)

extern "C" JNIEXPORT void JNICALL
Java_com_example_elrsotg_MainActivity_registerTelemetryCallback(JNIEnv *env, jclass clazz)
{
    LOGI("Registering telemetry UI callback");

    // Find the callback method
    g_telemetryUICallback = env->GetStaticMethodID(clazz, "onTelemetryData",
                                                   "(Ljava/lang/String;IIIII)V");

    if (g_telemetryUICallback == nullptr)
    {
        LOGI("Failed to find onTelemetryData method");
        return;
    }

    // Store the class reference
    g_telemetryClass = env->NewGlobalRef(clazz);

    LOGI("Telemetry UI callback registered successfully");
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_elrsotg_MainActivity_nativeSetDebugLogging(JNIEnv *, jclass, jboolean enabled)
{
    g_debugLogging.store(enabled);
    if (enabled)
    {
        LOGI("🔍 DEBUG_LOGGING: ✅ ENABLED - Detailed TX/RX logging active");
        LOGI("🔍 DEBUG_MODE: Will show raw hex data for all USB communications");
    }
    else
    {
        LOGI("🔍 DEBUG_LOGGING: ❌ DISABLED - Debug logging stopped");
    }
}

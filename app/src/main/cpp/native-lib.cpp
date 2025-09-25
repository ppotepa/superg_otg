#include <jni.h>
#include <android/log.h>
#include <array>
#include <atomic>
#include <thread>
#include <chrono>
#include <cstring>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ELRS", __VA_ARGS__)

// ---- JNI bridge to UsbBridge.write(byte[],len,timeout) ----
static JavaVM* g_vm=nullptr;
static jclass   g_bridgeClass=nullptr;
static jmethodID g_write=nullptr;

static std::atomic<float> g_roll{0}, g_pitch{0}, g_yaw{0}, g_thr{0};
static std::atomic<bool>  g_run{false};
static std::atomic<bool>  g_armed{false};
static std::atomic<bool>  g_linkOk{false};
static std::atomic<bool>  g_safetyOverride{false};
#include <array>
#include <atomic>
#include <thread>
#include <chrono>
#include <cstring>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ELRS", __VA_ARGS__)

// Variables already declared above

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
        if (!armed || (!linkOk && !safetyOverride)) {
            thr = 0.0f; // Force throttle to minimum
        }
        
        // Apply throttle with safety check
        ch[2] = map_thr(thr);
        
        // AUX channels for modes (AETR1234 mapping)
        ch[4] = armed ? 1811 : 172;        // AUX1 - ARM channel (high=armed, low=disarmed)
        ch[5] = 992;                       // AUX2 - Flight mode (neutral = default mode)
        ch[6] = 992;                       // AUX3 - Additional mode switch
        ch[7] = 992;                       // AUX4 - Beeper/other functions
        
        // Send frame
        std::array<uint8_t, 26> frame;
        build(ch, frame);

        JNIEnv *env = envGet();
        jbyteArray arr = env->NewByteArray((jsize)frame.size());
        env->SetByteArrayRegion(arr, 0, (jsize)frame.size(), (jbyte *)frame.data());
        int result = env->CallStaticIntMethod(g_bridgeClass, g_write, arr, (jint)frame.size(), 20);
        env->DeleteLocalRef(arr);
        
        // Log occasionally for debugging
        static int counter = 0;
        if (++counter % 250 == 0) { // Every ~1 second at 250Hz
            LOGI("TX: Armed=%d, LinkOK=%d, Thr=%.2f, Result=%d", armed, linkOk, thr, result);
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

    JNIEnv *env = envGet();
    if (!env)
        return false;

    jbyteArray arr = env->NewByteArray(frameSize);
    env->SetByteArrayRegion(arr, 0, frameSize, (jbyte *)frame.data());
    int result = env->CallStaticIntMethod(g_bridgeClass, g_write, arr, frameSize, 100);
    env->DeleteLocalRef(arr);

    LOGI("Sent MSP command 0x%02X, result: %d", function, result);
    return result > 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_elrsotg_MainActivity_nativeSetAxes(JNIEnv *, jclass, jfloat r, jfloat p, jfloat y, jfloat t)
{
    g_roll = r;
    g_pitch = p;
    g_yaw = y;
    g_thr = t;
}
extern "C" JNIEXPORT void JNICALL
Java_com_example_elrsotg_MainActivity_nativeStart(JNIEnv *, jclass)
{
    g_run = true;
    std::thread(txLoop).detach();
}
extern "C" JNIEXPORT void JNICALL
Java_com_example_elrsotg_MainActivity_nativeStop(JNIEnv *, jclass)
{
    g_run = false;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_elrsotg_MainActivity_nativeSendCommand(JNIEnv *env, jclass, jstring command)
{
    const char *cmd = env->GetStringUTFChars(command, nullptr);
    bool result = false;

    LOGI("Processing ELRS command: %s", cmd);

    if (strcmp(cmd, "PAIR") == 0)
    {
        // ELRS bind command - MSP function 0xF4 (ELRS_BIND)
        result = sendMspCommand(0xF4);
        LOGI("PAIR command sent, result: %s", result ? "SUCCESS" : "FAILED");
    }
    else if (strcmp(cmd, "INC_SIGNAL") == 0)
    {
        // Increase power - could be MSP_SET_TX_INFO or custom ELRS command
        // For now, we'll use a placeholder MSP command
        uint8_t payload[] = {1}; // increase power flag
        result = sendMspCommand(0xF5, payload, 1);
        LOGI("INC_SIGNAL command sent, result: %s", result ? "SUCCESS" : "FAILED");
    }
    else if (strcmp(cmd, "DEC_SIGNAL") == 0)
    {
        // Decrease power
        uint8_t payload[] = {0}; // decrease power flag
        result = sendMspCommand(0xF5, payload, 1);
        LOGI("DEC_SIGNAL command sent, result: %s", result ? "SUCCESS" : "FAILED");
    }
    else if (strcmp(cmd, "BIND") == 0)
    {
        // Same as PAIR for ELRS
        result = sendMspCommand(0xF4);
        LOGI("BIND command sent, result: %s", result ? "SUCCESS" : "FAILED");
    }
    else if (strcmp(cmd, "RESET") == 0)
    {
        // Reset/reboot command - MSP reboot
        result = sendMspCommand(0x68); // MSP_REBOOT
        LOGI("RESET command sent, result: %s", result ? "SUCCESS" : "FAILED");
    }
    else if (strcmp(cmd, "MODEL_SELECT") == 0)
    {
        // Model select - could cycle through model slots
        uint8_t payload[] = {1};                   // next model
        result = sendMspCommand(0xF6, payload, 1); // Custom model select command
        LOGI("MODEL_SELECT command sent, result: %s", result ? "SUCCESS" : "FAILED");
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
static std::atomic<bool> g_telemetryRun{false};

// Forward declaration
static void processTelemetryFrame(const uint8_t* frame, int len);

static void telemetryLoop() {
    uint8_t buffer[128];
    uint8_t frame[64];
    int framePos = 0;
    bool inFrame = false;
    uint8_t expectedLen = 0;
    
    while (g_telemetryRun.load()) {
        JNIEnv* env = envGet();
        jbyteArray arr = env->NewByteArray(128);
        
        // Read from USB with short timeout
        int bytesRead = env->CallStaticIntMethod(g_bridgeClass, g_read, arr, 50);
        
        if (bytesRead > 0) {
            env->GetByteArrayRegion(arr, 0, bytesRead, (jbyte*)buffer);
            
            // Parse CRSF frames from the data
            for (int i = 0; i < bytesRead; i++) {
                uint8_t byte = buffer[i];
                
                if (!inFrame && byte == 0xC8) {
                    // Start of frame
                    frame[0] = byte;
                    framePos = 1;
                    inFrame = true;
                    expectedLen = 0;
                } else if (inFrame) {
                    frame[framePos++] = byte;
                    
                    if (framePos == 2) {
                        // Got length byte
                        expectedLen = byte;
                        if (expectedLen > 62) { // Invalid length
                            inFrame = false;
                            framePos = 0;
                        }
                    } else if (framePos >= 3 && framePos == expectedLen + 2) {
                        // Complete frame received
                        processTelemetryFrame(frame, framePos);
                        inFrame = false;
                        framePos = 0;
                    } else if (framePos >= 64) {
                        // Frame too long, reset
                        inFrame = false;
                        framePos = 0;
                    }
                }
            }
        }
        
        env->DeleteLocalRef(arr);
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
}

static void processTelemetryFrame(const uint8_t* frame, int len) {
    if (len < 4) return;
    
    uint8_t type = frame[2];
    LOGI("Received CRSF frame type: 0x%02X, len: %d", type, len);
    
    switch (type) {
        case 0x14: // LINK_STATISTICS
            if (len >= 12) {
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
                
                static int logCounter = 0;
                if (++logCounter % 50 == 0) { // Log every ~5 seconds
                    LOGI("Link Stats: RSSI1=%ddBm, RSSI2=%ddBm, LQ=%d%%, SNR=%ddB, LinkOK=%d", 
                         rssi1, rssi2, lq, snr, newLinkOk);
                }
            }
            break;
            
        case 0x08: // BATTERY_SENSOR
            if (len >= 8) {
                uint16_t voltage = (frame[3] << 8) | frame[4]; // mV
                uint16_t current = (frame[5] << 8) | frame[6]; // mA
                uint32_t capacity = (frame[7] << 16) | (frame[8] << 8) | frame[9]; // mAh
                
                LOGI("Battery: %dmV, %dmA, %dmAh", voltage, current, capacity);
            }
            break;
            
        case 0x1E: // ATTITUDE
            if (len >= 8) {
                int16_t pitch = (int16_t)((frame[3] << 8) | frame[4]);
                int16_t roll = (int16_t)((frame[5] << 8) | frame[6]);
                int16_t yaw = (int16_t)((frame[7] << 8) | frame[8]);
                
                LOGI("Attitude: Pitch=%d, Roll=%d, Yaw=%d", pitch, roll, yaw);
            }
            break;
            
        case 0x21: // FLIGHT_MODE
            if (len >= 4) {
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
Java_com_example_elrsotg_MainActivity_nativeStartTelemetry(JNIEnv* env, jclass) {
    if (!g_read) {
        g_read = env->GetStaticMethodID(g_bridgeClass, "read", "([BI)I");
    }
    
    g_telemetryRun = true;
    std::thread(telemetryLoop).detach();
    LOGI("Telemetry reader started");
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_elrsotg_MainActivity_nativeStopTelemetry(JNIEnv*, jclass) {
    g_telemetryRun = false;
    LOGI("Telemetry reader stopped");
}

// ---- Safety and Arming Controls ----
extern "C" JNIEXPORT void JNICALL
Java_com_example_elrsotg_MainActivity_nativeSetArmed(JNIEnv*, jclass, jboolean armed) {
    g_armed.store(armed);
    LOGI("Armed state changed: %s", armed ? "ARMED" : "DISARMED");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_elrsotg_MainActivity_nativeIsArmed(JNIEnv*, jclass) {
    return g_armed.load();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_elrsotg_MainActivity_nativeIsLinkOk(JNIEnv*, jclass) {
    return g_linkOk.load();
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_elrsotg_MainActivity_nativeSetSafetyOverride(JNIEnv*, jclass, jboolean override) {
    g_safetyOverride.store(override);
    LOGI("Safety override: %s", override ? "ENABLED" : "DISABLED");
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_elrsotg_MainActivity_nativeEmergencyStop(JNIEnv*, jclass) {
    g_armed.store(false);
    g_roll.store(0.0f);
    g_pitch.store(0.0f);
    g_yaw.store(0.0f);
    g_thr.store(0.0f);
    LOGI("EMERGENCY STOP - All controls zeroed and disarmed");
}

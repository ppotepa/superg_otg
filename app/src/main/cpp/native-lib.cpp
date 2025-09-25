#include <jni.h>
#include <android/log.h>
#include <array>
#include <atomic>
#include <thread>
#include <chrono>
#include <cstring>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ELRS", __VA_ARGS__)

// ---- JNI bridge to UsbBridge.write(byte[],len,timeout) ----
static JavaVM *g_vm = nullptr;
static jclass g_bridgeClass = nullptr;
static jmethodID g_write = nullptr;

static std::atomic<float> g_roll{0}, g_pitch{0}, g_yaw{0}, g_thr{0};
static std::atomic<bool> g_run{false};

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
        for (int i = 0; i < 16; i++)
            ch[i] = 992;
        ch[0] = map_stick(g_roll.load());
        ch[1] = map_stick(g_pitch.load());
        ch[2] = map_thr(g_thr.load());
        ch[3] = map_stick(g_yaw.load());

        std::array<uint8_t, 26> frame;
        build(ch, frame);

        JNIEnv *env = envGet();
        jbyteArray arr = env->NewByteArray((jsize)frame.size());
        env->SetByteArrayRegion(arr, 0, (jsize)frame.size(), (jbyte *)frame.data());
        (void)env->CallStaticIntMethod(g_bridgeClass, g_write, arr, (jint)frame.size(), 20);
        env->DeleteLocalRef(arr);

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
    // CRSF MSP frame: SYNC ADDR LEN TYPE DEST ORIG FUNCTION PAYLOADSIZE [PAYLOAD] CRC
    uint8_t idx = 0;
    out[idx++] = 0xC8;            // CRSF sync byte
    out[idx++] = 8 + payloadSize; // frame length (without sync and length bytes)
    out[idx++] = 0x7A;            // MSP command frame type
    out[idx++] = 0xEE;            // destination (ELRS TX)
    out[idx++] = 0xEA;            // origin (ELRS handset)
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

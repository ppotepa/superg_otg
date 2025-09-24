#include <jni.h>
#include <android/log.h>
#include <array>
#include <atomic>
#include <thread>
#include <chrono>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "ELRS", __VA_ARGS__)

// ---- JNI bridge to UsbBridge.write(byte[],len,timeout) ----
static JavaVM* g_vm=nullptr;
static jclass   g_bridgeClass=nullptr;
static jmethodID g_write=nullptr;

static std::atomic<float> g_roll{0}, g_pitch{0}, g_yaw{0}, g_thr{0};
static std::atomic<bool>  g_run{false};

// ---- CRSF helpers ----
static inline uint8_t crsf_crc8(const uint8_t* p, int n){
    uint8_t c=0; for(int i=0;i<n;i++){ uint8_t b = uint8_t(p[i]^c);
        for(int j=0;j<8;j++) b = (b & 0x80)? uint8_t((b<<1)^0xD5) : uint8_t(b<<1);
        c=b; } return c;
}
static inline uint16_t us2val(float us){
    float v = 172.f + (us-1000.f)*((1811.f-172.f)/1000.f);
    if(v<172) v=172; if(v>1811) v=1811; return (uint16_t)v;
}
static inline uint16_t map_stick(float x){ return us2val(1500.f + x*500.f); }
static inline uint16_t map_thr(float t){   return us2val(1000.f + t*1000.f); }

static void pack(const uint16_t ch[16], uint8_t out[22]){
    uint32_t acc=0; int bits=0, idx=0;
    for(int i=0;i<16;i++){ acc|=(uint32_t(ch[i]&0x7FF)<<bits); bits+=11;
        while(bits>=8){ out[idx++]=(uint8_t)(acc&0xFF); acc>>=8; bits-=8; } }
    if(idx<22) out[idx]=(uint8_t)(acc&0xFF);
}
static void build(const uint16_t ch[16], std::array<uint8_t,26>& f){
    f[0]=0xC8; f[1]=1+22+1; f[2]=0x16;
    pack(ch, &f[3]);
    f.back()=crsf_crc8(&f[2],23);
}

static JNIEnv* envGet(){
    JNIEnv* env=nullptr;
    if (g_vm->GetEnv((void**)&env, JNI_VERSION_1_6)!=JNI_OK) g_vm->AttachCurrentThread(&env,nullptr);
    return env;
}

static void txLoop(){
    auto period = std::chrono::milliseconds(4); // ~250 Hz
    while(g_run.load()){
        uint16_t ch[16]; for(int i=0;i<16;i++) ch[i]=992;
        ch[0]=map_stick(g_roll.load());
        ch[1]=map_stick(g_pitch.load());
        ch[2]=map_thr  (g_thr.load());
        ch[3]=map_stick(g_yaw.load());

        std::array<uint8_t,26> frame;
        build(ch, frame);

        JNIEnv* env = envGet();
        jbyteArray arr = env->NewByteArray((jsize)frame.size());
        env->SetByteArrayRegion(arr, 0, (jsize)frame.size(), (jbyte*)frame.data());
        (void) env->CallStaticIntMethod(g_bridgeClass, g_write, arr, (jint)frame.size(), 20);
        env->DeleteLocalRef(arr);

        std::this_thread::sleep_for(period);
    }
}

extern "C" jint JNI_OnLoad(JavaVM* vm, void*){
    g_vm = vm;
    JNIEnv* env = envGet();
    jclass cls = env->FindClass("com/example/elrsotg/UsbBridge");
    g_bridgeClass = (jclass)env->NewGlobalRef(cls);
    g_write = env->GetStaticMethodID(g_bridgeClass, "write", "([BII)I");
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_elrsotg_MainActivity_nativeSetAxes(JNIEnv*, jclass, jfloat r, jfloat p, jfloat y, jfloat t){
g_roll=r; g_pitch=p; g_yaw=y; g_thr=t;
}
extern "C" JNIEXPORT void JNICALL
Java_com_example_elrsotg_MainActivity_nativeStart(JNIEnv*, jclass){
g_run=true;
std::thread(txLoop).detach();
}
extern "C" JNIEXPORT void JNICALL
Java_com_example_elrsotg_MainActivity_nativeStop(JNIEnv*, jclass){
g_run=false;
}

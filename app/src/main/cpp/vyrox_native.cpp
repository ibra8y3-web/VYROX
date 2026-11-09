#include <jni.h>

#include <sstream>
#include <string>
#include <sys/utsname.h>
#include <unistd.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_vyrox_runtime_core_host_NativeHostBridge_nativeBuildInfo(
    JNIEnv* env,
    jobject /* receiver */
) {
    struct utsname system_info {};
    std::string machine = "unknown";
    if (uname(&system_info) == 0) {
        machine = system_info.machine;
    }

    const long page_size = sysconf(_SC_PAGESIZE);
    const long cpu_count = sysconf(_SC_NPROCESSORS_ONLN);

#ifdef __ANDROID_API__
    constexpr int android_api = __ANDROID_API__;
#else
    constexpr int android_api = 0;
#endif

    std::ostringstream output;
    output << "arch=" << machine
           << ";pointer_bits=" << (sizeof(void*) * 8)
           << ";page_size=" << (page_size > 0 ? page_size : 0)
           << ";logical_cpus=" << (cpu_count > 0 ? cpu_count : 1)
           << ";android_api=" << android_api
           << ";bridge_version=1";

    const std::string value = output.str();
    return env->NewStringUTF(value.c_str());
}

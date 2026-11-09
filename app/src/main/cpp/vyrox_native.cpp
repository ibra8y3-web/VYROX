#include <jni.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <sstream>
#include <string>
#include <sys/utsname.h>
#include <unistd.h>
#include <vector>

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

extern "C" JNIEXPORT jstring JNICALL
Java_com_vyrox_runtime_core_host_NativeHostBridge_nativeVulkanInfo(
    JNIEnv* env,
    jobject /* receiver */
) {
    VkApplicationInfo application_info{};
    application_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    application_info.pApplicationName = "VYROX";
    application_info.applicationVersion = 1;
    application_info.pEngineName = "VYROX";
    application_info.engineVersion = 1;
    application_info.apiVersion = VK_API_VERSION_1_0;

    VkInstanceCreateInfo create_info{};
    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pApplicationInfo = &application_info;

    VkInstance instance = VK_NULL_HANDLE;
    const VkResult create_result = vkCreateInstance(&create_info, nullptr, &instance);
    if (create_result != VK_SUCCESS || instance == VK_NULL_HANDLE) {
        const std::string value =
            "vulkan=unavailable;vkCreateInstance_result=" + std::to_string(create_result);
        return env->NewStringUTF(value.c_str());
    }

    uint32_t physical_device_count = 0;
    VkResult result = vkEnumeratePhysicalDevices(instance, &physical_device_count, nullptr);
    if (result != VK_SUCCESS || physical_device_count == 0) {
        vkDestroyInstance(instance, nullptr);
        const std::string value =
            "vulkan=loader-only;physical_devices=0;enumeration_result=" +
            std::to_string(result);
        return env->NewStringUTF(value.c_str());
    }

    std::vector<VkPhysicalDevice> physical_devices(physical_device_count);
    result = vkEnumeratePhysicalDevices(
        instance,
        &physical_device_count,
        physical_devices.data()
    );
    if (result != VK_SUCCESS || physical_devices.empty()) {
        vkDestroyInstance(instance, nullptr);
        const std::string value =
            "vulkan=unavailable;enumeration_result=" + std::to_string(result);
        return env->NewStringUTF(value.c_str());
    }

    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(physical_devices.front(), &properties);
    std::string device_name(properties.deviceName);
    std::replace(device_name.begin(), device_name.end(), ';', ',');

    std::ostringstream output;
    output << "vulkan=available"
           << ";device=" << device_name
           << ";api=" << VK_VERSION_MAJOR(properties.apiVersion)
           << "." << VK_VERSION_MINOR(properties.apiVersion)
           << "." << VK_VERSION_PATCH(properties.apiVersion)
           << ";driver_version=" << properties.driverVersion
           << ";physical_devices=" << physical_device_count;

    vkDestroyInstance(instance, nullptr);
    const std::string value = output.str();
    return env->NewStringUTF(value.c_str());
}

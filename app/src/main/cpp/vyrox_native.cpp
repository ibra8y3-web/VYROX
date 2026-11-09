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
    uint32_t loader_version = VK_API_VERSION_1_0;
    auto enumerate_instance_version =
        reinterpret_cast<PFN_vkEnumerateInstanceVersion>(
            vkGetInstanceProcAddr(VK_NULL_HANDLE, "vkEnumerateInstanceVersion")
        );
    if (enumerate_instance_version != nullptr) {
        if (enumerate_instance_version(&loader_version) != VK_SUCCESS) {
            loader_version = VK_API_VERSION_1_0;
        }
    }

    VkApplicationInfo application_info{};
    application_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    application_info.pApplicationName = "VYROX";
    application_info.applicationVersion = 1;
    application_info.pEngineName = "VYROX";
    application_info.engineVersion = 1;
    application_info.apiVersion = std::min(loader_version, VK_API_VERSION_1_3);

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

    const VkPhysicalDevice physical_device = physical_devices.front();
    VkPhysicalDeviceProperties properties{};
    vkGetPhysicalDeviceProperties(physical_device, &properties);

    uint32_t extension_count = 0;
    result = vkEnumerateDeviceExtensionProperties(
        physical_device, nullptr, &extension_count, nullptr
    );
    if (result != VK_SUCCESS) {
        vkDestroyInstance(instance, nullptr);
        const std::string value =
            "vulkan=unavailable;extension_query_result=" + std::to_string(result);
        return env->NewStringUTF(value.c_str());
    }

    std::vector<VkExtensionProperties> extension_properties(extension_count);
    if (extension_count > 0) {
        result = vkEnumerateDeviceExtensionProperties(
            physical_device, nullptr, &extension_count, extension_properties.data()
        );
        if (result != VK_SUCCESS) {
            vkDestroyInstance(instance, nullptr);
            const std::string value =
                "vulkan=unavailable;extension_query_result=" + std::to_string(result);
            return env->NewStringUTF(value.c_str());
        }
    }

    std::vector<std::string> extensions;
    extensions.reserve(extension_properties.size());
    for (const auto& extension : extension_properties) {
        extensions.emplace_back(extension.extensionName);
    }

    const std::vector<std::string> required_extensions = {
        "VK_EXT_depth_clip_enable",
        "VK_KHR_maintenance5",
        "VK_KHR_maintenance6",
        "VK_EXT_robustness2",
        "VK_EXT_transform_feedback"
    };

    std::vector<std::string> missing_extensions;
    for (const auto& required : required_extensions) {
        if (std::find(extensions.begin(), extensions.end(), required) == extensions.end()) {
            missing_extensions.push_back(required);
        }
    }

    bool descriptor_indexing = false;
#if defined(VK_VERSION_1_1) && defined(VK_VERSION_1_2)
    if (properties.apiVersion >= VK_API_VERSION_1_2) {
        auto get_features2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
            vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceFeatures2")
        );
        if (get_features2 != nullptr) {
            VkPhysicalDeviceVulkan12Features vulkan12_features{};
            vulkan12_features.sType =
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES;
            VkPhysicalDeviceFeatures2 features2{};
            features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
            features2.pNext = &vulkan12_features;
            get_features2(physical_device, &features2);
            descriptor_indexing = vulkan12_features.descriptorIndexing == VK_TRUE;
        }
    }
#endif

    std::string device_name(properties.deviceName);
    std::replace(device_name.begin(), device_name.end(), ';', ',');
    std::ostringstream extension_output;
    for (size_t index = 0; index < extensions.size(); ++index) {
        if (index != 0) extension_output << ",";
        extension_output << extensions[index];
    }

    std::ostringstream missing_output;
    for (size_t index = 0; index < missing_extensions.size(); ++index) {
        if (index != 0) missing_output << ",";
        missing_output << missing_extensions[index];
    }

    const bool vulkan_13 =
        VK_VERSION_MAJOR(properties.apiVersion) > 1 ||
        (VK_VERSION_MAJOR(properties.apiVersion) == 1 &&
         VK_VERSION_MINOR(properties.apiVersion) >= 3);
    const bool dxvk_requirements_detected =
        vulkan_13 &&
        missing_extensions.empty() &&
        properties.limits.maxPushConstantsSize >= 256 &&
        descriptor_indexing;

    std::ostringstream output;
    output << "vulkan=available"
           << ";device=" << device_name
           << ";api=" << VK_VERSION_MAJOR(properties.apiVersion)
           << "." << VK_VERSION_MINOR(properties.apiVersion)
           << "." << VK_VERSION_PATCH(properties.apiVersion)
           << ";driver_version=" << properties.driverVersion
           << ";physical_devices=" << physical_device_count
           << ";max_push_constants=" << properties.limits.maxPushConstantsSize
           << ";descriptor_indexing=" << (descriptor_indexing ? "true" : "false")
           << ";extensions=" << extension_output.str()
           << ";dxvk_missing=" << missing_output.str()
           << ";dxvk27_compatible=" << (dxvk_requirements_detected ? "true" : "false");

    vkDestroyInstance(instance, nullptr);
    const std::string value = output.str();
    return env->NewStringUTF(value.c_str());
}

package com.vyrox.engine

enum class EngineIntegrationState {
    RESEARCH_ONLY,
    SOURCE_BUILT,
    PACKAGED_IN_APK,
    DEVICE_VERIFIED,
    NOT_BUNDLED
}

data class RuntimeEngineDescriptor(
    val id: String,
    val name: String,
    val role: String,
    val upstreamUrl: String,
    val license: String,
    val integrationState: EngineIntegrationState,
    val notes: String
)

/**
 * Central inventory of upstream runtime components.
 *
 * A catalog entry is not a bundled dependency. Each integration remains gated
 * by Android ABI, Linux userland, licensing, and physical-device verification.
 */
object RuntimeEngineCatalog {
    val components: List<RuntimeEngineDescriptor> = listOf(
        RuntimeEngineDescriptor(
            id = "wine",
            name = "Wine",
            role = "Windows API compatibility",
            upstreamUrl = "https://gitlab.winehq.org/wine/wine",
            license = "LGPL-2.1-or-later",
            integrationState = EngineIntegrationState.RESEARCH_ONLY,
            notes = "The intended x64-first path is x86-64 Wine WOW64 running through Box64 inside an ARM64 glibc RootFS. Wine and its x86-64 libraries are not installed or executed yet."
        ),
        RuntimeEngineDescriptor(
            id = "proot",
            name = "PRoot",
            role = "Rootless Linux userland and filesystem redirection",
            upstreamUrl = "https://github.com/termux/proot",
            license = "GPL-2.0-or-later",
            integrationState = EngineIntegrationState.PACKAGED_IN_APK,
            notes = "Pinned source v5.1.107.102 builds with libandroid-shmem and libtalloc; the CI workflow packages the ARM64 PRoot PIE and static loader into the debug APK. Device execve, ptrace, and RootFS execution remain unverified."
        ),
        RuntimeEngineDescriptor(
            id = "box64",
            name = "Box64",
            role = "x86-64 Linux user-space translation",
            upstreamUrl = "https://github.com/ptitSeb/box64",
            license = "MIT",
            integrationState = EngineIntegrationState.RESEARCH_ONLY,
            notes = "Translates x86-64 Linux ELF programs on ARM64 Linux. It does not load Windows PE files; the planned path is x86-64 Wine WOW64 plus an x86-64 glibc library set inside the ARM64 RootFS."
        ),
        RuntimeEngineDescriptor(
            id = "fex",
            name = "FEX",
            role = "x86 / x86-64 Linux user-space translation",
            upstreamUrl = "https://github.com/FEX-Emu/FEX",
            license = "MIT",
            integrationState = EngineIntegrationState.RESEARCH_ONLY,
            notes = "Alternative to Box64, not part of the initial x64 path. Requires a compatible ARM64 Linux host and a provisioned x86-64 guest environment."
        ),
        RuntimeEngineDescriptor(
            id = "dxvk",
            name = "DXVK",
            role = "Direct3D 8/9/10/11 over Vulkan",
            upstreamUrl = "https://github.com/doitsujin/dxvk",
            license = "zlib",
            integrationState = EngineIntegrationState.RESEARCH_ONLY,
            notes = "DXVK 3.x requires Vulkan 1.4 plus specific extensions and features; Android mobile drivers are not first-party supported."
        ),
        RuntimeEngineDescriptor(
            id = "vkd3d-proton",
            name = "VKD3D-Proton",
            role = "Direct3D 12 over Vulkan",
            upstreamUrl = "https://github.com/HansKristian-Work/vkd3d-proton",
            license = "Review upstream license at pinned revision",
            integrationState = EngineIntegrationState.RESEARCH_ONLY,
            notes = "Requires a modern Vulkan 1.3 feature set; no Android device compatibility is assumed."
        ),
        RuntimeEngineDescriptor(
            id = "linux-userland",
            name = "Linux userland",
            role = "glibc libraries and runtime filesystem",
            upstreamUrl = "https://github.com/termux/proot-distro",
            license = "Per-distribution and per-package",
            integrationState = EngineIntegrationState.NOT_BUNDLED,
            notes = "A Capsule C:/D: directory tree is not a Linux RootFS."
        )
    )

    fun find(id: String): RuntimeEngineDescriptor? =
        components.firstOrNull { it.id == id }
}

data class VulkanProbe(
    val deviceAvailable: Boolean,
    val deviceName: String?,
    val apiMajor: Int,
    val apiMinor: Int,
    val driverVersion: String?,
    val extensions: Set<String>,
    val maxPushConstantsBytes: Int,
    val descriptorIndexing: Boolean,
    val scalarBlockLayout: Boolean,
    val shaderInt8: Boolean,
    val shaderInt16: Boolean,
    val shaderInt64: Boolean
) {
    val hasVulkan14: Boolean
        get() = apiMajor > 1 || (apiMajor == 1 && apiMinor >= 4)

    val supportsDxvk3Requirements: Boolean
        get() = deviceAvailable &&
            hasVulkan14 &&
            DXVK_3X_REQUIRED_EXTENSIONS.all { it in extensions } &&
            maxPushConstantsBytes >= 256 &&
            descriptorIndexing &&
            scalarBlockLayout &&
            shaderInt8 &&
            shaderInt16 &&
            shaderInt64

    companion object {
        val DXVK_3X_REQUIRED_EXTENSIONS = setOf(
            "VK_EXT_depth_clip_enable",
            "VK_KHR_maintenance5",
            "VK_KHR_maintenance6",
            "VK_KHR_load_store_op_none",
            "VK_EXT_robustness2",
            "VK_EXT_transform_feedback"
        )
    }
}

object VulkanProbeParser {
    fun parse(raw: String?): VulkanProbe {
        val values = raw.orEmpty()
            .split(';')
            .mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) null
                else part.substring(0, separator) to part.substring(separator + 1)
            }
            .toMap()

        val api = values["api"].orEmpty().split('.')
        val extensions = values["extensions"]
            .orEmpty()
            .split(',')
            .filter { it.isNotBlank() }
            .toSet()

        return VulkanProbe(
            deviceAvailable = values["vulkan"] == "available",
            deviceName = values["device"]?.takeIf { it.isNotBlank() },
            apiMajor = api.getOrNull(0)?.toIntOrNull() ?: 0,
            apiMinor = api.getOrNull(1)?.toIntOrNull() ?: 0,
            driverVersion = values["driver_version"]?.takeIf { it.isNotBlank() },
            extensions = extensions,
            maxPushConstantsBytes = values["max_push_constants"]?.toIntOrNull() ?: 0,
            descriptorIndexing = values["descriptor_indexing"].toBoolean(),
            scalarBlockLayout = values["scalar_block_layout"].toBoolean(),
            shaderInt8 = values["shader_int8"].toBoolean(),
            shaderInt16 = values["shader_int16"].toBoolean(),
            shaderInt64 = values["shader_int64"].toBoolean()
        )
    }
}

data class X64RuntimePrerequisites(
    val hostAbis: Set<String>,
    val arm64RootFsInstalled: Boolean,
    val x86_64WineWow64Installed: Boolean,
    val x86_64RuntimeLibrariesInstalled: Boolean,
    val box64Installed: Boolean,
    val prootInstalled: Boolean,
    val displayBridgeInstalled: Boolean,
    val audioBridgeInstalled: Boolean,
    val inputBridgeInstalled: Boolean,
    val d3d11Requested: Boolean,
    val dxvkInstalled: Boolean,
    val vulkan: VulkanProbe,
    val windowsPeLoaderInstalled: Boolean = false,
    val capsuleDriveMappingInstalled: Boolean = false,
    val physicalDeviceExecutionVerified: Boolean = false
)

data class RuntimeReadiness(
    val preflightPassed: Boolean,
    val blockers: List<String>
)

/**
 * Produces an honest readiness assessment only. It never starts a process.
 */
object RuntimeCompatibilityPlanner {
    fun assessWindowsX64(prerequisites: X64RuntimePrerequisites): RuntimeReadiness {
        val blockers = buildList {
            if ("arm64-v8a" !in prerequisites.hostAbis) {
                add("The Android ARM64 host ABI is not available.")
            }
            if (!prerequisites.arm64RootFsInstalled) {
                add("An ARM64 glibc Linux RootFS has not been installed for PRoot.")
            }
            if (!prerequisites.x86_64WineWow64Installed) {
                add("An x86-64 Wine WOW64 runtime has not been installed.")
            }
            if (!prerequisites.x86_64RuntimeLibrariesInstalled) {
                add("The x86-64 glibc and Wine runtime libraries are not provisioned.")
            }
            if (!prerequisites.prootInstalled) {
                add("PRoot has not passed Android device execution tests.")
            }
            if (!prerequisites.box64Installed) {
                add("Box64 has not been installed in the ARM64 Linux RootFS.")
            }
            if (!prerequisites.windowsPeLoaderInstalled) {
                add("The VYROX Windows PE loader/session bridge is not integrated.")
            }
            if (!prerequisites.capsuleDriveMappingInstalled) {
                add("Capsule C:/D: paths are not mapped into a Windows runtime.")
            }
            if (!prerequisites.displayBridgeInstalled) {
                add("A Windows window/display bridge to the Android desktop is not integrated.")
            }
            if (!prerequisites.audioBridgeInstalled) {
                add("A Windows audio bridge to Android audio output is not integrated.")
            }
            if (!prerequisites.inputBridgeInstalled) {
                add("Windows keyboard, pointer, and game-controller input mapping is not integrated.")
            }
            if (!prerequisites.physicalDeviceExecutionVerified) {
                add("No Windows executable has passed a physical-device execution test.")
            }
            if (prerequisites.d3d11Requested) {
                if (!prerequisites.dxvkInstalled) {
                    add("DXVK has not been integrated.")
                }
                if (!prerequisites.vulkan.supportsDxvk3Requirements) {
                    add("The selected Vulkan device has not passed the DXVK 3.x feature requirements.")
                }
            }
        }
        return RuntimeReadiness(preflightPassed = blockers.isEmpty(), blockers = blockers)
    }
}

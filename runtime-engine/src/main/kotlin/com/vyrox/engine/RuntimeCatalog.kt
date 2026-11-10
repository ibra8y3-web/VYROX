package com.vyrox.engine

enum class EngineIntegrationState {
    RESEARCH_ONLY,
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
            notes = "Requires an Android-compatible Unix userland and a tested ARM64 build."
        ),
        RuntimeEngineDescriptor(
            id = "proot",
            name = "PRoot",
            role = "Rootless Linux userland and filesystem redirection",
            upstreamUrl = "https://github.com/termux/proot",
            license = "GPL-2.0-or-later",
            integrationState = EngineIntegrationState.RESEARCH_ONLY,
            notes = "Pinned source candidate v5.1.107.102. Requires libandroid-shmem and libtalloc, APK native-library packaging, and physical-device ptrace tests."
        ),
        RuntimeEngineDescriptor(
            id = "box64",
            name = "Box64",
            role = "x86-64 Linux user-space translation",
            upstreamUrl = "https://github.com/ptitSeb/box64",
            license = "MIT",
            integrationState = EngineIntegrationState.RESEARCH_ONLY,
            notes = "Not a standalone Windows PE loader; normally paired with Wine in a Linux userland."
        ),
        RuntimeEngineDescriptor(
            id = "fex",
            name = "FEX",
            role = "x86 / x86-64 Linux user-space translation",
            upstreamUrl = "https://github.com/FEX-Emu/FEX",
            license = "MIT",
            integrationState = EngineIntegrationState.RESEARCH_ONLY,
            notes = "Upstream targets Linux, not Android directly, and requires an x86-64 RootFS."
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
    val linuxUserlandInstalled: Boolean,
    val x86_64RootFsInstalled: Boolean,
    val arm64WineInstalled: Boolean,
    val box64Installed: Boolean,
    val prootInstalled: Boolean,
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
            if (!prerequisites.linuxUserlandInstalled) {
                add("A compatible Linux userland has not been installed.")
            }
            if (!prerequisites.x86_64RootFsInstalled) {
                add("An x86-64 guest RootFS has not been installed.")
            }
            if (!prerequisites.arm64WineInstalled) {
                add("An ARM64 Wine build has not been integrated.")
            }
            if (!prerequisites.prootInstalled) {
                add("PRoot has not been built and integrated for Android.")
            }
            if (!prerequisites.box64Installed) {
                add("Box64 has not been built and integrated for the selected userland.")
            }
            if (!prerequisites.windowsPeLoaderInstalled) {
                add("The VYROX Windows PE loader/session bridge is not integrated.")
            }
            if (!prerequisites.capsuleDriveMappingInstalled) {
                add("Capsule C:/D: paths are not mapped into a Windows runtime.")
            }
            if (!prerequisites.physicalDeviceExecutionVerified) {
                add("No Windows executable has passed a physical-device execution test.")
            }
            if (prerequisites.d3d11Requested) {
                if (!prerequisites.dxvkInstalled) {
                    add("DXVK has not been integrated.")
                }
                if (!prerequisites.vulkan.supportsDxvk3Requirements) {
                    add("The Vulkan device has not passed the DXVK 3.x Vulkan 1.4 feature requirements.")
                }
            }
        }
        return RuntimeReadiness(preflightPassed = blockers.isEmpty(), blockers = blockers)
    }
}

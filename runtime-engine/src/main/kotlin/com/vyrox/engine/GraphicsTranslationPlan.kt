package com.vyrox.engine

import java.io.File
import java.nio.file.Files
import java.util.Properties

data class GraphicsTranslationInfo(
    val dxvkVersion: String,
    val vkd3dProtonVersion: String,
    val vulkanBackend: String
)

/**
 * Installs pinned upstream Direct3D-to-Vulkan translation layers into one
 * verified Wine prefix. This only marks the DLLs installed after a Vulkan
 * device can be opened from inside the ARM64 RootFS through Box64.
 */
object GraphicsTranslationPlan {
    const val DXVK_VERSION = "3.1.1"
    const val VKD3D_PROTON_VERSION = "3.1"
    const val METADATA_FILE = "vyrox-graphics.properties"

    fun createInstallScript(
        guestPrefixPath: String,
        rootFsDirectory: File
    ): String {
        require(guestPrefixPath.matches(PREFIX_PATH)) {
            "The Wine prefix path is not a VYROX Capsule prefix."
        }
        val wine = WineRuntimePlan.readInstallation(rootFsDirectory)
            ?: throw IllegalStateException("Install and verify Wine 11 WoW64 before adding graphics translation.")
        return """
            set -eu
            BOX64='@BOX64@'
            WINE_LOADER='@WINE@'
            WINEBOOT_EXE='@WINEBOOT@'
            PREFIX='@PREFIX@'
            DXVK_VERSION='@DXVK@'
            VKD3D_VERSION='@VKD3D@'

            test -x "@D@BOX64"
            test -x "@D@WINE_LOADER"
            test -f "@D@WINEBOOT_EXE"
            test -s "@D@PREFIX/system.reg"
            test -s "@D@PREFIX/user.reg"

            export BOX64_LD_LIBRARY_PATH='/usr/lib/wine:/usr/lib/x86_64-linux-gnu/wine/x86_64-unix:/usr/lib/x86_64-linux-gnu:/lib/x86_64-linux-gnu'
            export WINEPREFIX="@D@PREFIX"
            export WINEARCH=win64
            export WINEDEBUG=-all
            export VYROX_BOX64="@D@BOX64"
            export VYROX_WINE_LOADER="@D@WINE_LOADER"
            export VYROX_WINEBOOT_EXE="@D@WINEBOOT_EXE"

            if ! command -v zstd >/dev/null 2>&1; then
                apt-get update
                apt-get install -y --no-install-recommends zstd
            fi

            VULKANINFO_BIN="$(command -v vulkaninfo 2>/dev/null || true)"
            test -n "@D@VULKANINFO_BIN" && test -f "@D@VULKANINFO_BIN" || {
                echo "The RootFS has no vulkaninfo binary; Vulkan is not available."
                exit 61
            }
            VULKANINFO_MACHINE="$(od -An -tu2 -j18 -N2 "@D@VULKANINFO_BIN" 2>/dev/null | tr -d ' ' || true)"
            if [ "@D@VULKANINFO_MACHINE" = "62" ]; then
                for icd in /usr/share/vulkan/icd.d/lvp_icd.x86_64.json /etc/vulkan/icd.d/lvp_icd.x86_64.json; do
                    if [ -f "@D@icd" ]; then export VK_ICD_FILENAMES="@D@icd"; break; fi
                done
                VULKAN_OUTPUT="@D@("@D@BOX64" "@D@VULKANINFO_BIN" --summary 2>&1 || true)"
            else
                VULKAN_OUTPUT="@D@("@D@VULKANINFO_BIN" --summary 2>&1 || true)"
            fi
            printf '%s\n' "@D@VULKAN_OUTPUT" | tail -n 24
            VULKAN_BACKEND=unavailable
            if printf '%s' "@D@VULKAN_OUTPUT" | grep -qiE 'lavapipe|llvmpipe'; then
                VULKAN_BACKEND=lavapipe-software
            elif printf '%s' "@D@VULKAN_OUTPUT" | grep -qiE 'deviceName|GPU id'; then
                VULKAN_BACKEND=vulkan-device
            fi
            test "@D@VULKAN_BACKEND" != unavailable || {
                echo "Vulkan could not initialize inside this RootFS. DXVK and VKD3D-Proton were not installed."
                exit 62
            }

            WRAPPER_DIR='/tmp/vyrox-wine-wrappers'
            TEMP_DIR='/tmp/vyrox-graphics-install'
            mkdir -p "@D@WRAPPER_DIR" "@D@TEMP_DIR"
            cat > "@D@WRAPPER_DIR/wine" <<'VYROX_WINE'
            #!/bin/sh
            exec "@D@VYROX_BOX64" "@D@VYROX_WINE_LOADER" "@D@@"
            VYROX_WINE
            cat > "@D@WRAPPER_DIR/wine64" <<'VYROX_WINE64'
            #!/bin/sh
            exec "@D@VYROX_BOX64" "@D@VYROX_WINE_LOADER" "@D@@"
            VYROX_WINE64
            cat > "@D@WRAPPER_DIR/wineboot" <<'VYROX_WINEBOOT'
            #!/bin/sh
            exec "@D@VYROX_BOX64" "@D@VYROX_WINE_LOADER" "@D@VYROX_WINEBOOT_EXE" "@D@@"
            VYROX_WINEBOOT
            chmod 0755 "@D@WRAPPER_DIR/wine" "@D@WRAPPER_DIR/wine64" "@D@WRAPPER_DIR/wineboot"
            export PATH="@D@WRAPPER_DIR:@D@PATH"
            command -v wine >/dev/null
            command -v wine64 >/dev/null
            wine --version

            DXVK_ARCHIVE="/tmp/dxvk-@D@DXVK_VERSION.tar.gz"
            VKD3D_ARCHIVE="/tmp/vkd3d-proton-@D@VKD3D_VERSION.tar.zst"
            wget -q --https-only --timeout=60 --tries=3 \
                "https://github.com/doitsujin/dxvk/releases/download/v@D@DXVK_VERSION/dxvk-@D@DXVK_VERSION.tar.gz" \
                -O "@D@DXVK_ARCHIVE"
            wget -q --https-only --timeout=60 --tries=3 \
                "https://github.com/HansKristian-Work/vkd3d-proton/releases/download/v@D@VKD3D_VERSION/vkd3d-proton-@D@VKD3D_VERSION.tar.zst" \
                -O "@D@VKD3D_ARCHIVE"
            tar -tzf "@D@DXVK_ARCHIVE" | grep -F 'setup_dxvk.sh' >/dev/null
            tar --zstd -tf "@D@VKD3D_ARCHIVE" | grep -F 'setup_vkd3d_proton.sh' >/dev/null

            DXVK_DIR="@D@TEMP_DIR/dxvk"
            VKD3D_DIR="@D@TEMP_DIR/vkd3d"
            mkdir -p "@D@DXVK_DIR" "@D@VKD3D_DIR"
            tar -xzf "@D@DXVK_ARCHIVE" -C "@D@DXVK_DIR" --strip-components=1
            tar --zstd -xf "@D@VKD3D_ARCHIVE" -C "@D@VKD3D_DIR" --strip-components=1
            test -x "@D@DXVK_DIR/setup_dxvk.sh"
            test -x "@D@VKD3D_DIR/setup_vkd3d_proton.sh"

            "@D@DXVK_DIR/setup_dxvk.sh" install --with-d3d10 --without-dxgi
            "@D@VKD3D_DIR/setup_vkd3d_proton.sh" install

            CAPSULE_C="$(readlink -f "@D@PREFIX/drive_c")"
            test -d "@D@CAPSULE_C" || {
                echo "The Capsule C: drive symlink could not be resolved." >&2
                exit 70
            }
            case "@D@CAPSULE_C" in
                /mnt/vyrox/capsules/*/drive_c) ;;
                *) echo "The Capsule C: drive escaped the VYROX storage root." >&2; exit 70 ;;
            esac
            WINDOWS_DIR="$(find "@D@CAPSULE_C" -mindepth 1 -maxdepth 1 -type d -iname windows -print -quit)"
            test -n "@D@WINDOWS_DIR"
            SYSTEM32="$(find "@D@WINDOWS_DIR" -mindepth 1 -maxdepth 1 -type d -iname system32 -print -quit)"
            SYSWOW64="$(find "@D@WINDOWS_DIR" -mindepth 1 -maxdepth 1 -type d -iname syswow64 -print -quit)"
            test -n "@D@SYSTEM32" && test -n "@D@SYSWOW64"
            for dll in d3d9.dll d3d10core.dll d3d11.dll dxgi.dll; do
                test -s "@D@SYSTEM32/@D@dll" || { echo "DXVK did not install 64-bit @D@dll."; exit 71; }
                test -s "@D@SYSWOW64/@D@dll" || { echo "DXVK did not install 32-bit @D@dll."; exit 72; }
            done
            for dll in d3d12.dll d3d12core.dll; do
                test -s "@D@SYSTEM32/@D@dll" || { echo "VKD3D-Proton did not install @D@dll."; exit 73; }
            done

            {
                printf 'dxvk_version=%s\n' "@D@DXVK_VERSION"
                printf 'vkd3d_proton_version=%s\n' "@D@VKD3D_VERSION"
                printf 'vulkan_backend=%s\n' "@D@VULKAN_BACKEND"
                printf 'installed_at_epoch_seconds=%s\n' "@D@(date +%s)"
                printf 'd3d9=dll-installed\n'
                printf 'd3d10=dll-installed\n'
                printf 'd3d11=dll-installed\n'
                printf 'd3d12=dll-installed\n'
            } > "@D@PREFIX/@METADATA@"
            rm -rf "@D@TEMP_DIR" "@D@DXVK_ARCHIVE" "@D@VKD3D_ARCHIVE"
            echo "VYROX_GRAPHICS_TRANSLATION_READY dxvk=@D@DXVK_VERSION vkd3d=@D@VKD3D_VERSION vulkan=@D@VULKAN_BACKEND"
        """.trimIndent()
            .replace("@D@", '$'.toString())
            .replace("@BOX64@", WineRuntimePlan.BOX64_BINARY)
            .replace("@WINE@", wine.loaderGuestPath)
            .replace("@WINEBOOT@", wine.winebootGuestPath)
            .replace("@PREFIX@", guestPrefixPath)
            .replace("@DXVK@", DXVK_VERSION)
            .replace("@VKD3D@", VKD3D_PROTON_VERSION)
            .replace("@METADATA@", METADATA_FILE)
    }

    fun readInstallation(prefixDirectory: File, capsuleDriveC: File): GraphicsTranslationInfo? = runCatching {
        if (
            Files.isSymbolicLink(prefixDirectory.toPath()) ||
            !prefixDirectory.isDirectory ||
            Files.isSymbolicLink(capsuleDriveC.toPath()) ||
            !capsuleDriveC.isDirectory
        ) return null
        val metadataFile = File(prefixDirectory, METADATA_FILE)
        if (Files.isSymbolicLink(metadataFile.toPath()) || !metadataFile.isFile) return null
        val metadata = Properties()
        metadataFile.inputStream().buffered().use { metadata.load(it) }
        val dxvk = metadata.getProperty("dxvk_version") ?: return null
        val vkd3d = metadata.getProperty("vkd3d_proton_version") ?: return null
        val backend = metadata.getProperty("vulkan_backend") ?: return null
        if (dxvk != DXVK_VERSION || vkd3d != VKD3D_PROTON_VERSION || backend == "unavailable") return null

        val windows = capsuleDriveC.listFiles()
            ?.firstOrNull { it.isDirectory && it.name.equals("windows", ignoreCase = true) }
            ?: return null
        val system32 = findChild(windows, "system32") ?: return null
        val syswow64 = findChild(windows, "syswow64") ?: return null
        val dxvkDlls = listOf("d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll")
        if (
            dxvkDlls.any { !File(system32, it).isFile || Files.isSymbolicLink(File(system32, it).toPath()) } ||
            dxvkDlls.any { !File(syswow64, it).isFile || Files.isSymbolicLink(File(syswow64, it).toPath()) } ||
            listOf("d3d12.dll", "d3d12core.dll").any {
                !File(system32, it).isFile || Files.isSymbolicLink(File(system32, it).toPath())
            }
        ) return null
        GraphicsTranslationInfo(dxvk, vkd3d, backend)
    }.getOrNull()

    fun isInstalled(prefixDirectory: File, capsuleDriveC: File): Boolean =
        readInstallation(prefixDirectory, capsuleDriveC) != null

    private fun findChild(parent: File, name: String): File? =
        parent.listFiles()?.firstOrNull { it.isDirectory && it.name.equals(name, ignoreCase = true) }

    private val PREFIX_PATH = Regex("/opt/vyrox/prefixes/[0-9a-fA-F-]{36}")
}

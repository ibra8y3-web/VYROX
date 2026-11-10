package com.vyrox.engine

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.Properties

data class WineInstallationInfo(
    val version: String,
    val loaderGuestPath: String,
    val winebootGuestPath: String,
    val vulkanBackend: String
)

data class GraphicsTranslationInfo(
    val dxvkVersion: String,
    val vkd3dProtonVersion: String,
    val vulkanBackend: String
)

enum class WindowsInstallerKind { EXE, MSI }

object WineRuntimePlan {
    const val WINE_VERSION_MINIMUM_MAJOR = 11
    const val WINEHQ_KEY_URL = "https://dl.winehq.org/wine-builds/winehq.key"
    const val METADATA_PATH = "opt/vyrox/wine-stable.properties"
    const val BOX64_BINARY = "/usr/local/bin/box64"
    const val DXVK_VERSION = "3.1.1"
    const val VKD3D_PROTON_VERSION = "3.1"
    const val GUEST_CAPSULE_ROOT = "/mnt/vyrox/capsules"

    fun createInstallShellScript(): String = """
        set -eu
        export DEBIAN_FRONTEND=noninteractive
        read_os_value() {
            awk -F= -v key="@D@1" '
                @D@1 == key {
                    value = substr(@D@0, index(@D@0, "=") + 1)
                    sub(/^"/, "", value)
                    sub(/"@D@/, "", value)
                    sub(/^\047/, "", value)
                    sub(/\047@D@/, "", value)
                    print value
                    exit
                }
            ' /etc/os-release 2>/dev/null | tr -cd 'A-Za-z0-9._-'
        }
        OS_ID="@D@(read_os_value ID)"
        OS_CODENAME="@D@(read_os_value VERSION_CODENAME)"
        case "@D@OS_ID:@D@OS_CODENAME" in
            debian:bookworm|debian:trixie|ubuntu:jammy|ubuntu:noble) ;;
            *) echo "Unsupported WineHQ RootFS: @D@OS_ID @D@OS_CODENAME" >&2; exit 41 ;;
        esac
        command -v dpkg >/dev/null 2>&1 || { echo "dpkg is missing." >&2; exit 42; }
        command -v apt-get >/dev/null 2>&1 || { echo "APT is missing." >&2; exit 43; }
        mkdir -p /tmp /etc/apt/keyrings /etc/apt/sources.list.d
        chmod 1777 /tmp
        apt-get update
        apt-get install -y --no-install-recommends ca-certificates wget gnupg
        if ! dpkg --print-foreign-architectures | grep -qx amd64; then
            dpkg --add-architecture amd64
        fi
        wget -qO- 'https://dl.winehq.org/wine-builds/winehq.key' | gpg --dearmor --yes -o /etc/apt/keyrings/vyrox-winehq.gpg
        chmod 0644 /etc/apt/keyrings/vyrox-winehq.gpg
        cat > /etc/apt/sources.list.d/vyrox-winehq.sources <<EOF
        Types: deb
        URIs: https://dl.winehq.org/wine-builds/@D@OS_ID/
        Suites: @D@OS_CODENAME
        Components: main
        Architectures: amd64
        Signed-By: /etc/apt/keyrings/vyrox-winehq.gpg
        EOF
        apt-get update
        apt-get install -y --install-recommends winehq-stable
        apt-get install -y --no-install-recommends xvfb x11vnc fluxbox x11-utils pulseaudio pulseaudio-utils netcat-openbsd icoutils zstd
        apt-get install -y --no-install-recommends libvulkan1:amd64 mesa-vulkan-drivers:amd64 vulkan-tools:amd64

        BOX64='@BOX64@'
        test -x "@D@BOX64" || { echo "Build Box64 before installing Wine." >&2; exit 44; }

        WINE=''
        for candidate in /usr/lib/wine/wine64 /usr/lib/x86_64-linux-gnu/wine/wine64 /opt/wine-stable/bin/wine64; do
            if [ -f "@D@candidate" ] && [ -x "@D@candidate" ]; then WINE="@D@candidate"; break; fi
        done
        if [ -z "@D@WINE" ]; then
            WINE="@D@(find /usr/lib /opt -type f -name wine64 -perm /111 -print -quit 2>/dev/null || true)"
        fi
        test -n "@D@WINE" && test -x "@D@WINE" || { echo "The x86-64 Wine ELF loader was not found." >&2; exit 45; }
        WINE="@D@(readlink -f "@D@WINE")"

        WINEBOOT=''
        for candidate in /usr/lib/x86_64-linux-gnu/wine/x86_64-windows/wineboot.exe /usr/lib/wine/x86_64-windows/wineboot.exe /opt/wine-stable/lib/wine/x86_64-windows/wineboot.exe; do
            if [ -f "@D@candidate" ]; then WINEBOOT="@D@candidate"; break; fi
        done
        if [ -z "@D@WINEBOOT" ]; then
            WINEBOOT="@D@(find /usr/lib /opt -type f -path '*/x86_64-windows/wineboot.exe' -print -quit 2>/dev/null || true)"
        fi
        test -n "@D@WINEBOOT" && test -f "@D@WINEBOOT" || { echo "The Windows wineboot.exe payload was not found." >&2; exit 46; }

        WINE_MACHINE="@D@(od -An -tu2 -j18 -N2 "@D@WINE" | tr -d ' ')"
        test "@D@WINE_MACHINE" = "62" || { echo "wine64 is not an x86-64 Linux ELF." >&2; exit 47; }
        WINEBOOT_MAGIC="@D@(od -An -tx1 -N2 "@D@WINEBOOT" | tr -d ' \n')"
        test "@D@WINEBOOT_MAGIC" = "4d5a" || { echo "wineboot payload is not a Windows PE file." >&2; exit 48; }

        export BOX64_LD_LIBRARY_PATH='/usr/lib/wine:/usr/lib/x86_64-linux-gnu/wine/x86_64-unix:/usr/lib/x86_64-linux-gnu:/lib/x86_64-linux-gnu'
        WINE_VERSION_OUTPUT="@D@("@D@BOX64" "@D@WINE" --version 2>&1)"
        printf '%s\n' "@D@WINE_VERSION_OUTPUT"
        WINE_MAJOR="@D@(printf '%s' "@D@WINE_VERSION_OUTPUT" | sed -n 's/^[^0-9]*\([0-9][0-9]*\)\..*/\1/p')"
        test -n "@D@WINE_MAJOR" && test "@D@WINE_MAJOR" -ge 11 || { echo "Wine 11 or newer is required." >&2; exit 49; }
        mkdir -p /opt/vyrox
        {
            printf 'version=%s\n' "@D@WINE_VERSION_OUTPUT"
            printf 'major=%s\n' "@D@WINE_MAJOR"
            printf 'architecture=x86_64\n'
            printf 'wow64_mode=new\n'
            printf 'loader=%s\n' "@D@WINE"
            printf 'wineboot=%s\n' "@D@WINEBOOT"
            printf 'source=WineHQ-stable\n'
        } > '/opt/vyrox/wine-stable.properties'
        VULKAN_BACKEND=unavailable
        VULKANINFO_BIN="@D@(command -v vulkaninfo 2>/dev/null || true)"
        if [ -n "@D@VULKANINFO_BIN" ] && [ -f "@D@VULKANINFO_BIN" ]; then
            VULKANINFO_MACHINE="@D@(od -An -tu2 -j18 -N2 "@D@VULKANINFO_BIN" 2>/dev/null | tr -d ' ' || true)"
            if [ "@D@VULKANINFO_MACHINE" = "62" ]; then
                for icd in /usr/share/vulkan/icd.d/lvp_icd.x86_64.json /etc/vulkan/icd.d/lvp_icd.x86_64.json; do
                    if [ -f "@D@icd" ]; then export VK_ICD_FILENAMES="@D@icd"; break; fi
                done
                VULKAN_OUTPUT="@D@("@D@BOX64" "@D@VULKANINFO_BIN" --summary 2>&1 || true)"
                printf '%s\n' "@D@VULKAN_OUTPUT" | tail -n 24
                if printf '%s' "@D@VULKAN_OUTPUT" | grep -qiE 'lavapipe|llvmpipe'; then
                    VULKAN_BACKEND=lavapipe-software
                elif printf '%s' "@D@VULKAN_OUTPUT" | grep -qiE 'deviceName|GPU id'; then
                    VULKAN_BACKEND=vulkan-device
                fi
            fi
        fi
        printf 'vulkan_backend=%s\n' "@D@VULKAN_BACKEND" >> /opt/vyrox/wine-stable.properties
        echo "VYROX_VULKAN_BACKEND=@D@VULKAN_BACKEND"
        echo "VYROX_WINE_READY version=@D@WINE_VERSION_OUTPUT wow64=new architecture=x86_64"
    """.trimIndent()
        .replace("@D@", '$'.toString())
        .replace("@BOX64@", BOX64_BINARY)

    fun createPrefixShellScript(guestPrefixPath: String, rootFsDirectory: File): String {
        require(guestPrefixPath.matches(PREFIX_GUEST_PATH)) { "Invalid VYROX prefix path." }
        val wine = readInstallation(rootFsDirectory)
            ?: throw IllegalStateException("A verified Wine 11+ WoW64 installation is required.")
        return """
            set -eu
            BOX64='@BOX64@'
            WINE='@WINE@'
            WINEBOOT='@WINEBOOT@'
            test -x "@D@BOX64"
            test -x "@D@WINE"
            test -f "@D@WINEBOOT"
            export BOX64_LD_LIBRARY_PATH='/usr/lib/wine:/usr/lib/x86_64-linux-gnu/wine/x86_64-unix:/usr/lib/x86_64-linux-gnu:/lib/x86_64-linux-gnu'
            export WINEPREFIX='@PREFIX@'
            export WINEARCH=win64
            export WINEDEBUG=-all
            "@D@BOX64" "@D@WINE" "@D@WINEBOOT" -u
            "@D@BOX64" "@D@WINE" --version
            test -s "@D@WINEPREFIX/system.reg"
            test -s "@D@WINEPREFIX/user.reg"
            echo "VYROX_WINEBOOT_OK"
        """.trimIndent()
            .replace("@D@", '$'.toString())
            .replace("@BOX64@", BOX64_BINARY)
            .replace("@WINE@", wine.loaderGuestPath)
            .replace("@WINEBOOT@", wine.winebootGuestPath)
            .replace("@PREFIX@", guestPrefixPath)
    }

    fun createInteractiveRunScript(
        guestPrefixPath: String,
        guestTargetPath: String,
        kind: WindowsInstallerKind,
        rootFsDirectory: File
    ): String {
        require(guestPrefixPath.matches(PREFIX_GUEST_PATH)) { "Invalid VYROX prefix path." }
        require(isValidCapsuleGuestFile(guestTargetPath)) { "The program must be inside this Capsule's C: or D: drive." }
        val wine = readInstallation(rootFsDirectory)
            ?: throw IllegalStateException("A verified Wine 11+ WoW64 installation is required.")
        val launchCommand = when (kind) {
            WindowsInstallerKind.EXE -> "\"\${'$'}BOX64\" \"\${'$'}WINE\" \"\${'$'}TARGET\""
            WindowsInstallerKind.MSI -> "\"\${'$'}BOX64\" \"\${'$'}WINE\" msiexec /i \"\${'$'}TARGET\""
        }
        return """
            set -eu
            BOX64='@BOX64@'
            WINE='@WINE@'
            TARGET=@TARGET@
            export BOX64_LD_LIBRARY_PATH='/usr/lib/wine:/usr/lib/x86_64-linux-gnu/wine/x86_64-unix:/usr/lib/x86_64-linux-gnu:/lib/x86_64-linux-gnu'
            export WINEPREFIX='@PREFIX@'
            export WINEARCH=win64
            export WINEDEBUG=-all
            export WINEDLLOVERRIDES='mscoree,mshtml='
            export DISPLAY=:99
            export PULSE_ALLOW_RUN_AS_ROOT=1

            mkdir -p /tmp/.X11-unix /tmp/pulse-root
            Xvfb :99 -screen 0 1280x800x24 -nolisten tcp -ac +extension GLX +render -noreset >/tmp/vyrox-xvfb.log 2>&1 &
            XVFB_PID=@D@!
            VNC_PID=''
            FLUXBOX_PID=''
            AUDIO_PID=''
            cleanup() {
                [ -z "@D@AUDIO_PID" ] || kill "@D@AUDIO_PID" 2>/dev/null || true
                [ -z "@D@VNC_PID" ] || kill "@D@VNC_PID" 2>/dev/null || true
                [ -z "@D@FLUXBOX_PID" ] || kill "@D@FLUXBOX_PID" 2>/dev/null || true
                kill "@D@XVFB_PID" 2>/dev/null || true
                pulseaudio --kill >/dev/null 2>&1 || true
            }
            trap cleanup EXIT INT TERM

            for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
                xdpyinfo -display :99 >/dev/null 2>&1 && break
                sleep 1
            done
            xdpyinfo -display :99 >/dev/null 2>&1 || { echo "VYROX_X11_FAILED"; exit 60; }
            fluxbox >/tmp/vyrox-fluxbox.log 2>&1 &
            FLUXBOX_PID=@D@!
            x11vnc -display :99 -localhost -nopw -forever -shared -rfbport 5900 -noxdamage -quiet >/tmp/vyrox-vnc.log 2>&1 &
            VNC_PID=@D@!

            if pulseaudio --start --exit-idle-time=-1 --log-target=file:/tmp/vyrox-pulse.log >/dev/null 2>&1; then
                if pactl load-module module-null-sink sink_name=vyrox >/dev/null 2>&1; then
                    (parec --device=vyrox.monitor --raw --format=s16le --rate=48000 --channels=2 2>/dev/null | nc -q 1 127.0.0.1 4472 >/dev/null 2>&1) &
                    AUDIO_PID=@D@!
                    echo "VYROX_AUDIO_BRIDGE_STARTED"
                else
                    echo "VYROX_AUDIO_BRIDGE_UNAVAILABLE"
                fi
            else
                echo "VYROX_AUDIO_BRIDGE_UNAVAILABLE"
            fi
            echo "VYROX_WINDOWS_DESKTOP_READY display=:99 vnc=127.0.0.1:5900"
            @LAUNCH@
            RESULT=@D@?
            echo "VYROX_WINDOWS_PROCESS_EXIT=@D@RESULT"
            exit "@D@RESULT"
        """.trimIndent()
            .replace("@D@", '$'.toString())
            .replace("@BOX64@", BOX64_BINARY)
            .replace("@WINE@", wine.loaderGuestPath)
            .replace("@TARGET@", shellQuote(guestTargetPath))
            .replace("@PREFIX@", guestPrefixPath)
            .replace("@LAUNCH@", launchCommand)
    }

    /**
     * Installs the official DXVK (D3D9/10/11) and VKD3D-Proton (D3D12)
     * releases into one verified Capsule prefix using Wine through Box64.
     */
    fun createGraphicsTranslationInstallScript(
        guestPrefixPath: String,
        rootFsDirectory: File
    ): String {
        require(guestPrefixPath.matches(PREFIX_GUEST_PATH)) { "Invalid VYROX prefix path." }
        val wine = readInstallation(rootFsDirectory)
            ?: throw IllegalStateException("A verified Wine 11+ WoW64 installation is required.")
        require(wine.vulkanBackend != "unavailable") {
            "No working Vulkan ICD was detected inside the RootFS. DXVK and VKD3D-Proton cannot be enabled."
        }
        return """
            set -eu
            BOX64='@BOX64@'
            WINE_LOADER='@WINE@'
            WINEBOOT_EXE='@WINEBOOT@'
            PREFIX='@PREFIX@'
            DXVK_VERSION='@DXVK_VERSION@'
            VKD3D_VERSION='@VKD3D_VERSION@'
            VULKAN_BACKEND='@VULKAN_BACKEND@'

            test -x "@D@BOX64"
            test -x "@D@WINE_LOADER"
            test -f "@D@WINEBOOT_EXE"
            test -s "@D@PREFIX/system.reg"
            test -s "@D@PREFIX/user.reg"
            test "@D@VULKAN_BACKEND" != "unavailable"

            export BOX64_LD_LIBRARY_PATH='/usr/lib/wine:/usr/lib/x86_64-linux-gnu/wine/x86_64-unix:/usr/lib/x86_64-linux-gnu:/lib/x86_64-linux-gnu'
            export WINEPREFIX="@D@PREFIX"
            export WINEARCH=win64
            export WINEDEBUG=-all
            export VYROX_BOX64="@D@BOX64"
            export VYROX_WINE_LOADER="@D@WINE_LOADER"
            export VYROX_WINEBOOT_EXE="@D@WINEBOOT_EXE"

            WRAPPER_DIR='/tmp/vyrox-wine-wrappers'
            TMP_DIR='/tmp/vyrox-graphics-install'
            mkdir -p "@D@WRAPPER_DIR" "@D@TMP_DIR"
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

            WINE_SERVER=''
            WINE_SERVER_DIR="@D@(dirname "@D@WINE_LOADER")"
            for candidate in "@D@WINE_SERVER_DIR/wineserver" /usr/lib/wine/wineserver /usr/lib/x86_64-linux-gnu/wine/wineserver; do
                if [ -x "@D@candidate" ]; then WINE_SERVER="@D@candidate"; break; fi
            done
            if [ -n "@D@WINE_SERVER" ]; then
                export VYROX_WINE_SERVER="@D@WINE_SERVER"
                cat > "@D@WRAPPER_DIR/wineserver" <<'VYROX_WINESERVER'
            #!/bin/sh
            exec "@D@VYROX_BOX64" "@D@VYROX_WINE_SERVER" "@D@@"
            VYROX_WINESERVER
                chmod 0755 "@D@WRAPPER_DIR/wineserver"
            fi
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
            tar -tzf "@D@DXVK_ARCHIVE" | grep -q 'setup_dxvk.sh
        require(CAPSULE_ID_PATTERN.matches(capsuleId)) { "Invalid Capsule ID." }
        val cDrive = "$GUEST_CAPSULE_ROOT/$capsuleId/drive_c"
        return """
            set -eu
            CDRIVE='@CDRIVE@'
            ICONS="@D@CDRIVE/VYROX/Icons"
            mkdir -p "@D@ICONS"
            for root in "@D@CDRIVE/Program Files" "@D@CDRIVE/Program Files (x86)" "@D@CDRIVE/Games"; do
                [ -d "@D@root" ] || continue
                find "@D@root" -maxdepth 6 -type f -iname '*.exe' -print
            done | head -n 250 | while IFS= read -r executable; do
                [ -f "@D@executable" ] || continue
                wrestool -x --output="@D@ICONS" -t14 "@D@executable" >/dev/null 2>&1 || true
            done
            for icon in "@D@ICONS"/*.ico; do
                [ -f "@D@icon" ] || continue
                icotool -x -o "@D@ICONS" "@D@icon" >/dev/null 2>&1 || true
            done
            echo "VYROX_ICON_SCAN_COMPLETE"
        """.trimIndent()
            .replace("@D@", '$'.toString())
            .replace("@CDRIVE@", cDrive)
    }

    fun readInstallation(rootFsDirectory: File): WineInstallationInfo? = runCatching {
        if (Files.isSymbolicLink(rootFsDirectory.toPath()) || !rootFsDirectory.isDirectory) return null
        val root = rootFsDirectory.canonicalFile
        val metadataFile = File(root, METADATA_PATH)
        if (Files.isSymbolicLink(metadataFile.toPath()) || !metadataFile.isFile) return null
        val metadata = Properties()
        metadataFile.inputStream().buffered().use { metadata.load(it) }
        val major = metadata.getProperty("major")?.toIntOrNull() ?: return null
        val loaderPath = metadata.getProperty("loader") ?: return null
        val bootPath = metadata.getProperty("wineboot") ?: return null
        val loader = resolveGuestFile(root, loaderPath, true) ?: return null
        val wineboot = resolveGuestFile(root, bootPath, false) ?: return null
        if (
            metadata.getProperty("source") != "WineHQ-stable" ||
            metadata.getProperty("architecture") != "x86_64" ||
            metadata.getProperty("wow64_mode") != "new" ||
            major < WINE_VERSION_MINIMUM_MAJOR ||
            readElfMachine(loader) != X86_64_ELF_MACHINE ||
            !isWindowsPe(wineboot)
        ) return null
        WineInstallationInfo(
            version = metadata.getProperty("version") ?: return null,
            loaderGuestPath = toGuestPath(root, loader),
            winebootGuestPath = toGuestPath(root, wineboot),
            vulkanBackend = metadata.getProperty("vulkan_backend", "unavailable")
        )
    }.getOrNull()

    fun isInstalled(rootFsDirectory: File): Boolean =
        readInstallation(rootFsDirectory) != null && Box64BuildPlan.isInstalled(rootFsDirectory)

    private fun isValidCapsuleGuestFile(path: String): Boolean {
        if (!path.startsWith("$GUEST_CAPSULE_ROOT/") || path.contains('\u0000') || path.contains('\\')) return false
        val parts = path.split('/')
        if (parts.size < 7 || parts.any { it.isBlank() || it == "." || it == ".." }) return false
        return CAPSULE_ID_PATTERN.matches(parts[4]) && (parts[5] == "drive_c" || parts[5] == "drive_d")
    }

    private fun resolveGuestFile(root: File, guestPath: String, executable: Boolean): File? {
        if (!guestPath.startsWith("/") || guestPath.contains("..")) return null
        val file = File(root, guestPath.removePrefix("/"))
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile || (executable && !file.canExecute())) return null
        val canonical = file.canonicalFile
        return canonical.takeIf { it == root || it.path.startsWith(root.path + File.separator) }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun toGuestPath(root: File, file: File): String =
        "/" + root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')

    private fun isWindowsPe(file: File): Boolean = runCatching {
        FileInputStream(file).use { it.read() == 'M'.code && it.read() == 'Z'.code }
    }.getOrDefault(false)

    private fun readElfMachine(file: File): Int? = runCatching {
        val header = ByteArray(20)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) return null
                offset += count
            }
        }
        if (
            header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte() ||
            header[4] != 2.toByte() || header[5] != 1.toByte()
        ) return null
        (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
    }.getOrNull()

    private val PREFIX_GUEST_PATH = Regex("/opt/vyrox/prefixes/[0-9a-fA-F-]{36}")
    private val CAPSULE_ID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private const val X86_64_ELF_MACHINE = 62
}

            tar --zstd -tf "@D@VKD3D_ARCHIVE" | grep -q 'setup_vkd3d_proton.sh
        require(CAPSULE_ID_PATTERN.matches(capsuleId)) { "Invalid Capsule ID." }
        val cDrive = "$GUEST_CAPSULE_ROOT/$capsuleId/drive_c"
        return """
            set -eu
            CDRIVE='@CDRIVE@'
            ICONS="@D@CDRIVE/VYROX/Icons"
            mkdir -p "@D@ICONS"
            for root in "@D@CDRIVE/Program Files" "@D@CDRIVE/Program Files (x86)" "@D@CDRIVE/Games"; do
                [ -d "@D@root" ] || continue
                find "@D@root" -maxdepth 6 -type f -iname '*.exe' -print
            done | head -n 250 | while IFS= read -r executable; do
                [ -f "@D@executable" ] || continue
                wrestool -x --output="@D@ICONS" -t14 "@D@executable" >/dev/null 2>&1 || true
            done
            for icon in "@D@ICONS"/*.ico; do
                [ -f "@D@icon" ] || continue
                icotool -x -o "@D@ICONS" "@D@icon" >/dev/null 2>&1 || true
            done
            echo "VYROX_ICON_SCAN_COMPLETE"
        """.trimIndent()
            .replace("@D@", '$'.toString())
            .replace("@CDRIVE@", cDrive)
    }

    fun readInstallation(rootFsDirectory: File): WineInstallationInfo? = runCatching {
        if (Files.isSymbolicLink(rootFsDirectory.toPath()) || !rootFsDirectory.isDirectory) return null
        val root = rootFsDirectory.canonicalFile
        val metadataFile = File(root, METADATA_PATH)
        if (Files.isSymbolicLink(metadataFile.toPath()) || !metadataFile.isFile) return null
        val metadata = Properties()
        metadataFile.inputStream().buffered().use { metadata.load(it) }
        val major = metadata.getProperty("major")?.toIntOrNull() ?: return null
        val loaderPath = metadata.getProperty("loader") ?: return null
        val bootPath = metadata.getProperty("wineboot") ?: return null
        val loader = resolveGuestFile(root, loaderPath, true) ?: return null
        val wineboot = resolveGuestFile(root, bootPath, false) ?: return null
        if (
            metadata.getProperty("source") != "WineHQ-stable" ||
            metadata.getProperty("architecture") != "x86_64" ||
            metadata.getProperty("wow64_mode") != "new" ||
            major < WINE_VERSION_MINIMUM_MAJOR ||
            readElfMachine(loader) != X86_64_ELF_MACHINE ||
            !isWindowsPe(wineboot)
        ) return null
        WineInstallationInfo(
            version = metadata.getProperty("version") ?: return null,
            loaderGuestPath = toGuestPath(root, loader),
            winebootGuestPath = toGuestPath(root, wineboot)
        )
    }.getOrNull()

    fun isInstalled(rootFsDirectory: File): Boolean =
        readInstallation(rootFsDirectory) != null && Box64BuildPlan.isInstalled(rootFsDirectory)

    private fun isValidCapsuleGuestFile(path: String): Boolean {
        if (!path.startsWith("$GUEST_CAPSULE_ROOT/") || path.contains('\u0000') || path.contains('\\')) return false
        val parts = path.split('/')
        if (parts.size < 7 || parts.any { it.isBlank() || it == "." || it == ".." }) return false
        return CAPSULE_ID_PATTERN.matches(parts[4]) && (parts[5] == "drive_c" || parts[5] == "drive_d")
    }

    private fun resolveGuestFile(root: File, guestPath: String, executable: Boolean): File? {
        if (!guestPath.startsWith("/") || guestPath.contains("..")) return null
        val file = File(root, guestPath.removePrefix("/"))
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile || (executable && !file.canExecute())) return null
        val canonical = file.canonicalFile
        return canonical.takeIf { it == root || it.path.startsWith(root.path + File.separator) }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun toGuestPath(root: File, file: File): String =
        "/" + root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')

    private fun isWindowsPe(file: File): Boolean = runCatching {
        FileInputStream(file).use { it.read() == 'M'.code && it.read() == 'Z'.code }
    }.getOrDefault(false)

    private fun readElfMachine(file: File): Int? = runCatching {
        val header = ByteArray(20)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) return null
                offset += count
            }
        }
        if (
            header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte() ||
            header[4] != 2.toByte() || header[5] != 1.toByte()
        ) return null
        (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
    }.getOrNull()

    private val PREFIX_GUEST_PATH = Regex("/opt/vyrox/prefixes/[0-9a-fA-F-]{36}")
    private val CAPSULE_ID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private const val X86_64_ELF_MACHINE = 62
}


            DXVK_DIR="@D@TMP_DIR/dxvk"
            VKD3D_DIR="@D@TMP_DIR/vkd3d"
            mkdir -p "@D@DXVK_DIR" "@D@VKD3D_DIR"
            tar -xzf "@D@DXVK_ARCHIVE" -C "@D@DXVK_DIR" --strip-components=1
            tar --zstd -xf "@D@VKD3D_ARCHIVE" -C "@D@VKD3D_DIR" --strip-components=1
            test -x "@D@DXVK_DIR/setup_dxvk.sh"
            test -x "@D@VKD3D_DIR/setup_vkd3d_proton.sh"

            "@D@DXVK_DIR/setup_dxvk.sh" install --with-d3d10
            "@D@VKD3D_DIR/setup_vkd3d_proton.sh" install

            WINDOWS_DIR="@D@(find "@D@PREFIX/drive_c" -mindepth 1 -maxdepth 1 -type d -iname windows -print -quit)"
            test -n "@D@WINDOWS_DIR"
            SYSTEM32="@D@WINDOWS_DIR/system32"
            SYSWOW64="@D@WINDOWS_DIR/syswow64"
            test -d "@D@SYSTEM32"
            test -d "@D@SYSWOW64"
            for dll in d3d9.dll d3d10core.dll d3d11.dll dxgi.dll; do
                test -s "@D@SYSTEM32/@D@dll" || { echo "DXVK missing 64-bit @D@dll." >&2; exit 71; }
                test -s "@D@SYSWOW64/@D@dll" || { echo "DXVK missing 32-bit @D@dll." >&2; exit 72; }
            done
            for dll in d3d12.dll d3d12core.dll; do
                test -s "@D@SYSTEM32/@D@dll" || { echo "VKD3D-Proton missing @D@dll." >&2; exit 73; }
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
            } > "@D@PREFIX/vyrox-graphics.properties"
            rm -rf "@D@TMP_DIR" "@D@DXVK_ARCHIVE" "@D@VKD3D_ARCHIVE"
            echo "VYROX_GRAPHICS_TRANSLATION_READY dxvk=@D@DXVK_VERSION vkd3d=@D@VKD3D_VERSION vulkan=@D@VULKAN_BACKEND"
        """.trimIndent()
            .replace("@D@", '
        require(CAPSULE_ID_PATTERN.matches(capsuleId)) { "Invalid Capsule ID." }
        val cDrive = "$GUEST_CAPSULE_ROOT/$capsuleId/drive_c"
        return """
            set -eu
            CDRIVE='@CDRIVE@'
            ICONS="@D@CDRIVE/VYROX/Icons"
            mkdir -p "@D@ICONS"
            for root in "@D@CDRIVE/Program Files" "@D@CDRIVE/Program Files (x86)" "@D@CDRIVE/Games"; do
                [ -d "@D@root" ] || continue
                find "@D@root" -maxdepth 6 -type f -iname '*.exe' -print
            done | head -n 250 | while IFS= read -r executable; do
                [ -f "@D@executable" ] || continue
                wrestool -x --output="@D@ICONS" -t14 "@D@executable" >/dev/null 2>&1 || true
            done
            for icon in "@D@ICONS"/*.ico; do
                [ -f "@D@icon" ] || continue
                icotool -x -o "@D@ICONS" "@D@icon" >/dev/null 2>&1 || true
            done
            echo "VYROX_ICON_SCAN_COMPLETE"
        """.trimIndent()
            .replace("@D@", '$'.toString())
            .replace("@CDRIVE@", cDrive)
    }

    fun readInstallation(rootFsDirectory: File): WineInstallationInfo? = runCatching {
        if (Files.isSymbolicLink(rootFsDirectory.toPath()) || !rootFsDirectory.isDirectory) return null
        val root = rootFsDirectory.canonicalFile
        val metadataFile = File(root, METADATA_PATH)
        if (Files.isSymbolicLink(metadataFile.toPath()) || !metadataFile.isFile) return null
        val metadata = Properties()
        metadataFile.inputStream().buffered().use { metadata.load(it) }
        val major = metadata.getProperty("major")?.toIntOrNull() ?: return null
        val loaderPath = metadata.getProperty("loader") ?: return null
        val bootPath = metadata.getProperty("wineboot") ?: return null
        val loader = resolveGuestFile(root, loaderPath, true) ?: return null
        val wineboot = resolveGuestFile(root, bootPath, false) ?: return null
        if (
            metadata.getProperty("source") != "WineHQ-stable" ||
            metadata.getProperty("architecture") != "x86_64" ||
            metadata.getProperty("wow64_mode") != "new" ||
            major < WINE_VERSION_MINIMUM_MAJOR ||
            readElfMachine(loader) != X86_64_ELF_MACHINE ||
            !isWindowsPe(wineboot)
        ) return null
        WineInstallationInfo(
            version = metadata.getProperty("version") ?: return null,
            loaderGuestPath = toGuestPath(root, loader),
            winebootGuestPath = toGuestPath(root, wineboot)
        )
    }.getOrNull()

    fun isInstalled(rootFsDirectory: File): Boolean =
        readInstallation(rootFsDirectory) != null && Box64BuildPlan.isInstalled(rootFsDirectory)

    private fun isValidCapsuleGuestFile(path: String): Boolean {
        if (!path.startsWith("$GUEST_CAPSULE_ROOT/") || path.contains('\u0000') || path.contains('\\')) return false
        val parts = path.split('/')
        if (parts.size < 7 || parts.any { it.isBlank() || it == "." || it == ".." }) return false
        return CAPSULE_ID_PATTERN.matches(parts[4]) && (parts[5] == "drive_c" || parts[5] == "drive_d")
    }

    private fun resolveGuestFile(root: File, guestPath: String, executable: Boolean): File? {
        if (!guestPath.startsWith("/") || guestPath.contains("..")) return null
        val file = File(root, guestPath.removePrefix("/"))
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile || (executable && !file.canExecute())) return null
        val canonical = file.canonicalFile
        return canonical.takeIf { it == root || it.path.startsWith(root.path + File.separator) }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun toGuestPath(root: File, file: File): String =
        "/" + root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')

    private fun isWindowsPe(file: File): Boolean = runCatching {
        FileInputStream(file).use { it.read() == 'M'.code && it.read() == 'Z'.code }
    }.getOrDefault(false)

    private fun readElfMachine(file: File): Int? = runCatching {
        val header = ByteArray(20)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) return null
                offset += count
            }
        }
        if (
            header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte() ||
            header[4] != 2.toByte() || header[5] != 1.toByte()
        ) return null
        (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
    }.getOrNull()

    private val PREFIX_GUEST_PATH = Regex("/opt/vyrox/prefixes/[0-9a-fA-F-]{36}")
    private val CAPSULE_ID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private const val X86_64_ELF_MACHINE = 62
}
.toString())
            .replace("@BOX64@", BOX64_BINARY)
            .replace("@WINE@", wine.loaderGuestPath)
            .replace("@WINEBOOT@", wine.winebootGuestPath)
            .replace("@PREFIX@", guestPrefixPath)
            .replace("@DXVK_VERSION@", DXVK_VERSION)
            .replace("@VKD3D_VERSION@", VKD3D_PROTON_VERSION)
            .replace("@VULKAN_BACKEND@", wine.vulkanBackend)
    }

    fun readGraphicsTranslation(
        prefixDirectory: File,
        capsuleDriveC: File
    ): GraphicsTranslationInfo? = runCatching {
        if (
            Files.isSymbolicLink(prefixDirectory.toPath()) ||
            !prefixDirectory.isDirectory ||
            Files.isSymbolicLink(capsuleDriveC.toPath()) ||
            !capsuleDriveC.isDirectory
        ) return null
        val metadataFile = File(prefixDirectory, "vyrox-graphics.properties")
        if (Files.isSymbolicLink(metadataFile.toPath()) || !metadataFile.isFile) return null
        val metadata = Properties()
        metadataFile.inputStream().buffered().use { metadata.load(it) }
        val dxvkVersion = metadata.getProperty("dxvk_version") ?: return null
        val vkd3dVersion = metadata.getProperty("vkd3d_proton_version") ?: return null
        val backend = metadata.getProperty("vulkan_backend") ?: return null
        if (
            dxvkVersion != DXVK_VERSION ||
            vkd3dVersion != VKD3D_PROTON_VERSION ||
            backend == "unavailable"
        ) return null
        val windows = capsuleDriveC.listFiles()
            ?.firstOrNull { it.isDirectory && it.name.equals("windows", ignoreCase = true) }
            ?: return null
        val system32 = File(windows, "system32")
        val syswow64 = File(windows, "syswow64")
        val requiredDxvk = listOf("d3d9.dll", "d3d10core.dll", "d3d11.dll", "dxgi.dll")
        if (
            requiredDxvk.any { !File(system32, it).isFile || Files.isSymbolicLink(File(system32, it).toPath()) } ||
            requiredDxvk.any { !File(syswow64, it).isFile || Files.isSymbolicLink(File(syswow64, it).toPath()) } ||
            listOf("d3d12.dll", "d3d12core.dll").any {
                !File(system32, it).isFile || Files.isSymbolicLink(File(system32, it).toPath())
            }
        ) return null
        GraphicsTranslationInfo(dxvkVersion, vkd3dVersion, backend)
    }.getOrNull()

    fun isGraphicsTranslationInstalled(prefixDirectory: File, capsuleDriveC: File): Boolean =
        readGraphicsTranslation(prefixDirectory, capsuleDriveC) != null

    fun createIconExtractionScript(capsuleId: String): String {
        require(CAPSULE_ID_PATTERN.matches(capsuleId)) { "Invalid Capsule ID." }
        val cDrive = "$GUEST_CAPSULE_ROOT/$capsuleId/drive_c"
        return """
            set -eu
            CDRIVE='@CDRIVE@'
            ICONS="@D@CDRIVE/VYROX/Icons"
            mkdir -p "@D@ICONS"
            for root in "@D@CDRIVE/Program Files" "@D@CDRIVE/Program Files (x86)" "@D@CDRIVE/Games"; do
                [ -d "@D@root" ] || continue
                find "@D@root" -maxdepth 6 -type f -iname '*.exe' -print
            done | head -n 250 | while IFS= read -r executable; do
                [ -f "@D@executable" ] || continue
                wrestool -x --output="@D@ICONS" -t14 "@D@executable" >/dev/null 2>&1 || true
            done
            for icon in "@D@ICONS"/*.ico; do
                [ -f "@D@icon" ] || continue
                icotool -x -o "@D@ICONS" "@D@icon" >/dev/null 2>&1 || true
            done
            echo "VYROX_ICON_SCAN_COMPLETE"
        """.trimIndent()
            .replace("@D@", '$'.toString())
            .replace("@CDRIVE@", cDrive)
    }

    fun readInstallation(rootFsDirectory: File): WineInstallationInfo? = runCatching {
        if (Files.isSymbolicLink(rootFsDirectory.toPath()) || !rootFsDirectory.isDirectory) return null
        val root = rootFsDirectory.canonicalFile
        val metadataFile = File(root, METADATA_PATH)
        if (Files.isSymbolicLink(metadataFile.toPath()) || !metadataFile.isFile) return null
        val metadata = Properties()
        metadataFile.inputStream().buffered().use { metadata.load(it) }
        val major = metadata.getProperty("major")?.toIntOrNull() ?: return null
        val loaderPath = metadata.getProperty("loader") ?: return null
        val bootPath = metadata.getProperty("wineboot") ?: return null
        val loader = resolveGuestFile(root, loaderPath, true) ?: return null
        val wineboot = resolveGuestFile(root, bootPath, false) ?: return null
        if (
            metadata.getProperty("source") != "WineHQ-stable" ||
            metadata.getProperty("architecture") != "x86_64" ||
            metadata.getProperty("wow64_mode") != "new" ||
            major < WINE_VERSION_MINIMUM_MAJOR ||
            readElfMachine(loader) != X86_64_ELF_MACHINE ||
            !isWindowsPe(wineboot)
        ) return null
        WineInstallationInfo(
            version = metadata.getProperty("version") ?: return null,
            loaderGuestPath = toGuestPath(root, loader),
            winebootGuestPath = toGuestPath(root, wineboot)
        )
    }.getOrNull()

    fun isInstalled(rootFsDirectory: File): Boolean =
        readInstallation(rootFsDirectory) != null && Box64BuildPlan.isInstalled(rootFsDirectory)

    private fun isValidCapsuleGuestFile(path: String): Boolean {
        if (!path.startsWith("$GUEST_CAPSULE_ROOT/") || path.contains('\u0000') || path.contains('\\')) return false
        val parts = path.split('/')
        if (parts.size < 7 || parts.any { it.isBlank() || it == "." || it == ".." }) return false
        return CAPSULE_ID_PATTERN.matches(parts[4]) && (parts[5] == "drive_c" || parts[5] == "drive_d")
    }

    private fun resolveGuestFile(root: File, guestPath: String, executable: Boolean): File? {
        if (!guestPath.startsWith("/") || guestPath.contains("..")) return null
        val file = File(root, guestPath.removePrefix("/"))
        if (Files.isSymbolicLink(file.toPath()) || !file.isFile || (executable && !file.canExecute())) return null
        val canonical = file.canonicalFile
        return canonical.takeIf { it == root || it.path.startsWith(root.path + File.separator) }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun toGuestPath(root: File, file: File): String =
        "/" + root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')

    private fun isWindowsPe(file: File): Boolean = runCatching {
        FileInputStream(file).use { it.read() == 'M'.code && it.read() == 'Z'.code }
    }.getOrDefault(false)

    private fun readElfMachine(file: File): Int? = runCatching {
        val header = ByteArray(20)
        FileInputStream(file).use { input ->
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) return null
                offset += count
            }
        }
        if (
            header[0] != 0x7F.toByte() || header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte() ||
            header[4] != 2.toByte() || header[5] != 1.toByte()
        ) return null
        (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
    }.getOrNull()

    private val PREFIX_GUEST_PATH = Regex("/opt/vyrox/prefixes/[0-9a-fA-F-]{36}")
    private val CAPSULE_ID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private const val X86_64_ELF_MACHINE = 62
}

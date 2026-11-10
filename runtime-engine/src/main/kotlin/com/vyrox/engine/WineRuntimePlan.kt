package com.vyrox.engine

import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.Properties

/**
 * Installs WineHQ stable x86-64 WoW64 into an ARM64 Debian/Ubuntu RootFS.
 * The x86-64 Wine loader is executed by VYROX's pinned ARM64 Box64 binary.
 */
object WineRuntimePlan {
    const val WINE_VERSION_MINIMUM_MAJOR = 11
    const val WINEHQ_KEY_URL = "https://dl.winehq.org/wine-builds/winehq.key"
    const val WINE_BINARY = "/opt/wine-stable/bin/wine"
    const val WINEBOOT_BINARY = "/opt/wine-stable/bin/wineboot"
    const val METADATA_PATH = "opt/vyrox/wine-stable.properties"
    const val BOX64_BINARY = "/usr/local/bin/box64"

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
            debian:bookworm|debian:trixie|ubuntu:jammy|ubuntu:noble)
                ;;
            *)
                echo "Unsupported WineHQ RootFS: @D@OS_ID @D@OS_CODENAME. Use Debian 12/13 or Ubuntu 22.04/24.04 ARM64." >&2
                exit 41
                ;;
        esac

        command -v dpkg >/dev/null 2>&1 || {
            echo "The selected RootFS is not a Debian/Ubuntu APT system." >&2
            exit 42
        }
        command -v apt-get >/dev/null 2>&1 || {
            echo "APT is not available in the selected RootFS." >&2
            exit 43
        }

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

        BOX64='/usr/local/bin/box64'
        WINE='/opt/wine-stable/bin/wine'
        WINEBOOT='/opt/wine-stable/bin/wineboot'
        test -x "@D@BOX64" || {
            echo "Build Box64 before installing Wine." >&2
            exit 44
        }
        test -x "@D@WINE" || {
            echo "WineHQ stable installed without the expected Wine loader at @D@WINE." >&2
            exit 45
        }
        test -x "@D@WINEBOOT" || {
            echo "WineHQ stable installed without wineboot at @D@WINEBOOT." >&2
            exit 46
        }

        WINE_MACHINE="@D@(od -An -tu2 -j18 -N2 "@D@WINE" | tr -d ' ')"
        WINEBOOT_MACHINE="@D@(od -An -tu2 -j18 -N2 "@D@WINEBOOT" | tr -d ' ')"
        test "@D@WINE_MACHINE" = "62" || {
            echo "Wine loader is not an x86-64 Linux ELF executable." >&2
            exit 47
        }
        test "@D@WINEBOOT_MACHINE" = "62" || {
            echo "wineboot is not an x86-64 Linux ELF executable." >&2
            exit 48
        }

        export BOX64_LD_LIBRARY_PATH='/opt/wine-stable/lib:/opt/wine-stable/lib/wine/x86_64-unix:/usr/lib/x86_64-linux-gnu:/lib/x86_64-linux-gnu'
        WINE_VERSION_OUTPUT="@D@("@D@BOX64" "@D@WINE" --version 2>&1)"
        printf '%s\n' "@D@WINE_VERSION_OUTPUT"
        WINE_MAJOR="@D@(printf '%s' "@D@WINE_VERSION_OUTPUT" | sed -n 's/^[^0-9]*\([0-9][0-9]*\)\..*/\1/p')"
        test -n "@D@WINE_MAJOR" && test "@D@WINE_MAJOR" -ge 11 || {
            echo "Wine 11 or newer is required for the new WoW64 mode." >&2
            exit 49
        }

        mkdir -p /opt/vyrox
        {
            printf 'version=%s\n' "@D@WINE_VERSION_OUTPUT"
            printf 'major=%s\n' "@D@WINE_MAJOR"
            printf 'architecture=x86_64\n'
            printf 'wow64_mode=new\n'
            printf 'loader=%s\n' "@D@WINE"
            printf 'wineboot=%s\n' "@D@WINEBOOT"
            printf 'source=WineHQ-stable\n'
            printf 'verified_at_epoch_seconds=%s\n' "@D@(date +%s)"
        } > '/opt/vyrox/wine-stable.properties'
        echo "VYROX_WINE_READY version=@D@WINE_VERSION_OUTPUT wow64=new architecture=x86_64"
    """.trimIndent().replace("@D@", '$'.toString())

    fun createPrefixShellScript(guestPrefixPath: String): String {
        require(guestPrefixPath.matches(PREFIX_GUEST_PATH)) {
            "Wine prefix path is not a VYROX staging path."
        }
        return """
            set -eu
            BOX64='/usr/local/bin/box64'
            WINE='/opt/wine-stable/bin/wine'
            WINEBOOT='/opt/wine-stable/bin/wineboot'
            test -x "@D@BOX64"
            test -x "@D@WINE"
            test -x "@D@WINEBOOT"
            export BOX64_LD_LIBRARY_PATH='/opt/wine-stable/lib:/opt/wine-stable/lib/wine/x86_64-unix:/usr/lib/x86_64-linux-gnu:/lib/x86_64-linux-gnu'
            export WINEPREFIX='@PREFIX@'
            export WINEARCH=win64
            export WINEDEBUG=-all
            "@D@BOX64" "@D@WINEBOOT" -u
            "@D@BOX64" "@D@WINE" --version
            test -s "@D@WINEPREFIX/system.reg"
            test -s "@D@WINEPREFIX/user.reg"
            echo "VYROX_WINEBOOT_OK"
        """.trimIndent().replace("@D@", '$'.toString()).replace("@PREFIX@", guestPrefixPath)
    }

    fun isInstalled(rootFsDirectory: File): Boolean = runCatching {
        if (Files.isSymbolicLink(rootFsDirectory.toPath())) return false
        val root = rootFsDirectory.canonicalFile
        val metadataFile = File(root, METADATA_PATH)
        val wine = File(root, WINE_BINARY.removePrefix("/"))
        val wineboot = File(root, WINEBOOT_BINARY.removePrefix("/"))
        if (
            !root.isDirectory ||
            Files.isSymbolicLink(metadataFile.toPath()) ||
            !metadataFile.isFile ||
            Files.isSymbolicLink(wine.toPath()) ||
            !wine.isFile || !wine.canExecute() ||
            Files.isSymbolicLink(wineboot.toPath()) ||
            !wineboot.isFile || !wineboot.canExecute() ||
            !Box64BuildPlan.isInstalled(root)
        ) return false

        val metadata = Properties()
        metadataFile.inputStream().buffered().use { metadata.load(it) }
        val major = metadata.getProperty("major")?.toIntOrNull() ?: return false
        metadata.getProperty("source") == "WineHQ-stable" &&
            metadata.getProperty("architecture") == "x86_64" &&
            metadata.getProperty("wow64_mode") == "new" &&
            major >= WINE_VERSION_MINIMUM_MAJOR &&
            readElfMachine(wine) == X86_64_ELF_MACHINE &&
            readElfMachine(wineboot) == X86_64_ELF_MACHINE
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
            header[0] != 0x7F.toByte() ||
            header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() ||
            header[3] != 'F'.code.toByte() ||
            header[4] != 2.toByte() ||
            header[5] != 1.toByte()
        ) return null
        (header[18].toInt() and 0xFF) or ((header[19].toInt() and 0xFF) shl 8)
    }.getOrNull()

    private val PREFIX_GUEST_PATH = Regex(
        "/opt/vyrox/prefixes/\\.staging-[0-9a-fA-F-]{36}-[0-9a-fA-F-]{36}"
    )
    private const val X86_64_ELF_MACHINE = 62
}

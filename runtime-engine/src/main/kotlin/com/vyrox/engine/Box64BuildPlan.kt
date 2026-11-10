package com.vyrox.engine

import java.io.File
import java.nio.file.Files
import java.util.Properties

/**
 * Pinned Box64 source build for an ARM64 glibc Linux RootFS.
 *
 * This produces a Linux AArch64 executable inside the selected RootFS. It is
 * not an Android/Bionic library and does not execute Windows PE files.
 */
object Box64BuildPlan {
    const val VERSION = "0.4.4"
    const val SOURCE_COMMIT = "2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a"
    const val SOURCE_URL = "https://github.com/ptitSeb/box64.git"
    const val BINARY_RELATIVE_PATH = "usr/local/bin/box64"
    const val METADATA_RELATIVE_PATH = "usr/local/share/vyrox/box64.properties"

    fun createShellScript(): String = """
        set -eu

        BOX64_VERSION='0.4.4'
        BOX64_COMMIT='2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a'
        BOX64_SOURCE_URL='https://github.com/ptitSeb/box64.git'
        BOX64_ROOT='/opt/vyrox/sources/box64-0.4.4'
        BOX64_BUILD='/opt/vyrox/build/box64-0.4.4'
        BOX64_BINARY='/usr/local/bin/box64'
        BOX64_METADATA='/usr/local/share/vyrox/box64.properties'

        printf '%s\n' 'VYROX: building Box64 0.4.4 for ARM64 glibc'
        test -x /bin/sh
        # Keep build tools' temporary files inside the guest RootFS.
        [ ! -L /tmp ] || {
            echo 'Guest /tmp is a symbolic link; refusing to build through it.' >&2
            exit 19
        }
        mkdir -p /tmp
        chmod 1777 /tmp
        export TMPDIR=/tmp
        command -v apt-get >/dev/null 2>&1 || {
            echo 'This build action currently supports APT-based Debian or Ubuntu ARM64 RootFS images.' >&2
            exit 20
        }
        command -v git >/dev/null 2>&1 || true

        for managed_path in /opt/vyrox /opt/vyrox/sources /opt/vyrox/build; do
            if [ -L "@D@managed_path" ]; then
                echo "Refusing a symbolic link in the Box64 build path: @D@managed_path" >&2
                exit 21
            fi
        done

        export DEBIAN_FRONTEND=noninteractive
        apt-get update
        apt-get install -y --no-install-recommends \
            build-essential cmake git pkg-config python3 binutils-x86-64-linux-gnu \
            libx11-dev libgl-dev libvulkan-dev libepoxy-dev \
            libasound2-dev libpulse-dev libpcap-dev

        mkdir -p /opt/vyrox/sources /opt/vyrox/build
        if [ -e "@D@BOX64_ROOT" ]; then
            [ -d "@D@BOX64_ROOT" ] && [ ! -L "@D@BOX64_ROOT" ] || {
                echo 'The Box64 source path is not a regular directory; refusing to overwrite it.' >&2
                exit 22
            }
            rm -rf "@D@BOX64_ROOT"
        fi

        # Fetch the exact reviewed commit. The source directory is throw-away,
        # and hooks are disabled so untrusted guest git configuration is not run.
        git init --template=/dev/null "@D@BOX64_ROOT"
        git -C "@D@BOX64_ROOT" -c core.hooksPath=/dev/null remote add origin "@D@BOX64_SOURCE_URL"
        git -C "@D@BOX64_ROOT" -c core.hooksPath=/dev/null fetch --depth 1 origin "@D@BOX64_COMMIT"
        git -C "@D@BOX64_ROOT" -c core.hooksPath=/dev/null checkout --detach FETCH_HEAD

        ACTUAL_COMMIT="$(git -C "@D@BOX64_ROOT" rev-parse HEAD)"
        test "@D@ACTUAL_COMMIT" = "@D@BOX64_COMMIT" || {
            echo "Box64 source pin mismatch: expected @D@BOX64_COMMIT, got @D@ACTUAL_COMMIT" >&2
            exit 23
        }

        if [ -e "@D@BOX64_BUILD" ]; then
            [ -d "@D@BOX64_BUILD" ] && [ ! -L "@D@BOX64_BUILD" ] || {
                echo 'The Box64 build path is not a regular directory.' >&2
                exit 24
            }
            rm -rf "@D@BOX64_BUILD"
        fi

        cmake -S "@D@BOX64_ROOT" -B "@D@BOX64_BUILD" \
            -DCMAKE_BUILD_TYPE=Release \
            -DCMAKE_C_COMPILER=gcc \
            -DCMAKE_INSTALL_PREFIX=/usr/local \
            -DARM64=1 \
            -DARM_DYNAREC=ON \
            -DBAD_SIGNAL=ON
        cmake --build "@D@BOX64_BUILD" --parallel 2
        cmake --install "@D@BOX64_BUILD"

        test -x "@D@BOX64_BINARY" || {
            echo 'Box64 build completed but /usr/local/bin/box64 is missing or not executable.' >&2
            exit 25
        }
        VERSION_OUTPUT="$("@D@BOX64_BINARY" --version 2>&1 || true)"
        printf '%s\n' "@D@VERSION_OUTPUT"
        printf '%s\n' "@D@VERSION_OUTPUT" | grep -F "@D@BOX64_VERSION" >/dev/null || {
            echo 'The installed Box64 binary did not report the pinned version.' >&2
            exit 26
        }

        # Build a tiny x86-64 Linux ELF using only syscalls. This avoids
        # depending on an x86-64 glibc loader before that runtime is installed.
        SMOKE_SOURCE='/tmp/vyrox-box64-smoke.S'
        SMOKE_OBJECT='/tmp/vyrox-box64-smoke.o'
        SMOKE_BINARY='/tmp/vyrox-box64-smoke'
        cat > "@D@SMOKE_SOURCE" <<'VYROX_ASM'
        .global _start
        .section .text
        _start:
            mov $1, %rax
            mov $1, %rdi
            lea message(%rip), %rsi
            mov ${'$'}message_length, %rdx
            syscall
            mov $60, %rax
            xor %rdi, %rdi
            syscall
        .section .rodata
        message:
            .ascii "VYROX_BOX64_X86_64_SMOKE_OK\n"
        message_length = . - message
        VYROX_ASM

        x86_64-linux-gnu-as --64 -o "@D@SMOKE_OBJECT" "@D@SMOKE_SOURCE"
        x86_64-linux-gnu-ld -static -o "@D@SMOKE_BINARY" "@D@SMOKE_OBJECT"
        readelf -h "@D@SMOKE_BINARY" | grep -Eq 'Class:[[:space:]]+ELF64' || {
            echo 'The Box64 smoke executable is not ELF64.' >&2
            exit 27
        }
        readelf -h "@D@SMOKE_BINARY" | grep -Eq 'Machine:[[:space:]]+Advanced Micro Devices X86-64' || {
            echo 'The Box64 smoke executable is not x86-64.' >&2
            exit 28
        }
        "@D@BOX64_BINARY" "@D@SMOKE_BINARY" > /tmp/vyrox-box64-smoke.log 2>&1 || {
            cat /tmp/vyrox-box64-smoke.log >&2
            echo 'Box64 could not execute the x86-64 Linux syscall smoke program.' >&2
            exit 29
        }
        cat /tmp/vyrox-box64-smoke.log
        grep -F 'VYROX_BOX64_X86_64_SMOKE_OK' /tmp/vyrox-box64-smoke.log >/dev/null || {
            echo 'Box64 output did not contain the x86-64 smoke marker.' >&2
            exit 30
        }
        rm -f "@D@SMOKE_SOURCE" "@D@SMOKE_OBJECT" "@D@SMOKE_BINARY" /tmp/vyrox-box64-smoke.log

        mkdir -p /usr/local/share/vyrox
        printf 'version=%s\nsource_commit=%s\nsource_url=%s\nverification=version_aarch64_elf_x86_64_smoke\nx86_64_smoke_test=passed\ninstalled_at_epoch_seconds=%s\n' \
            "@D@BOX64_VERSION" "@D@BOX64_COMMIT" "@D@BOX64_SOURCE_URL" "$(date +%s)" \
            > "@D@BOX64_METADATA"

        echo "VYROX_BOX64_READY version=@D@BOX64_VERSION source_commit=@D@BOX64_COMMIT x86_64_smoke_test=passed"
    """.trimIndent().replace("@D@", '$'.toString())

    /**
     * A package is visible as installed only when its metadata matches the
     * pinned source, its executable is a regular AArch64 Linux ELF, and the
     * build script's x86-64 syscall smoke test passed. This does not validate
     * Wine or Windows PE execution.
     */
    fun isInstalled(rootFsDirectory: File): Boolean = runCatching {
        if (Files.isSymbolicLink(rootFsDirectory.toPath())) return false
        val root = rootFsDirectory.canonicalFile
        if (!root.isDirectory) return false

        val protectedPaths = listOf(
            "usr",
            "usr/local",
            "usr/local/bin",
            "usr/local/share",
            "usr/local/share/vyrox",
            BINARY_RELATIVE_PATH,
            METADATA_RELATIVE_PATH
        )
        if (protectedPaths.any { Files.isSymbolicLink(File(root, it).toPath()) }) return false

        val binary = File(root, BINARY_RELATIVE_PATH).canonicalFile
        val metadataFile = File(root, METADATA_RELATIVE_PATH).canonicalFile
        if (
            binary.parentFile != File(root, "usr/local/bin").canonicalFile ||
            metadataFile.parentFile != File(root, "usr/local/share/vyrox").canonicalFile ||
            !binary.isFile ||
            !binary.canExecute() ||
            !metadataFile.isFile
        ) return false

        val metadata = Properties()
        metadataFile.inputStream().buffered().use { metadata.load(it) }
        metadata.getProperty("version") == VERSION &&
            metadata.getProperty("source_commit") == SOURCE_COMMIT &&
            metadata.getProperty("verification") == "version_aarch64_elf_x86_64_smoke" &&
            metadata.getProperty("x86_64_smoke_test") == "passed" &&
            Aarch64Elf.isPieExecutable(binary)
    }.getOrDefault(false)
}

private object Aarch64Elf {
    fun isPieExecutable(file: File): Boolean {
        if (file.length() < 64L) return false
        val header = ByteArray(20)
        file.inputStream().use { input ->
            var offset = 0
            while (offset < header.size) {
                val count = input.read(header, offset, header.size - offset)
                if (count < 0) return false
                offset += count
            }
        }
        if (
            header[0] != 0x7F.toByte() ||
            header[1] != 'E'.code.toByte() ||
            header[2] != 'L'.code.toByte() ||
            header[3] != 'F'.code.toByte() ||
            (header[4].toInt() and 0xFF) != 2 ||
            (header[5].toInt() and 0xFF) != 1 ||
            (header[6].toInt() and 0xFF) != 1
        ) return false
        val machine = (header[18].toInt() and 0xFF) or
            ((header[19].toInt() and 0xFF) shl 8)
        val type = (header[16].toInt() and 0xFF) or
            ((header[17].toInt() and 0xFF) shl 8)
        return machine == 183 && type in setOf(2, 3)
    }
}

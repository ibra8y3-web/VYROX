#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK_VERSION="27.2.12479018"
API_LEVEL="26"
PROOT_VERSION="5.1.107.102"
TALLOC_VERSION="2.4.2"
SHMEM_VERSION="0.7"

PROOT_SHA256="30d12a6e2cc4147123756ff58edea262d3083bbc238c12d0ab9b39e3d1c40852"
TALLOC_SHA256="85ecf9e465e20f98f9950a52e9a411e14320bc555fa257d87697b7e7a9b1d8a6"
SHMEM_SHA256="1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867"

SDK_ROOT="$(printenv ANDROID_SDK_ROOT || true)"
if [[ -z "$SDK_ROOT" ]]; then
  SDK_ROOT="$(printenv ANDROID_HOME || true)"
fi
if [[ -z "$SDK_ROOT" ]]; then
  echo "ANDROID_SDK_ROOT or ANDROID_HOME must point to an installed Android SDK." >&2
  exit 2
fi

# Always use the version pinned by this repository. GitHub-hosted runners
# may export ANDROID_NDK_HOME for a different preinstalled NDK release.
NDK_ROOT="$SDK_ROOT/ndk/$NDK_VERSION"
NDK_OVERRIDE="$(printenv ANDROID_NDK_HOME || true)"
if [[ -n "$NDK_OVERRIDE" && -f "$NDK_OVERRIDE/source.properties" ]]; then
  NDK_OVERRIDE_VERSION="$(sed -n 's/^Pkg.Revision=//p' "$NDK_OVERRIDE/source.properties" | head -n 1)"
  if [[ "$NDK_OVERRIDE_VERSION" == "$NDK_VERSION" ]]; then
    NDK_ROOT="$NDK_OVERRIDE"
  else
    echo "Ignoring auto-selected Android NDK $NDK_OVERRIDE_VERSION; using pinned $NDK_VERSION."
  fi
fi
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
if [[ ! -x "$TOOLCHAIN/bin/aarch64-linux-android$API_LEVEL-clang" ]]; then
  echo "Android NDK $NDK_VERSION with the Linux x86_64 host toolchain was not found at $NDK_ROOT." >&2
  exit 2
fi

CC="$TOOLCHAIN/bin/aarch64-linux-android$API_LEVEL-clang"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
READELF="$TOOLCHAIN/bin/llvm-readelf"
OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"
OBJDUMP="$TOOLCHAIN/bin/llvm-objdump"
LD="$TOOLCHAIN/bin/ld.lld"

for tool in "$CC" "$AR" "$RANLIB" "$STRIP" "$READELF" "$OBJCOPY" "$OBJDUMP" "$LD" curl unzip tar sha256sum make patch python3; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "Required build tool is missing: $tool" >&2
    exit 2
  }
done

OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
LICENSE_OUT="$ROOT/app/src/main/assets/third_party-licenses"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PREFIX="$WORK/prefix"
mkdir -p "$PREFIX/include" "$PREFIX/lib" "$OUT" "$LICENSE_OUT"
rm -f "$OUT/libproot.so" "$OUT/libproot-loader.so" "$OUT/libtalloc.so.2" "$OUT/libandroid-shmem.so"

download_checked() {
  local url="$1"
  local destination="$2"
  local expected="$3"
  echo "Downloading $(basename "$destination")"
  curl --fail --location --retry 3 --retry-all-errors --silent --show-error "$url" -o "$destination"
  echo "$expected  $destination" | sha256sum --check --status || {
    echo "SHA-256 mismatch for $url" >&2
    exit 1
  }
}

PROOT_ARCHIVE="$WORK/proot.zip"
TALLOC_ARCHIVE="$WORK/talloc.tar.gz"
SHMEM_ARCHIVE="$WORK/libandroid-shmem.tar.gz"

download_checked \
  "https://github.com/termux/proot/archive/refs/tags/v$PROOT_VERSION.zip" \
  "$PROOT_ARCHIVE" "$PROOT_SHA256"
download_checked \
  "https://www.samba.org/ftp/talloc/talloc-$TALLOC_VERSION.tar.gz" \
  "$TALLOC_ARCHIVE" "$TALLOC_SHA256"
download_checked \
  "https://github.com/termux/libandroid-shmem/archive/refs/tags/v$SHMEM_VERSION.tar.gz" \
  "$SHMEM_ARCHIVE" "$SHMEM_SHA256"

unzip -q "$PROOT_ARCHIVE" -d "$WORK"
tar -xzf "$TALLOC_ARCHIVE" -C "$WORK"
tar -xzf "$SHMEM_ARCHIVE" -C "$WORK"

PROOT_SRC="$WORK/proot-$PROOT_VERSION"
TALLOC_SRC="$WORK/talloc-$TALLOC_VERSION"
SHMEM_SRC="$WORK/libandroid-shmem-$SHMEM_VERSION"

for source_dir in "$PROOT_SRC" "$TALLOC_SRC" "$SHMEM_SRC"; do
  [[ -d "$source_dir" ]] || {
    echo "Expected source directory was not extracted: $source_dir" >&2
    exit 1
  }
done

# libandroid-shmem assumes glibc's _PATH_TMP exists. Android/Bionic does not
# provide it; VYROX patches the upstream source to use a private TMPDIR from
# RuntimeProcessSupervisor instead of a shared global temporary directory.
python3 "$ROOT/scripts/patch_android_shmem.py" \
  --proot-source "$PROOT_SRC/src/extension/ashmem_memfd/ashmem_memfd.c" \
  --sysvipc-source "$PROOT_SRC/src/extension/sysvipc/sysvipc_shm.c" \
  "$SHMEM_SRC/shmem.c"

JOBS="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)"

echo "Building static libtalloc $TALLOC_VERSION for arm64-v8a"
TALLOC_WORK="$WORK/talloc-build"
mkdir -p "$TALLOC_WORK"
cp -a "$TALLOC_SRC/." "$TALLOC_WORK/"
mkdir -p "$TALLOC_WORK/tool-shims"
printf '#!/bin/sh\nexit 1\n' > "$TALLOC_WORK/tool-shims/pkg-config"
chmod 0755 "$TALLOC_WORK/tool-shims/pkg-config"
ln -s "$(command -v python3)" "$TALLOC_WORK/tool-shims/python"

cat > "$TALLOC_WORK/cross-answers.txt" <<'ANSWERS'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for -D_LARGE_FILES: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
ANSWERS

(
  cd "$TALLOC_WORK"
  export CC AR RANLIB STRIP OBJCOPY OBJDUMP LD
  export CFLAGS="-O2 -fPIC"
  export CPPFLAGS="-D_FILE_OFFSET_BITS=64"
  export LDFLAGS=""
  export PATH="$TALLOC_WORK/tool-shims:$PATH"

  ./configure \
    --prefix="$PREFIX" \
    --disable-rpath \
    --disable-python \
    --cross-compile \
    --cross-answers=cross-answers.txt

  make -j"$JOBS"
  "$AR" rcs "$PREFIX/lib/libtalloc.a" bin/default/talloc*.o
  "$RANLIB" "$PREFIX/lib/libtalloc.a"
  install -m 0644 talloc.h "$PREFIX/include/talloc.h"
)

[[ -s "$PREFIX/lib/libtalloc.a" ]] || {
  echo "Static libtalloc archive was not produced." >&2
  exit 1
}

echo "Building static libandroid-shmem $SHMEM_VERSION for arm64-v8a"
(
  cd "$SHMEM_SRC"
  export CC AR
  export CFLAGS="-O2 -fPIC"
  make -j"$JOBS" CFLAGS="-O2 -fPIC -std=c11 -Wall -Wextra" libandroid-shmem.a
  install -m 0644 libandroid-shmem.a "$PREFIX/lib/libandroid-shmem.a"
  install -m 0644 shm.h "$PREFIX/include/shm.h"
)
[[ -s "$PREFIX/lib/libandroid-shmem.a" ]] || {
  echo "Static libandroid-shmem archive was not produced." >&2
  exit 1
}

echo "Building Termux PRoot $PROOT_VERSION and its Android loader"
python3 - "$PROOT_SRC/src/GNUmakefile" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
needle = "-z,noexecstack"
if needle not in text:
    raise SystemExit("PRoot GNUmakefile no longer contains the expected loader link flag.")
text = text.replace(needle, "-z,noexecstack,-z,max-page-size=16384")
path.write_text(text, encoding="utf-8")
PY

(
  cd "$PROOT_SRC/src"
  export CC AR RANLIB STRIP OBJCOPY OBJDUMP
  export CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -DARG_MAX=131072 -DVERSION=\\\"$PROOT_VERSION\\\" -I. -I$PROOT_SRC/src -I$PREFIX/include"
  export CFLAGS="-Wall -Wextra -O2 -fPIC -fPIE -DWITH_LIBANDROID_SHMEM"
  unset PROOT_UNBUNDLE_LOADER || true
  make -j"$JOBS" LD="$CC" \
    LDFLAGS="-L$PREFIX/lib -Wl,-z,noexecstack -Wl,--no-undefined -Wl,-z,max-page-size=16384 -pie -ltalloc -landroid-shmem -landroid -llog" \
    proot loader/loader
)

[[ -x "$PROOT_SRC/src/proot" ]] || {
  echo "PRoot executable was not produced." >&2
  exit 1
}
[[ -x "$PROOT_SRC/src/loader/loader" ]] || {
  echo "The required static PRoot loader was not produced." >&2
  exit 1
}

install -m 0755 "$PROOT_SRC/src/proot" "$OUT/libproot.so"
install -m 0755 "$PROOT_SRC/src/loader/loader" "$OUT/libproot-loader.so"

verify_arm64_elf() {
  local file="$1"
  local expected_type="$2"
  "$READELF" -h "$file" | grep -Eq 'Class:[[:space:]]+ELF64' || {
    echo "Not an ELF64 file: $file" >&2
    exit 1
  }
  "$READELF" -h "$file" | grep -Eq 'Data:[[:space:]]+2.s complement, little endian' || {
    echo "Not a little-endian ELF: $file" >&2
    exit 1
  }
  "$READELF" -h "$file" | grep -Eq 'Machine:[[:space:]]+AArch64' || {
    echo "Not an AArch64 ELF: $file" >&2
    exit 1
  }
  "$READELF" -h "$file" | grep -Eq "Type:[[:space:]]+$expected_type" || {
    echo "Unexpected ELF type for $file (expected $expected_type)." >&2
    exit 1
  }
}

verify_arm64_elf "$OUT/libproot.so" DYN
verify_arm64_elf "$OUT/libproot-loader.so" EXEC
if "$READELF" -l "$OUT/libproot-loader.so" | grep -q INTERP; then
  echo "The PRoot loader must be static and must not have PT_INTERP." >&2
  exit 1
fi

PROOT_DYNAMIC="$("$READELF" -d "$OUT/libproot.so")"
if grep -Eq 'libtalloc|libandroid-shmem' <<< "$PROOT_DYNAMIC"; then
  echo "PRoot still has a dynamic talloc or libandroid-shmem dependency." >&2
  exit 1
fi
if grep -Eq '\\((RPATH|RUNPATH)\\)' <<< "$PROOT_DYNAMIC"; then
  echo "PRoot must not embed a build-host RPATH/RUNPATH." >&2
  exit 1
fi

"$STRIP" --strip-unneeded "$OUT/libproot.so"
"$STRIP" --strip-unneeded "$OUT/libproot-loader.so"
chmod 0755 "$OUT/libproot.so" "$OUT/libproot-loader.so"

mkdir -p "$LICENSE_OUT/proot" "$LICENSE_OUT/talloc" "$LICENSE_OUT/libandroid-shmem"
cp "$PROOT_SRC/COPYING" "$LICENSE_OUT/proot/COPYING"
for notice in "$TALLOC_SRC"/COPYING* "$TALLOC_SRC"/LICENSE* "$TALLOC_SRC"/NOTICE*; do
  [[ -f "$notice" ]] && cp "$notice" "$LICENSE_OUT/talloc/"
done
cp "$SHMEM_SRC/LICENSE" "$LICENSE_OUT/libandroid-shmem/LICENSE"

{
  echo "VYROX Android native runtime build"
  echo "Target ABI: arm64-v8a"
  echo "Android API: $API_LEVEL"
  echo "NDK: $NDK_VERSION"
  echo "PRoot: $PROOT_VERSION (Termux source)"
  echo "talloc: $TALLOC_VERSION (static)"
  echo "libandroid-shmem: $SHMEM_VERSION (static)"
  echo "PRoot loader: libproot-loader.so (static AArch64 ELF)"
  echo "PROOT_LOADER points to the loader in applicationInfo.nativeLibraryDir."
  echo
  sha256sum "$OUT/libproot.so" "$OUT/libproot-loader.so"
} > "$LICENSE_OUT/BUILD-INFO.txt"

echo "PRoot Android payload built and validated:"
ls -lh "$OUT/libproot.so" "$OUT/libproot-loader.so"

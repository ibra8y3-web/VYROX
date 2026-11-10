#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NDK_VERSION="27.2.12479018"
API_LEVEL="26"
PROOT_VERSION="5.1.107.102"
TALLOC_VERSION="2.5.0"
SHMEM_VERSION="0.7"

PROOT_SHA256="30d12a6e2cc4147123756ff58edea262d3083bbc238c12d0ab9b39e3d1c40852"
TALLOC_SHA256="912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007"
SHMEM_SHA256="1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867"

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK_ROOT" ]]; then
  echo "ANDROID_SDK_ROOT or ANDROID_HOME must point to an installed Android SDK." >&2
  exit 2
fi

NDK_ROOT="${ANDROID_NDK_HOME:-$SDK_ROOT/ndk/$NDK_VERSION}"
TOOLCHAIN="$NDK_ROOT/toolchains/llvm/prebuilt/linux-x86_64"
if [[ ! -x "$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang" ]]; then
  echo "Android NDK $NDK_VERSION with the Linux x86_64 host toolchain was not found at $NDK_ROOT." >&2
  exit 2
fi

CC="$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang"
AR="$TOOLCHAIN/bin/llvm-ar"
RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
STRIP="$TOOLCHAIN/bin/llvm-strip"
READELF="$TOOLCHAIN/bin/llvm-readelf"
OBJCOPY="$TOOLCHAIN/bin/llvm-objcopy"
OBJDUMP="$TOOLCHAIN/bin/llvm-objdump"

for tool in "$AR" "$RANLIB" "$STRIP" "$READELF" "$OBJCOPY" "$OBJDUMP" curl unzip tar sha256sum make; do
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

JOBS="${VYROX_BUILD_JOBS:-$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 2)}"
export CC AR RANLIB STRIP
export CFLAGS="-O2 -fPIC"
export LDFLAGS="-Wl,-z,noexecstack"

echo "Building libtalloc $TALLOC_VERSION for arm64-v8a"
cat > "$TALLOC_SRC/cross-answers.txt" <<'ANSWERS'
Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
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
ANSWERS

(
  cd "$TALLOC_SRC"
  ./configure \
    --prefix="$PREFIX" \
    --disable-rpath \
    --disable-python \
    --cross-compile \
    --cross-answers=cross-answers.txt
  make -j"$JOBS"
  make install
  if [[ -d bin/default ]]; then
    "$AR" rcs bin/default/libtalloc.a bin/default/talloc*.o
    install -m 0644 bin/default/libtalloc.a "$PREFIX/lib/libtalloc.a"
  fi
)

TALLOC_SHARED="$(find "$PREFIX/lib" -maxdepth 1 -type f -name 'libtalloc.so*' -print | sort | head -n 1)"
if [[ -z "$TALLOC_SHARED" ]]; then
  echo "The pinned talloc build did not produce a shared libtalloc library." >&2
  exit 1
fi
install -m 0755 "$TALLOC_SHARED" "$OUT/libtalloc.so.2"

echo "Building libandroid-shmem $SHMEM_VERSION for arm64-v8a"
(
  cd "$SHMEM_SRC"
  make -j"$JOBS" CC="$CC" AR="$AR" PREFIX="$PREFIX" install
)
install -m 0755 "$PREFIX/lib/libandroid-shmem.so" "$OUT/libandroid-shmem.so"

echo "Building Termux PRoot $PROOT_VERSION and its Android loader"
unset PROOT_UNBUNDLE_LOADER || true
(
  cd "$PROOT_SRC/src"
  make -j"$JOBS" \
    CC="$CC" \
    LD="$CC" \
    AR="$AR" \
    STRIP="$STRIP" \
    OBJCOPY="$OBJCOPY" \
    OBJDUMP="$OBJDUMP" \
    CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -DARG_MAX=131072 -DVERSION=\\\"$PROOT_VERSION\\\" -I. -I$PROOT_SRC/src -I$PREFIX/include" \
    CFLAGS="-Wall -Wextra -O2 -fPIC -fPIE" \
    LDFLAGS="-L$PREFIX/lib -ltalloc -landroid-shmem -Wl,-z,noexecstack -Wl,--no-undefined -pie" \
    PROOT_WITH_LIBANDROID_SHMEM=true \
    proot loader/loader
)

[[ -x "$PROOT_SRC/src/proot" ]] || {
  echo "PRoot executable was not produced." >&2
  exit 1
}
[[ -x "$PROOT_SRC/src/loader/loader" ]] || {
  echo "The required PRoot loader was not produced." >&2
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
verify_arm64_elf "$OUT/libtalloc.so.2" DYN
verify_arm64_elf "$OUT/libandroid-shmem.so" DYN

"$READELF" -d "$OUT/libproot.so" | grep -Fq 'libtalloc.so.2' || {
  echo "PRoot does not declare the expected libtalloc.so.2 dependency." >&2
  exit 1
}
"$READELF" -d "$OUT/libproot.so" | grep -Fq 'libandroid-shmem.so' || {
  echo "PRoot does not declare the expected libandroid-shmem.so dependency." >&2
  exit 1
}
"$READELF" -d "$OUT/libtalloc.so.2" | grep -Fq 'libtalloc.so.2' || {
  echo "libtalloc SONAME is not libtalloc.so.2." >&2
  exit 1
}
if "$READELF" -d "$OUT/libproot.so" | grep -Eq '\((RPATH|RUNPATH)\)'; then
  echo "PRoot must not embed a build-host RPATH/RUNPATH." >&2
  exit 1
fi

"$STRIP" --strip-unneeded "$OUT/libproot.so"
"$STRIP" --strip-unneeded "$OUT/libproot-loader.so"
"$STRIP" --strip-unneeded "$OUT/libtalloc.so.2"
"$STRIP" --strip-unneeded "$OUT/libandroid-shmem.so"
chmod 0755 "$OUT/"*.so

# Preserve upstream license and notice files alongside the packaged native payload.
mkdir -p "$LICENSE_OUT/proot" "$LICENSE_OUT/talloc" "$LICENSE_OUT/libandroid-shmem"
cp "$PROOT_SRC/COPYING" "$LICENSE_OUT/proot/COPYING"
find "$TALLOC_SRC" -maxdepth 1 -type f \\( -iname 'COPYING*' -o -iname 'LICENSE*' -o -iname 'NOTICE*' \\) \
  -exec cp {} "$LICENSE_OUT/talloc/" \\;
cp "$SHMEM_SRC/LICENSE" "$LICENSE_OUT/libandroid-shmem/LICENSE"

{
  echo "VYROX Android native runtime build"
  echo "Target ABI: arm64-v8a"
  echo "Android API: $API_LEVEL"
  echo "NDK: $NDK_VERSION"
  echo "PRoot: $PROOT_VERSION (Termux source)"
  echo "talloc: $TALLOC_VERSION"
  echo "libandroid-shmem: $SHMEM_VERSION"
  echo "PRoot loader: libproot-loader.so (static AArch64 ELF)"
  echo "PROOT_LOADER must point to the loader in applicationInfo.nativeLibraryDir."
  echo
  sha256sum "$OUT/libproot.so" "$OUT/libproot-loader.so" "$OUT/libtalloc.so.2" "$OUT/libandroid-shmem.so"
} > "$LICENSE_OUT/BUILD-INFO.txt"

echo "PRoot Android payload built and validated:"
ls -lh "$OUT"/libproot.so "$OUT"/libproot-loader.so "$OUT"/libtalloc.so.2 "$OUT"/libandroid-shmem.so

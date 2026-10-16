#!/usr/bin/env bash
# Builds pjproject's pjsua2 SWIG/Java binding for Android into this module:
#   src/generated/java      org.pjsip.pjsua2.* (SWIG output)
#   src/generated/jniLibs   libpjsua2.so + libc++_shared.so per ABI
# Both are git-ignored; rerun after a clean checkout or a native configuration change.
# Tarballs and shared Mbed TLS/Opus sources live at the work directory's top level.
# Dependencies build out of tree; PJSIP keeps an isolated source/build tree per ABI.
# Each ABI is rebuilt from scratch; shared source trees are retained.
# Java/JNI wrapper generation runs once per invocation using the common Android binding profile;
# the generated C++ wrapper is still compiled and linked separately for every ABI.
# Media: null sound device only (no Android audio backend, so pjsua never touches
# AudioManager), G.711 + mono Opus (up to 16 kHz), bundled libsrtp and Mbed TLS.
set -euo pipefail

PJ_VERSION=2.17-dev
# Official upstream revision with Mbed TLS PSA/TLS 1.3 fixes and secure SDES RNG.
PJ_REVISION=15e9be5494d72811690eaba75a8cb6b79678cbfc
PJ_SHA256=a8b9291862fdd750c52e27c419c643a5ed84ee0813086ca6ba8f84feda4f11c0
MBEDTLS_VERSION=3.6.7
MBEDTLS_SHA256=a7e8bcbec0e6f761b4af24f25677626b35f762f68eef79c08677a363212d11f6
OPUS_VERSION=1.6.1
OPUS_SHA256=6ffcb593207be92584df15b32466ed64bbec99109f007c82205f0194572411a1
ABIS=${ABIS:-arm64-v8a}
APP_PLATFORM=${APP_PLATFORM:-android-30}
NATIVE_SIZE_REPORTS=${NATIVE_SIZE_REPORTS:-0}
case $NATIVE_SIZE_REPORTS in
  0|1) ;;
  *) echo "NATIVE_SIZE_REPORTS must be 0 or 1" >&2; exit 1 ;;
esac
# Use one NDK toolchain for ThinLTO across all static dependencies and the JNI wrapper.
NATIVE_CFLAGS="-O2 -fPIC -flto=thin -ffunction-sections -fdata-sections -fvisibility=hidden"
NATIVE_LDFLAGS="-flto=thin -Wl,--gc-sections"

read -r -a ABI_LIST <<< "$ABIS"
[ "${#ABI_LIST[@]}" -gt 0 ] || { echo "No ABIs specified" >&2; exit 1; }
for ABI in "${ABI_LIST[@]}"; do
  case $ABI in
    arm64-v8a|armeabi-v7a|x86_64|x86) ;;
    *) echo "Unsupported ABI: $ABI" >&2; exit 1 ;;
  esac
done

HERE=$(cd "$(dirname "$0")" && pwd)
MODULE=$(dirname "$HERE")
WORK=${PJ_WORK:-$MODULE/build/pjproject}
SDK=${ANDROID_HOME:-$(sed -n 's/^sdk.dir=//p' "$MODULE/../local.properties" 2>/dev/null)}
export ANDROID_NDK_ROOT=${ANDROID_NDK_ROOT:-$SDK/ndk/28.2.13676358}
[ -d "$ANDROID_NDK_ROOT" ] || { echo "NDK not found: $ANDROID_NDK_ROOT" >&2; exit 1; }
command -v swig >/dev/null || { echo "swig not found (brew install swig)" >&2; exit 1; }
command -v cmake >/dev/null || { echo "cmake not found" >&2; exit 1; }
command -v python3 >/dev/null || { echo "python3 not found" >&2; exit 1; }

mkdir -p "$WORK"
# Download and verify the three pinned upstream source archives.
TARBALL="$WORK/pjproject-$PJ_REVISION.tar.gz"
if [ ! -f "$TARBALL" ]; then
  curl -fsSL -o "$TARBALL.part" "https://github.com/pjsip/pjproject/archive/$PJ_REVISION.tar.gz"
  mv "$TARBALL.part" "$TARBALL"
fi
echo "$PJ_SHA256  $TARBALL" | shasum -a 256 -c -

# Use the full release archive, which includes the framework submodule.
# MBEDTLS_TARBALL can point at a previously downloaded release archive.
MBEDTLS_TARBALL=${MBEDTLS_TARBALL:-$WORK/mbedtls-$MBEDTLS_VERSION.tar.bz2}
if [ ! -f "$MBEDTLS_TARBALL" ]; then
  curl -fsSL -o "$MBEDTLS_TARBALL.part" \
    "https://github.com/Mbed-TLS/mbedtls/releases/download/mbedtls-$MBEDTLS_VERSION/mbedtls-$MBEDTLS_VERSION.tar.bz2"
  mv "$MBEDTLS_TARBALL.part" "$MBEDTLS_TARBALL"
fi
echo "$MBEDTLS_SHA256  $MBEDTLS_TARBALL" | shasum -a 256 -c -

OPUS_TARBALL="$WORK/opus-$OPUS_VERSION.tar.gz"
if [ ! -f "$OPUS_TARBALL" ]; then
  curl -fsSL -o "$OPUS_TARBALL.part" "https://downloads.xiph.org/releases/opus/opus-$OPUS_VERSION.tar.gz"
  mv "$OPUS_TARBALL.part" "$OPUS_TARBALL"
fi
echo "$OPUS_SHA256  $OPUS_TARBALL" | shasum -a 256 -c -

HOST_TAG=$(ls "$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt" | head -1)
LLVM_BIN="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG/bin"
API=${APP_PLATFORM#android-}
JOBS=$(sysctl -n hw.ncpu 2>/dev/null || nproc)

# Extract shared sources once. A marker is written only after extraction succeeds.
# Dependency builds run in ABI directories; Mbed TLS configuration is shared below.
if [ ! -f "$WORK/mbedtls-$MBEDTLS_VERSION/.extracted" ]; then
  rm -rf "$WORK/mbedtls-$MBEDTLS_VERSION"
  tar xjf "$MBEDTLS_TARBALL" -C "$WORK"
  touch "$WORK/mbedtls-$MBEDTLS_VERSION/.extracted"
fi
# The installed headers must match the library configuration. Configure the shared
# source header so CMake installs the same settings used for every ABI's build.
python3 "$WORK/mbedtls-$MBEDTLS_VERSION/scripts/config.py" \
  -f "$WORK/mbedtls-$MBEDTLS_VERSION/include/mbedtls/mbedtls_config.h" set MBEDTLS_THREADING_C
python3 "$WORK/mbedtls-$MBEDTLS_VERSION/scripts/config.py" \
  -f "$WORK/mbedtls-$MBEDTLS_VERSION/include/mbedtls/mbedtls_config.h" set MBEDTLS_THREADING_PTHREAD
if [ ! -f "$WORK/opus-$OPUS_VERSION/.extracted" ]; then
  rm -rf "$WORK/opus-$OPUS_VERSION"
  tar xzf "$OPUS_TARBALL" -C "$WORK"
  touch "$WORK/opus-$OPUS_VERSION/.extracted"
fi

# Stage all ABIs before replacing the previous usable generated tree.
GENERATED="$MODULE/src/generated"
mkdir -p "$MODULE/src"
STAGE=$(mktemp -d "$MODULE/src/.generated-stage.XXXXXX")
cleanup() {
  if [ -n "$STAGE" ]; then rm -rf "$STAGE"; fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
mkdir -p "$STAGE/java" "$STAGE/jniLibs"
SWIG_STAGE="$STAGE/.swig"
mkdir -p "$SWIG_STAGE"

for ABI in "${ABI_LIST[@]}"; do
  case $ABI in
    arm64-v8a) OPUS_HOST=aarch64-linux-android; OPUS_CC_TARGET=$OPUS_HOST ;;
    armeabi-v7a) OPUS_HOST=arm-linux-androideabi; OPUS_CC_TARGET=armv7a-linux-androideabi ;;
    x86_64) OPUS_HOST=x86_64-linux-android; OPUS_CC_TARGET=$OPUS_HOST ;;
    x86) OPUS_HOST=i686-linux-android; OPUS_CC_TARGET=$OPUS_HOST ;;
  esac
  ABI_WORK="$WORK/$ABI"
  SSL_BUILD="$ABI_WORK/mbedtls-build"
  OPUS_BUILD="$ABI_WORK/opus-build"
  SSL_PREFIX="$ABI_WORK/prefix/mbedtls"
  OPUS_PREFIX="$ABI_WORK/prefix/opus"
  SRC="$ABI_WORK/pjproject-$PJ_REVISION"
  # Fresh objects, installed archives and JNI output avoid stale configuration,
  # patches or linking against an earlier dependency build.
  rm -rf "$ABI_WORK"
  mkdir -p "$ABI_WORK"

  # Static, thread-safe Mbed TLS for SIP TLS and secure SDES-SRTP key generation.
  cmake -S "$WORK/mbedtls-$MBEDTLS_VERSION" -B "$SSL_BUILD" -G "Unix Makefiles" \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$ABI" -DANDROID_PLATFORM="$APP_PLATFORM" \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DCMAKE_C_FLAGS="$NATIVE_CFLAGS" -DCMAKE_INSTALL_PREFIX="$SSL_PREFIX" \
    -DCMAKE_INSTALL_LIBDIR=lib -DENABLE_TESTING=OFF -DENABLE_PROGRAMS=OFF \
    -DUSE_STATIC_MBEDTLS_LIBRARY=ON -DUSE_SHARED_MBEDTLS_LIBRARY=OFF
  cmake --build "$SSL_BUILD" --parallel "$JOBS"
  cmake --install "$SSL_BUILD"

  # Static libopus.
  mkdir -p "$OPUS_BUILD"
  (
    cd "$OPUS_BUILD"
    export CC="$LLVM_BIN/${OPUS_CC_TARGET}${API}-clang"
    export AR="$LLVM_BIN/llvm-ar" RANLIB="$LLVM_BIN/llvm-ranlib" STRIP="$LLVM_BIN/llvm-strip"
    CFLAGS="$NATIVE_CFLAGS" LDFLAGS="$NATIVE_LDFLAGS" "$WORK/opus-$OPUS_VERSION/configure" \
      --host="$OPUS_HOST" --prefix="$OPUS_PREFIX" \
      --disable-shared --enable-static --with-pic --disable-doc --disable-extra-programs
    make -j"$JOBS"
    make install
  )

  OUT="$SRC/pjsip-apps/src/swig/java/android/pjsua2/src/main"
  LIB="$OUT/jniLibs/$ABI/libpjsua2.so"
  LINK_MAP="$ABI_WORK/pjsua2.link.map"
  tar xzf "$TARBALL" -C "$ABI_WORK"
  cp "$HERE/config_site.h" "$SRC/pjlib/include/pj/config_site.h"
  # Accept "*." wildcard server certificates (RFC 6125); stock pjsip rejects them per RFC 5922.
  patch -p1 -d "$SRC" < "$HERE/pjsip-tls-wildcard.patch"
  # Sound-device idle auto-close and Mbed TLS/SRTP fixes are included upstream.
  # Bulk byte[] <-> ByteVector copies; the stock binding is a List<Short> with one JNI call per byte.
  # Must precede %template(ByteVector), so it goes right after the and_string.i include.
  I="$SRC/pjsip-apps/src/swig/pjsua2.i"
  # Apply the Java feature profile after upstream director declarations so its
  # Endpoint override takes effect, but before headers and feature templates.
  awk -v ext="$HERE/pjsua2-java-ext.i" -v profile="$HERE/pjsua2-java-profile.i" '
    /^%import "pj\/config_site.h"/ { while ((getline l < profile) > 0) print l }
    { print }
    /^%include "and_string.i"/ { while ((getline l < ext) > 0) print l }
  ' "$I" > "$I.new"
  mv "$I.new" "$I"
  grep -q vsaOutCap "$I"
  grep -q 'Voice gateway Java binding profile' "$I"
  (
    cd "$SRC"
    # configure-android replaces CXXFLAGS, but carries CPPFLAGS into both C and C++.
    export CPPFLAGS="${CPPFLAGS:-} $NATIVE_CFLAGS"
    export LDFLAGS="${LDFLAGS:-} $NATIVE_LDFLAGS"
    # Null audio device, G.711/Opus, TLS and SRTP; omit unused codecs/video.
    TARGET_ABI=$ABI APP_PLATFORM=$APP_PLATFORM ./configure-android --use-ndk-cflags \
      --with-mbedtls="$SSL_PREFIX" --with-opus="$OPUS_PREFIX" \
      --disable-sound --disable-video --disable-upnp \
      --disable-silk --disable-bcg729 --disable-lyra --disable-opencore-amr \
      --disable-openh264 --disable-vpx --disable-ffmpeg --disable-v4l2 --disable-sdl \
      --disable-libyuv --disable-libwebrtc --disable-android-mediacodec \
      --disable-gsm-codec --disable-ilbc-codec --disable-speex-codec --disable-g722-codec \
      --disable-g7221-codec --disable-l16-codec --disable-speex-aec
    grep -q "PJ_HAS_SSL_SOCK 1" pjlib/include/pj/compat/os_auto.h || { echo "SSL not enabled" >&2; exit 1; }
    grep -q "PJ_SSL_SOCK_IMP PJ_SSL_SOCK_IMP_MBEDTLS" pjlib/include/pj/compat/os_auto.h || { echo "Mbed TLS not selected" >&2; exit 1; }
    grep -q "PJMEDIA_HAS_OPUS_CODEC 1" pjmedia/include/pjmedia-codec/config_auto.h || { echo "Opus not enabled" >&2; exit 1; }
    grep -q "PJMEDIA_HAS_G722_CODEC 0" pjmedia/include/pjmedia-codec/config_auto.h || { echo "G.722 not disabled" >&2; exit 1; }
    make -j"$JOBS"
    SWIG_DIR="$SRC/pjsip-apps/src/swig/java"
    if [ ! -f "$SWIG_STAGE/pjsua2_wrap.cpp" ]; then
      # The upstream SWIG flags only select Android/Java, not an ABI. All source trees
      # use the same config_site.h and interface patch, so Java and wrapper declarations
      # are shared. Generate through upstream make to retain its flags and include paths.
      make -C "$SWIG_DIR" output/pjsua2_wrap.cpp
      cp "$SWIG_DIR/output/pjsua2_wrap.cpp" "$SWIG_DIR/output/pjsua2_wrap.h" "$SWIG_STAGE/"
      cp -R "$OUT/java/org" "$STAGE/java/"
    else
      mkdir -p "$SWIG_DIR/output" "$OUT/java"
      cp "$SWIG_STAGE/pjsua2_wrap.cpp" "$SWIG_STAGE/pjsua2_wrap.h" "$SWIG_DIR/output/"
      cp -R "$STAGE/java/org" "$OUT/java/"
    fi
    # Localize dependencies only at the shared JNI link, not configure's executables.
    # Treat the generated source as already made, irrespective of extracted header timestamps.
    # This skips only SWIG; fresh per-ABI objects and JNI libraries must still be built.
    JNI_LDFLAGS="$LDFLAGS -Wl,--version-script=$HERE/pjsua2.exports.map -Wl,--exclude-libs,ALL"
    if [ "$NATIVE_SIZE_REPORTS" = 1 ]; then
      JNI_LDFLAGS+=" -Wl,-Map=$LINK_MAP"
    fi
    make -C "$SWIG_DIR" -o output/pjsua2_wrap.cpp \
      LDFLAGS="$JNI_LDFLAGS"
  )

  if [ "$NATIVE_SIZE_REPORTS" = 1 ]; then
    [ -s "$LINK_MAP" ] || { echo "JNI linker map missing: $LINK_MAP" >&2; exit 1; }
    # Inspect the original ELF before APK stripping removes the local symbol table.
    "$LLVM_BIN/llvm-size" --format=sysv --radix=10 "$LIB" > "$ABI_WORK/pjsua2.sections.txt"
    "$LLVM_BIN/llvm-nm" --defined-only --print-size --size-sort --reverse-sort --radix=d --demangle \
      "$LIB" > "$ABI_WORK/pjsua2.symbol-sizes.txt"
    echo "Native size reports: $ABI_WORK/pjsua2.{link.map,sections.txt,symbol-sizes.txt}"
  fi

  EXPORTS="$ABI_WORK/pjsua2.exports.txt"
  "$LLVM_BIN/llvm-nm" -D --defined-only --extern-only "$LIB" > "$EXPORTS"
  grep -q 'Java_org_pjsip_pjsua2_pjsua2JNI_' "$EXPORTS" || { echo "JNI exports missing" >&2; exit 1; }
  awk '$NF !~ /^(Java_org_pjsip_pjsua2_pjsua2JNI_|JNI_OnLoad$|JNI_OnUnload$)/ {
         print "Unexpected native export: " $NF > "/dev/stderr"; bad=1
       } END { exit bad }' "$EXPORTS"
  mkdir -p "$STAGE/jniLibs/$ABI"
  cp "$OUT/jniLibs/$ABI/"*.so "$STAGE/jniLibs/$ABI/"
done

rm -rf "$SWIG_STAGE"
rm -rf "$GENERATED"
mv "$STAGE" "$GENERATED"
STAGE=""
ls -l "$GENERATED/jniLibs"/*/
echo "pjsua2 $PJ_VERSION ($PJ_REVISION) built for: ${ABI_LIST[*]}"

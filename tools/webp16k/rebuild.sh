#!/usr/bin/env bash
# Rebuild the pinned WebP overlay without installing or changing a toolchain.
set -euo pipefail
project_dir=$(cd -- "$(dirname -- "$0")" && pwd)
work_dir=${1:?Usage: bash tools/webp16k/rebuild.sh /absolute/scratch/directory}
case "$work_dir" in /*) ;; *) echo 'Scratch directory must be absolute' >&2; exit 1;; esac
sdk_dir=${ANDROID_SDK_ROOT:-${ANDROID_HOME:-"$HOME/Android/Sdk"}}
ndk_dir="$sdk_dir/ndk/28.2.13676358"
cmake="$sdk_dir/cmake/3.22.1/bin/cmake"
ninja="$sdk_dir/cmake/3.22.1/bin/ninja"
if ! test -x "$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin/clang" || ! test -x "$cmake"; then
 echo 'Requires existing Linux x86_64 Android NDK 28.2.13676358 and CMake 3.22.1 in Android SDK.' >&2; exit 1
fi
mkdir -p "$work_dir"
archive="$work_dir/libwebp-v1.6.0.tar.gz"
if ! test -f "$archive"; then
 curl --fail --location --show-error --silent https://codeload.github.com/webmproject/libwebp/tar.gz/refs/tags/v1.6.0 -o "$archive"
fi
printf '%s  %s\n' 93a852c2b3efafee3723efd4636de855b46f9fe1efddd607e1f42f60fc8f2136 "$archive" | sha256sum -c -
if ! test -d "$work_dir/libwebp-1.6.0"; then tar -xzf "$archive" -C "$work_dir"; fi
for abi in arm64-v8a x86_64 armeabi-v7a x86; do
 "$cmake" -S "$work_dir/libwebp-1.6.0" -B "$work_dir/build/$abi" -G Ninja \
  -DCMAKE_MAKE_PROGRAM="$ninja" -DCMAKE_TOOLCHAIN_FILE="$ndk_dir/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-24 -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_FLAGS=-g0 \
  -DBUILD_SHARED_LIBS=ON -DWEBP_BUILD_ANIM_UTILS=OFF -DWEBP_BUILD_CWEBP=OFF \
  -DWEBP_BUILD_DWEBP=OFF -DWEBP_BUILD_GIF2WEBP=OFF -DWEBP_BUILD_IMG2WEBP=OFF \
  -DWEBP_BUILD_VWEBP=OFF -DWEBP_BUILD_WEBPINFO=OFF -DWEBP_BUILD_WEBPMUX=OFF \
  -DWEBP_BUILD_EXTRAS=OFF -DWEBP_BUILD_LIBWEBPMUX=ON -DWEBP_ENABLE_SWAP_16BIT_CSP=ON \
  '-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384'
 "$cmake" --build "$work_dir/build/$abi" --parallel 2 --target webp webpdecoder webpdemux libwebpmux sharpyuv
 case "$abi" in arm64-v8a|x86_64)
  mkdir -p "$work_dir/overlay/$abi"
  for lib in libsharpyuv.so libwebp.so libwebpdecoder.so libwebpdemux.so libwebpmux.so; do
   cp "$work_dir/build/$abi/$lib" "$work_dir/overlay/$abi/$lib"
   "$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip" --strip-unneeded "$work_dir/overlay/$abi/$lib"
  done;;
 esac
done
(cd "$work_dir" && sha256sum -c "$project_dir/SHA256SUMS")
echo "Verified rebuilt overlay at $work_dir/overlay. Existing project binaries have not been changed."

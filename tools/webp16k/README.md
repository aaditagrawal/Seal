# Seal WebP 16 KB overlay

FFmpeg 0.18.1 contains five WebP-family shared objects for each 64-bit ABI with 4 KB ELF segment alignment. FFmpeg's libavcodec loads libwebp and libwebpmux at process startup. The overlay rebuilds those libraries from the same upstream codec version, libwebp 1.6.0, at Android API 24 with 16 KB alignment. All original exports and SONAMEs are retained.

Only arm64-v8a and x86_64 archives change. The two 32-bit archives, FFmpeg binaries and other codecs remain byte-for-byte upstream. A dedicated untransformed configuration identifies the exact resolved FFmpeg artifact. The Gradle transform passes other AARs through unchanged and validates the exact upstream AAR SHA256, pinned overlay manifest, all ten library hashes, ELF architectures and 16 KB LOAD mappings. It retains dependency metadata, nested ZIP Unix modes and symlinks. Other modules cannot be rewritten by filename collisions. Absolute artifact identity participates in the transform cache key. Each changed archive must have a different byte length because upstream FFmpeg.init uses archive size to decide when to re-extract after application updates.

The prebuilt overlay files are small enough to track directly. Rebuild them with existing Linux x86_64 Android NDK 28.2.13676358 and CMake 3.22.1:

```sh
bash tools/webp16k/rebuild.sh /tmp/aadit-seal-16k-rebuild
```

The script builds all four ABIs with two workers and checks the ten distributed binaries against SHA256SUMS. It writes only to the chosen scratch directory. It does not install a toolchain or overwrite the tracked overlay.

Pinned source:

- Tag v1.6.0, commit 4fa21912338357f89e4fd51cf2368325b59e9bd9
- https://codeload.github.com/webmproject/libwebp/tar.gz/refs/tags/v1.6.0
- SHA256 93a852c2b3efafee3723efd4636de855b46f9fe1efddd607e1f42f60fc8f2136
- Same public checksum in https://raw.githubusercontent.com/termux/termux-packages/658851ae0611cf6fbc296f57bbdc71c63ef11a1b/packages/libwebp/build.sh

COPYING, PATENTS and AUTHORS are retained here and in app assets for binary distribution. No libwebp source changes are required. The helper executables cwebp/dwebp and animation tools are not part of the replaced archive payload and are not built. The shared encoder, decoder, standalone decoder, mux, demux, sharpyuv, threading, SIMD and 16-bit byte-swap support are built.

ELF, ABI and API24 symbol checks passed. Two builds in separate scratch directories produced byte-identical copies of all ten distributed libraries. An Android API28 Bionic smoke test on an x86_64 Linux host with 4 KB pages encoded and decoded exact RGBA pixels, muxed and demuxed WebP, and loaded the standalone decoder.

Android guidance: https://developer.android.com/guide/practices/page-sizes

Independent standalone comparisons used official x86_64 API36 environments with PAGE_SIZE values of 4096 and 16384. On 4 KB, original and replacement FFmpeg, audio conversion and WebP checks passed with identical media hashes. On strict 16 KB, original FFmpeg and WebP processes failed with the linker's 4096-byte alignment error; the replacement passed all seven checks, including audio/ffprobe, direct codec/mux/demux/standalone decoder loading, and lossless WebP with exact RGB pixels. The official 16 KB environment had linker compatibility mode disabled. It simulates 16 KB userspace on x86_64 and does not establish physical ARM64 execution.

Integration validation inspected 30 actual APKs: all three flavors, debug and R8 release, each with four ABI splits and a universal APK. All native 64-bit payloads, including nested ZIP contents, passed alignment and offset checks. The 15 unsigned release APKs passed SDK zipalign checks with 16 KB pages and retained minSdk 24. Their DEX and every native file outside the two replaced archives matched the reviewed baseline byte-for-byte.

Installed generic debug APK tests passed on both API36 environments. Each device first retained the original extracted FFmpeg libraries and archive-size version, then received an APK upgrade. Seven instrumentation tests passed after upgrade and again after removing only the extracted FFmpeg directory. These exercised actual wrapper extraction, preserved symlinks and file hashes, the changed archive-size version, loopback yt-dlp download and audio conversion, and an installed-FFmpeg lossless WebP round trip. External networking stayed disabled. There is no ARM, 32-bit or API24/25 device execution claim; the original 32-bit payloads are unchanged and the replacement libraries link against API24 stubs. Release APKs were inspected unsigned and were not installed. Fresh independent review of this integration remains a separate step.

The Commons Compress repacker preserves symlink Unix modes and their targets using the ZIP central directory. It can normalize DOS timestamp representations and UID/GID extra fields; the upstream unzipper creates app-owned files and does not restore ZIP ownership or timestamps. Native content, entry order and symlink semantics are verified.

## Regression gates

```sh
./gradlew -p buildSrc test --no-daemon --max-workers=2
uv run --no-project python -m unittest discover -s scripts -p 'test_*.py'
./gradlew :app:assembleRelease :app:writeWebp16kReference --no-daemon --max-workers=2
uv run --no-project python scripts/verify_native_payloads.py
```

Both PR and manual release workflows run the overlay guards and inspect actual packaged APKs before signing. The APK verifier checks every split and universal ABI, recursively scans native archives, rejects cosmetic alignment headers with incompatible offsets, and compares FFmpeg contents to the pinned original. Only the five libraries in each 64-bit archive may differ. Whole 32-bit archives, other native files, symlink modes and targets must match. Archive lengths must change so the upstream wrapper re-extracts on app updates. Library and manifest bytes are Gradle transform cache inputs.

`NativePayloadTest` runs within the installed Seal instrumentation target. It compares the wrapper's extracted files and symlinks to the installed archive, verifies the saved extraction-size version and distributed WebP hashes, and performs a lossless WebP round trip through the installed FFmpeg binary. `OfflineDownloadTest` also exercises the bundled Python/yt-dlp wrapper and actual audio conversion over loopback. Run these only on an explicitly selected offline test device. Actual 16 KB and older-platform results must be reported separately from 4 KB tests.

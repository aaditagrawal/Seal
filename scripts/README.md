The manual Android release workflow builds three flavors with four ABI splits and
one universal APK each. `release_artifacts.py prepare` reads Gradle's per-flavor
output metadata and requires the complete 15-file matrix. It stages unique APK
basenames in `.release/unsigned`; metadata and the manifest stay outside that
directory. The signing action is pinned to the reviewed nightly commit
`758313793d0a2cf83cb215c5006100079d79c3f0`.

That action recursively finds APK/AAB files, writes `*-signed.apk` alongside its
inputs, and returns colon-separated absolute paths as `signedFiles`. The workflow
passes those paths to `verify`, which requires the exact expected matrix and runs
Build Tools 37's `apksigner verify` on every file before copying anything to
`.release/verified`. Upload reads only that directory. Unsigned inputs, temporary
alignment APKs and the action's temporary keystore cannot enter the upload glob.

Run filesystem regressions without signing credentials:

```sh
uv run --no-project --python 3.14 python -m unittest discover -s scripts -p 'test_*.py'
```

The tests cover every flavor/ABI, missing flavors/splits, duplicate names, path
escapes, symlinks, extra/incomplete action outputs and verification failure. They
mock signature verification; local release validation also checks real unsigned
APKs with the Android SDK verifier. No test signs or uploads an APK.

`OfflineDownloadTest` is an Android instrumentation suite for media intents,
temporary files, native MMKV persistence and a generated WAV download through
bundled Python/yt-dlp over `127.0.0.1`. Run it on an isolated emulator with WiFi and
mobile data disabled. The download test refuses to run when Android reports an
Internet-capable default network. It never uses a production URL.

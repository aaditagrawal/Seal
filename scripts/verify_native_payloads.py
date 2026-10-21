"""Verify the packaged ABI matrix and recursively inspect extracted native payloads."""

import argparse
import hashlib
import io
import json
import struct
import zipfile
from pathlib import Path

ABIS = {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}
LIBRARIES = {
    "libwebp.so",
    "libwebpdecoder.so",
    "libwebpdemux.so",
    "libwebpmux.so",
    "libsharpyuv.so",
}
UPSTREAM_SHA256 = "0a87ffa6cf912b0fe76c1a99b9107f543ee2f247935fae2c71f0822eb7bc5f49"
MANIFEST_SHA256 = "0cadfac35a48a716f42fe8a0084222c55371091f92ea37dd09488f537f252a0b"


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def check_elf(data: bytes, name: str) -> int:
    if not data.startswith(b"\x7fELF"):
        return 0
    if len(data) < 16 or data[4] not in (1, 2) or data[5] not in (1, 2):
        raise ValueError(f"Invalid ELF header: {name}")
    endian = "<" if data[5] == 1 else ">"
    is64 = data[4] == 2
    # Android's 16 KB device requirement applies to 64-bit processes. Preserve 32-bit payloads.
    if not is64:
        return 0
    header = struct.unpack_from(endian + "HHIQQQIHHHHHH", data, 16)
    offset, size, count = header[4], header[8], header[9]
    if size < 56 or count == 0 or offset < 64 or offset + size * count > len(data):
        raise ValueError(f"Invalid ELF program headers: {name}")
    loads = 0
    for i in range(count):
        segment = struct.unpack_from(endian + "IIQQQQQQ", data, offset + i * size)
        kind, _, file_offset, address, _, _, _, alignment = segment
        if kind == 1:
            loads += 1
            if (
                alignment < 16384
                or alignment & (alignment - 1)
                or (address - file_offset) % 16384
            ):
                raise ValueError(f"64-bit ELF lacks 16 KB LOAD alignment: {name}")
    if not loads:
        raise ValueError(f"ELF has no LOAD segments: {name}")
    return 1


def scan(data: bytes, name: str, cache: dict[str, int]) -> int:
    key = digest(data)
    if key in cache:
        return cache[key]
    count = check_elf(data, name)
    if data.startswith(b"PK\x03\x04"):
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            if len(archive.namelist()) != len(set(archive.namelist())):
                raise ValueError(f"Duplicate ZIP entry: {name}")
            for entry in archive.infolist():
                if not entry.is_dir():
                    count += scan(
                        archive.read(entry), f"{name}!{entry.filename}", cache
                    )
    cache[key] = count
    return count


def compare_ffmpeg(old: bytes, new: bytes, abi: str, expected: dict[str, str]) -> dict:
    if abi in {"x86", "armeabi-v7a"}:
        if old != new:
            raise ValueError(f"32-bit FFmpeg archive changed: {abi}")
        return {"abi": abi, "unchanged": True}
    if len(old) == len(new):
        raise ValueError(
            f"FFmpeg archive size must change to trigger extraction: {abi}"
        )
    with (
        zipfile.ZipFile(io.BytesIO(old)) as source,
        zipfile.ZipFile(io.BytesIO(new)) as target,
    ):
        if source.namelist() != target.namelist():
            raise ValueError(f"FFmpeg archive entry names/order changed: {abi}")
        changed = set()
        symlinks = 0
        for entry in source.infolist():
            replacement = target.getinfo(entry.filename)
            if (
                entry.external_attr != replacement.external_attr
                or entry.create_system != replacement.create_system
            ):
                raise ValueError(
                    f"FFmpeg Unix/symlink metadata changed: {abi}/{entry.filename}"
                )
            symlinks += ((entry.external_attr >> 16) & 0o170000) == 0o120000
            original_data, new_data = source.read(entry), target.read(replacement)
            if original_data != new_data:
                changed.add(entry.filename)
                key = f"overlay/{abi}/{Path(entry.filename).name}"
                if key not in expected or digest(new_data) != expected[key]:
                    raise ValueError(
                        f"Unexpected FFmpeg content change: {abi}/{entry.filename}"
                    )
        if changed != {f"usr/lib/{name}" for name in LIBRARIES}:
            raise ValueError(f"Expected exactly five WebP replacements: {abi}")
    return {
        "abi": abi,
        "changed": sorted(changed),
        "symlinks_preserved": symlinks,
        "old_size": len(old),
        "new_size": len(new),
        "sha256": digest(new),
    }


def verify(
    apk_root: Path,
    upstream: Path,
    manifest: Path,
    flavors: list[str],
    build_type: str = "release",
) -> dict:
    manifest_bytes = manifest.read_bytes()
    if digest(manifest_bytes) != MANIFEST_SHA256:
        raise ValueError("WebP manifest changed without reviewing the verifier pin")
    expected = {line[66:]: line[:64] for line in manifest_bytes.decode().splitlines()}
    upstream_bytes = upstream.read_bytes()
    if digest(upstream_bytes) != UPSTREAM_SHA256:
        raise ValueError("FFmpeg upstream changed without reviewing the native overlay")
    records, comparisons, cache = [], {}, {}
    with zipfile.ZipFile(io.BytesIO(upstream_bytes)) as source:
        for flavor in flavors:
            directory = apk_root / flavor / build_type
            metadata = json.loads((directory / "output-metadata.json").read_text())
            outputs = metadata["elements"]
            identifiers = [
                e["filters"][0]["value"] if e["filters"] else "universal"
                for e in outputs
            ]
            if len(outputs) != 5 or set(identifiers) != ABIS | {"universal"}:
                raise ValueError(
                    f"Incomplete or duplicate release ABI matrix: {flavor}"
                )
            for element, identifier in zip(outputs, identifiers, strict=True):
                name = element["outputFile"]
                if Path(name).name != name:
                    raise ValueError("Unsafe APK filename")
                path = directory / name
                with zipfile.ZipFile(path) as apk:
                    if apk.testzip() is not None:
                        raise ValueError(f"Invalid APK CRC: {name}")
                    present = {
                        n.split("/")[1]
                        for n in apk.namelist()
                        if n.startswith("lib/") and not n.endswith("/")
                    }
                    wanted = ABIS if identifier == "universal" else {identifier}
                    if present != wanted:
                        raise ValueError(f"APK ABI contents differ: {name}: {present}")
                    count = 0
                    for entry in apk.infolist():
                        if entry.filename.startswith("lib/") and not entry.is_dir():
                            count += scan(apk.read(entry), entry.filename, cache)
                    for abi in present:
                        for required in (
                            "libpython.so",
                            "libpython.zip.so",
                            "libffmpeg.so",
                            "libffmpeg.zip.so",
                            "libffprobe.so",
                            "libaria2c.so",
                            "libaria2c.zip.so",
                            "libmmkv.so",
                            "libqjs.so",
                        ):
                            if f"lib/{abi}/{required}" not in apk.namelist():
                                raise ValueError(
                                    f"Missing native feature payload: {abi}/{required}"
                                )
                        for entry in source.infolist():
                            if (
                                not entry.filename.startswith(f"jni/{abi}/")
                                or entry.is_dir()
                            ):
                                continue
                            actual = apk.read(entry.filename.replace("jni/", "lib/", 1))
                            original = source.read(entry)
                            if entry.filename.endswith("/libffmpeg.zip.so"):
                                key = (abi, digest(actual))
                                if key not in comparisons:
                                    comparisons[key] = compare_ffmpeg(
                                        original, actual, abi, expected
                                    )
                            elif original != actual:
                                raise ValueError(
                                    f"Non-WebP FFmpeg JNI payload changed: {entry.filename}"
                                )
                    records.append(
                        {
                            "file": str(path),
                            "abis": sorted(present),
                            "elf64_count": count,
                        }
                    )
    return {
        "apks": records,
        "ffmpeg_comparisons": list(comparisons.values()),
        "all_native_64bit_aligned": True,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk-root", type=Path, default=Path("app/build/outputs/apk"))
    parser.add_argument(
        "--reference-file",
        type=Path,
        default=Path("app/build/webp16k/upstream-aar.txt"),
    )
    parser.add_argument(
        "--manifest", type=Path, default=Path("tools/webp16k/SHA256SUMS")
    )
    parser.add_argument(
        "--flavors", nargs="+", default=["generic", "fdroid", "githubPreview"]
    )
    parser.add_argument("--build-type", choices=["debug", "release"], default="release")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    result = verify(
        args.apk_root,
        Path(args.reference_file.read_text().strip()),
        args.manifest,
        args.flavors,
        args.build_type,
    )
    if args.output:
        args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(
        f"Verified {len(result['apks'])} packaged APKs, all four ABIs, nested ELF alignment, WebP hashes, non-WebP bytes and symlinks."
    )


if __name__ == "__main__":
    main()

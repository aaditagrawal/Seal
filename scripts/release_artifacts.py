"""Stage the complete flavor/ABI matrix, then upload only verified action outputs."""

import argparse
import json
import shutil
import subprocess
from pathlib import Path

FLAVORS = ("generic", "githubPreview", "fdroid")
ABIS = {"armeabi-v7a", "arm64-v8a", "x86", "x86_64", "universal"}


def prepare(apk_root: Path, stage: Path, manifest: Path) -> None:
    if stage.exists() or manifest.exists():
        raise ValueError("Release staging must start empty")
    sources = []
    names = set()
    for flavor in FLAVORS:
        directory = apk_root / flavor / "release"
        metadata = json.loads((directory / "output-metadata.json").read_text())
        if metadata["variantName"] != f"{flavor}Release":
            raise ValueError(f"Unexpected variant for {flavor}")
        found_abis = set()
        for element in metadata["elements"]:
            filters = element["filters"]
            if len(filters) > 1 or any(f["filterType"] != "ABI" for f in filters):
                raise ValueError("Unexpected APK filters")
            abi = filters[0]["value"] if filters else "universal"
            name = element["outputFile"]
            if (
                abi not in ABIS
                or abi in found_abis
                or Path(name).name != name
                or not name.endswith(f"-{flavor}-{abi}-release.apk")
                or name in names
            ):
                raise ValueError(f"Unexpected or duplicate APK: {name}")
            source = directory / name
            if (
                source.is_symlink()
                or not source.is_file()
                or source.stat().st_size == 0
            ):
                raise ValueError(f"Missing release APK: {source}")
            names.add(name)
            found_abis.add(abi)
            sources.append(source)
        if found_abis != ABIS:
            raise ValueError(f"Incomplete ABI matrix for {flavor}: {found_abis}")
    stage.mkdir(parents=True)
    for source in sources:
        shutil.copyfile(source, stage / source.name)
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(json.dumps(sorted(names), indent=2) + "\n")
    print(f"Staged {len(sources)} release APKs")


def verify(
    stage: Path, manifest: Path, signed_files: str, apksigner: Path, upload: Path
) -> None:
    if upload.exists():
        raise ValueError("Upload directory must start empty")
    expected_paths = {
        stage / (name.removesuffix(".apk") + "-signed.apk")
        for name in json.loads(manifest.read_text())
    }
    if any(apk.is_symlink() for apk in expected_paths):
        raise ValueError("Signed APKs must not be symlinks")
    expected = {apk.resolve() for apk in expected_paths}
    reported = [Path(name).resolve() for name in signed_files.split(":") if name]
    if len(reported) != len(expected) or set(reported) != expected:
        raise ValueError(
            "Signing action outputs do not match the complete release matrix"
        )
    for apk in sorted(expected):
        if apk.is_symlink() or not apk.is_file() or apk.stat().st_size == 0:
            raise ValueError(f"Missing signed APK: {apk}")
        # A filename alone is not evidence of signing. Verify every APK before copying any.
        subprocess.run([str(apksigner), "verify", "--verbose", str(apk)], check=True)
    upload.mkdir(parents=True)
    for apk in sorted(expected):
        shutil.copyfile(apk, upload / apk.name)
    print(f"Verified {len(expected)} signed release APKs")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("operation", choices=("prepare", "verify"))
    parser.add_argument("--apk-root", type=Path, default=Path("app/build/outputs/apk"))
    parser.add_argument("--stage", type=Path, default=Path(".release/unsigned"))
    parser.add_argument("--manifest", type=Path, default=Path(".release/manifest.json"))
    parser.add_argument("--upload", type=Path, default=Path(".release/verified"))
    parser.add_argument("--signed-files", default="")
    parser.add_argument("--apksigner", type=Path)
    args = parser.parse_args()
    if args.operation == "prepare":
        prepare(args.apk_root, args.stage, args.manifest)
    else:
        if args.apksigner is None:
            parser.error("verify requires --apksigner")
        verify(
            args.stage, args.manifest, args.signed_files, args.apksigner, args.upload
        )


if __name__ == "__main__":
    main()

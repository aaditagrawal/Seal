import json
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from release_artifacts import ABIS, FLAVORS, prepare, verify


class ReleaseArtifactsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk_root = self.root / "apk"
        self.stage = self.root / "stage"
        self.manifest = self.root / "manifest.json"
        self.upload = self.root / "upload"
        for flavor in FLAVORS:
            directory = self.apk_root / flavor / "release"
            directory.mkdir(parents=True)
            elements = []
            for abi in sorted(ABIS):
                name = f"Seal-2.0.0-alpha.5-{flavor}-{abi}-release.apk"
                (directory / name).write_bytes(b"unsigned fixture")
                elements.append(
                    {
                        "outputFile": name,
                        "filters": []
                        if abi == "universal"
                        else [{"filterType": "ABI", "value": abi}],
                    }
                )
            (directory / "output-metadata.json").write_text(
                json.dumps(
                    {
                        "variantName": f"{flavor}Release",
                        "elements": elements,
                    }
                )
            )
        debug = self.apk_root / "generic" / "debug"
        debug.mkdir()
        (debug / "debug.apk").write_bytes(b"must not be signed")

    def stage_signed_fixtures(self):
        prepare(self.apk_root, self.stage, self.manifest)
        outputs = []
        for apk in self.stage.glob("*.apk"):
            signed = apk.with_name(apk.stem + "-signed.apk")
            signed.write_bytes(b"signature fixture, verification mocked")
            outputs.append(str(signed))
        # The action also leaves unsigned inputs, alignment intermediates and its key here.
        (self.stage / "key.jks").write_bytes(b"must not be uploaded")
        (self.stage / "intermediate-temp.apk").write_bytes(b"must not be uploaded")
        return outputs

    def test_all_flavors_and_abis_are_staged_and_only_verified_files_uploaded(self):
        outputs = self.stage_signed_fixtures()
        with patch("release_artifacts.subprocess.run") as run:
            verify(
                self.stage,
                self.manifest,
                ":".join(outputs),
                Path("apksigner"),
                self.upload,
            )
        self.assertEqual(15, run.call_count)
        self.assertTrue(all(call.kwargs["check"] for call in run.call_args_list))
        self.assertEqual(
            {Path(p).name for p in outputs}, {p.name for p in self.upload.iterdir()}
        )
        self.assertEqual(15, len(json.loads(self.manifest.read_text())))

    def test_missing_flavor_fails_before_staging(self):
        (self.apk_root / "fdroid/release/output-metadata.json").unlink()
        with self.assertRaises(FileNotFoundError):
            prepare(self.apk_root, self.stage, self.manifest)
        self.assertFalse(self.stage.exists())

    def test_missing_abi_and_duplicate_names_fail(self):
        metadata_file = self.apk_root / "generic/release/output-metadata.json"
        metadata = json.loads(metadata_file.read_text())
        for elements in [
            metadata["elements"][:-1],
            metadata["elements"] + metadata["elements"][:1],
        ]:
            metadata_file.write_text(json.dumps({**metadata, "elements": elements}))
            with self.assertRaises(ValueError):
                prepare(self.apk_root, self.stage, self.manifest)
            self.assertFalse(self.stage.exists())

    def test_path_escape_is_rejected(self):
        metadata_file = self.apk_root / "generic/release/output-metadata.json"
        metadata = json.loads(metadata_file.read_text())
        metadata["elements"][0]["outputFile"] = "../escape.apk"
        metadata_file.write_text(json.dumps(metadata))
        with self.assertRaises(ValueError):
            prepare(self.apk_root, self.stage, self.manifest)

    def test_incomplete_extra_duplicate_or_unsigned_action_outputs_fail(self):
        outputs = self.stage_signed_fixtures()
        for paths in [
            outputs[:-1],
            outputs + outputs[:1],
            outputs + [str(self.stage / "key.jks")],
            [p.replace("-signed.apk", ".apk") for p in outputs],
        ]:
            with self.assertRaises(ValueError):
                verify(
                    self.stage,
                    self.manifest,
                    ":".join(paths),
                    Path("apksigner"),
                    self.upload,
                )
        self.assertFalse(self.upload.exists())

    def test_invalid_signature_prevents_any_upload(self):
        outputs = self.stage_signed_fixtures()
        with (
            patch(
                "release_artifacts.subprocess.run",
                side_effect=subprocess.CalledProcessError(1, "verify"),
            ),
            self.assertRaises(subprocess.CalledProcessError),
        ):
            verify(
                self.stage,
                self.manifest,
                ":".join(outputs),
                Path("apksigner"),
                self.upload,
            )
        self.assertFalse(self.upload.exists())

    def test_signed_symlinks_are_rejected_before_verification(self):
        outputs = self.stage_signed_fixtures()
        signed = Path(outputs[0])
        signed.unlink()
        signed.symlink_to(next(self.stage.glob("*-release.apk")))
        with (
            patch("release_artifacts.subprocess.run") as run,
            self.assertRaises(ValueError),
        ):
            verify(
                self.stage,
                self.manifest,
                ":".join(outputs),
                Path("apksigner"),
                self.upload,
            )
        run.assert_not_called()
        self.assertFalse(self.upload.exists())


if __name__ == "__main__":
    unittest.main()

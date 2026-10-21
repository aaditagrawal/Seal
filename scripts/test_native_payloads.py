"""Adversarial cases that previously escaped outer-APK-only alignment checks."""

import io
import struct
import unittest
import zipfile

from verify_native_payloads import LIBRARIES, check_elf, compare_ffmpeg, digest, scan


def elf(alignment=16384, address=0):
    data = bytearray(128)
    data[:6] = b"\x7fELF\x02\x01"
    struct.pack_into(
        "<HHIQQQIHHHHHH", data, 16, 3, 62, 1, 0, 64, 0, 0, 64, 56, 1, 0, 0, 0
    )
    struct.pack_into("<IIQQQQQQ", data, 64, 1, 5, 0, address, 0, 128, 128, alignment)
    return bytes(data)


class NativePayloadTest(unittest.TestCase):
    def test_nested_archive_with_misleading_so_extension_is_inspected(self):
        nested = io.BytesIO()
        with zipfile.ZipFile(nested, "w") as archive:
            archive.writestr("usr/lib/libwebp.so", elf(4096))
        outer = io.BytesIO()
        with zipfile.ZipFile(outer, "w") as archive:
            archive.writestr("lib/x86_64/libffmpeg.zip.so", nested.getvalue())
        with self.assertRaisesRegex(ValueError, "16 KB LOAD alignment"):
            scan(outer.getvalue(), "fixture.apk", {})

    def test_alignment_label_alone_does_not_hide_bad_mapping_offsets(self):
        self.assertEqual(1, check_elf(elf(), "aligned.so"))
        with self.assertRaisesRegex(ValueError, "16 KB LOAD alignment"):
            check_elf(elf(16384, 4096), "cosmetic-alignment.so")

    def test_32bit_payload_must_remain_byte_identical(self):
        with self.assertRaisesRegex(ValueError, "32-bit FFmpeg archive changed"):
            compare_ffmpeg(b"original", b"modified", "x86", {})

    def test_non_webp_content_and_symlink_metadata_changes_are_rejected(self):
        def archive(rebuilt=False, corrupt_other=False, lose_symlink=False):
            stream = io.BytesIO()
            with zipfile.ZipFile(stream, "w") as result:
                for library in sorted(LIBRARIES):
                    result.writestr(
                        "usr/lib/" + library, b"rebuilt" if rebuilt else b"old"
                    )
                result.writestr(
                    "usr/lib/other.so.1.0", b"changed" if corrupt_other else b"other"
                )
                link = zipfile.ZipInfo("usr/lib/other.so.1")
                link.create_system = 3
                link.external_attr = (0o100644 if lose_symlink else 0o120777) << 16
                result.writestr(link, "other.so.1.0")
            return stream.getvalue()

        expected = {
            f"overlay/x86_64/{library}": digest(b"rebuilt") for library in LIBRARIES
        }
        original = archive()
        self.assertEqual(
            1,
            compare_ffmpeg(original, archive(True), "x86_64", expected)[
                "symlinks_preserved"
            ],
        )
        with self.assertRaisesRegex(ValueError, "Unexpected FFmpeg content change"):
            compare_ffmpeg(
                original, archive(True, corrupt_other=True), "x86_64", expected
            )
        with self.assertRaisesRegex(ValueError, "Unix/symlink metadata changed"):
            compare_ffmpeg(
                original, archive(True, lose_symlink=True), "x86_64", expected
            )


if __name__ == "__main__":
    unittest.main()

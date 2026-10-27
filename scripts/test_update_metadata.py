import importlib.util
import tempfile
import unittest
from pathlib import Path

spec = importlib.util.spec_from_file_location("update_metadata", Path(__file__).with_name("create-update-metadata.py"))
metadata = importlib.util.module_from_spec(spec)
spec.loader.exec_module(metadata)


class MetadataTest(unittest.TestCase):
    def test_metadata_binds_actual_apk_bytes_and_android_version(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory, "naiwa.apk")
            apk.write_bytes(b"abc")
            badging = "package: name='com.naiwa.androidpet' versionCode='7' versionName='0.7.0'"
            result = metadata.make_manifest(apk, badging, "v0.7.0", "说明")
            self.assertEqual(7, result["versionCode"])
            self.assertEqual(3, result["size"])
            self.assertEqual("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", result["sha256"])

    def test_rejects_wrong_application_id(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory, "naiwa.apk")
            apk.write_bytes(b"abc")
            with self.assertRaises(ValueError):
                metadata.make_manifest(apk, "package: name='other' versionCode='7' versionName='0.7'", "v0.7", "")

    def test_output_cannot_overwrite_the_apk(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory, "naiwa.apk")
            apk.write_bytes(b"abc")
            with self.assertRaises(ValueError):
                metadata.write_manifest(apk, apk, {"versionCode": 7})
            self.assertEqual(b"abc", apk.read_bytes())

    def test_rejects_metadata_larger_than_client_limit(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory, "naiwa.apk")
            output = Path(directory, "naiwa-update.json")
            apk.write_bytes(b"abc")
            with self.assertRaises(ValueError):
                metadata.write_manifest(output, apk, {"notes": "蛙" * 65536})
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()

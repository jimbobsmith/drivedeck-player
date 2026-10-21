import importlib.util
from pathlib import Path
import tempfile
import unittest
import zipfile

spec = importlib.util.spec_from_file_location("release", Path(__file__).with_name("prepare-release.py"))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseContractTest(unittest.TestCase):
    def test_missing_or_wrong_signature_fails(self):
        for text in ("", "Number of signers: 2", "Number of signers: 1\nSigner #1 certificate SHA-256 digest: " + "b" * 64):
            with self.assertRaises(ValueError):
                release.verified_signer(text, "a" * 64)

    def test_only_a_verified_single_matching_signer_is_accepted(self):
        text = "Number of signers: 1\nSigner #1 certificate SHA-256 digest: " + "a" * 64 + "\nVerified using v2 scheme (APK Signature Scheme v2): true\n"
        self.assertEqual("a" * 64, release.verified_signer(text, "a" * 64))
        with self.assertRaises(ValueError):
            release.verified_signer(text.replace("true", "false"), "a" * 64)

    def test_badging_is_taken_from_actual_package_metadata(self):
        metadata = release.parse_badging("package: name='com.drivedeck.player.test' versionCode='204' versionName='0.1.0-alpha02'\nminSdkVersion:'23'\n")
        self.assertEqual(204, metadata["versionCode"])
        legacy = release.parse_badging("package: name='com.drivedeck.player.test' versionCode='204' versionName='0.1.0-alpha02'\nsdkVersion:'23'\n")
        self.assertEqual(metadata, legacy)
        with self.assertRaises(ValueError):
            release.parse_badging("package: name='x' versionCode='0' versionName='x'\nsdkVersion:'30'\n")

    def test_manifest_refuses_wrong_channel_and_incomplete_universal_archive(self):
        with tempfile.TemporaryDirectory(prefix="dd-updater-") as directory:
            apk = Path(directory) / "Player-universal-debug.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("lib/x86_64/fixture.so", b"x" * 1_000_000)
            metadata = {"packageName": "com.drivedeck.player.test", "versionCode": 204, "versionName": "0.1.0-alpha02"}
            for tag in ("player-release-v2", "player-test-v2"):
                with self.assertRaises(ValueError):
                    release.make_manifest(apk, metadata, tag, "a" * 40)


if __name__ == "__main__":
    unittest.main()

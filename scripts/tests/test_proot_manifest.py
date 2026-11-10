import copy
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))

from check_proot_manifest import validate_manifest


class ProotManifestTests(unittest.TestCase):
    def load(self):
        return json.loads(
            (ROOT / "third_party" / "proot-android.lock.json").read_text(encoding="utf-8")
        )

    def test_repository_lock_is_valid(self):
        self.assertEqual([], validate_manifest(self.load()))

    def test_proot_version_must_match_android_reference_build(self):
        document = self.load()
        document["components"][0]["version"] = "5.3.0"
        self.assertTrue(any("proot version" in error.lower() for error in validate_manifest(document)))

    def test_invalid_source_digest_is_rejected(self):
        document = self.load()
        document["components"][0]["source_sha256"] = "1234"
        self.assertTrue(any("full sha-256" in error.lower() for error in validate_manifest(document)))

    def test_cannot_claim_binary_bundled_or_automatic_download(self):
        document = self.load()
        document["policy"]["binaries_bundled"] = True
        self.assertTrue(any("must not imply binaries" in error for error in validate_manifest(document)))

    def test_source_lock_cannot_claim_release_gate_passed(self):
        document = self.load()
        document["release_gate"]["ndk_build_passed"] = True
        self.assertTrue(any("release gate" in error.lower() for error in validate_manifest(document)))

    def test_talloc_license_difference_is_preserved_explicitly(self):
        document = self.load()
        talloc = next(item for item in document["components"] if item["id"] == "libtalloc")
        talloc["source_license"] = "GPL-3.0"
        self.assertTrue(any("libtalloc license" in error.lower() for error in validate_manifest(document)))


if __name__ == "__main__":
    unittest.main()

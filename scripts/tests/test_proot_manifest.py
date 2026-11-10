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

    def test_repository_lock_records_ci_build_and_open_device_gates(self):
        document = self.load()
        self.assertEqual([], validate_manifest(document))
        self.assertTrue(document["release_gate"]["ndk_build_passed"])
        self.assertFalse(document["release_gate"]["physical_android_execve_ptrace_test_passed"])

    def test_proot_version_must_match_android_reference_build(self):
        document = self.load()
        document["components"][0]["version"] = "5.3.0"
        self.assertTrue(any("proot version" in error.lower() for error in validate_manifest(document)))

    def test_invalid_source_digest_is_rejected(self):
        document = self.load()
        document["components"][0]["source_sha256"] = "1234"
        self.assertTrue(any("full sha-256" in error.lower() for error in validate_manifest(document)))

    def test_binary_sources_cannot_be_checked_into_repository(self):
        document = self.load()
        document["policy"]["binary_sources_committed"] = True
        self.assertTrue(any("checked-in" in error for error in validate_manifest(document)))

    def test_automatic_downloads_must_remain_disabled(self):
        document = self.load()
        document["policy"]["automatic_downloads"] = True
        self.assertTrue(any("runtime downloads" in error for error in validate_manifest(document)))

    def test_source_lock_cannot_claim_physical_device_gate_passed(self):
        document = self.load()
        document["release_gate"]["physical_android_execve_ptrace_test_passed"] = True
        self.assertTrue(any("release gate must remain open" in error for error in validate_manifest(document)))

    def test_missing_component_returns_error_without_crashing(self):
        document = self.load()
        document["components"] = [
            item for item in document["components"] if item["id"] != "proot"
        ]
        errors = validate_manifest(document)
        self.assertTrue(any("exactly proot" in error for error in errors))

    def test_duplicate_component_ids_are_rejected(self):
        document = self.load()
        document["components"].append(copy.deepcopy(document["components"][0]))
        errors = validate_manifest(document)
        self.assertTrue(any("duplicate component id" in error for error in errors))

    def test_talloc_license_difference_is_preserved_explicitly(self):
        document = self.load()
        talloc = next(item for item in document["components"] if item["id"] == "libtalloc")
        talloc["source_license"] = "GPL-3.0"
        self.assertTrue(any("libtalloc license" in error.lower() for error in validate_manifest(document)))


if __name__ == "__main__":
    unittest.main()

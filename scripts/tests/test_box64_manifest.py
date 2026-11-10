import copy
import unittest

from scripts.check_box64_manifest import validate_manifest

VALID = {
    "schema_version": 1,
    "status": "pinned_source_build_recipe",
    "component": {
        "id": "box64",
        "version": "0.4.4",
        "upstream_url": "https://github.com/ptitSeb/box64",
        "source_commit": "2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a",
        "source_pin_type": "immutable_git_commit",
        "license": "MIT",
    },
    "target": {
        "operating_system": "Linux",
        "architecture": "aarch64",
        "libc": "glibc",
        "android_abi_artifact": False,
        "installed_binary": "/usr/local/bin/box64",
        "metadata_file": "/usr/local/share/vyrox/box64.properties",
    },
    "build": {
        "recipe": "runtime-engine/src/main/kotlin/com/vyrox/engine/Box64BuildPlan.kt",
        "execution": "PRoot inside the selected Capsule ARM64 RootFS with fake-root guest identity",
        "parallel_jobs": 2,
        "cmake_options": [
            "-DCMAKE_BUILD_TYPE=Release",
            "-DCMAKE_C_COMPILER=gcc",
            "-DCMAKE_INSTALL_PREFIX=/usr/local",
            "-DARM64=1",
            "-DARM_DYNAREC=ON",
            "-DBAD_SIGNAL=ON",
        ],
    },
    "verification": {
        "source_commit_checked": True,
        "reported_version_checked": True,
        "installed_elf_machine": "AArch64",
        "build_on_physical_android_device": False,
        "box64_x86_64_linux_execution_tested": False,
        "windows_pe_execution_tested": False,
    },
    "release_gates": {
        "box64_source_build_on_physical_device": False,
        "x86_64_linux_smoke_test_passed": False,
        "wine_wow64_integrated": False,
        "windows_exe_execution_passed": False,
        "display_audio_input_bridges_integrated": False,
        "direct3d_translation_integrated": False,
    },
    "notes": ["Box64 does not load Windows PE files."],
}


class Box64ManifestTests(unittest.TestCase):
    def test_accepts_valid_immutable_source_manifest(self):
        self.assertEqual([], validate_manifest(copy.deepcopy(VALID)))

    def test_rejects_mutable_or_incorrect_source_revision(self):
        manifest = copy.deepcopy(VALID)
        manifest["component"]["source_commit"] = "main"
        self.assertTrue(any("immutable" in error or "commit" in error for error in validate_manifest(manifest)))

    def test_rejects_android_as_guest_binary_target(self):
        manifest = copy.deepcopy(VALID)
        manifest["target"]["android_abi_artifact"] = True
        self.assertTrue(any("Android ABI artifact" in error for error in validate_manifest(manifest)))

    def test_rejects_unverified_device_or_windows_claims(self):
        manifest = copy.deepcopy(VALID)
        manifest["verification"]["windows_pe_execution_tested"] = True
        self.assertTrue(any("Windows PE" in error for error in validate_manifest(manifest)))

    def test_rejects_release_gate_claims_without_evidence(self):
        manifest = copy.deepcopy(VALID)
        manifest["release_gates"]["windows_exe_execution_passed"] = True
        self.assertTrue(any("release gate" in error for error in validate_manifest(manifest)))


if __name__ == "__main__":
    unittest.main()

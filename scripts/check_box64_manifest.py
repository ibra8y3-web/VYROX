#!/usr/bin/env python3
"""Validate VYROX's immutable Box64 source-build manifest."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

COMMIT = re.compile(r"^[0-9a-f]{40}$")
EXPECTED_COMMIT = "2f130fab1d6e1a4ee8a71dc60cfdfcc839ad192a"
EXPECTED_OPTIONS = {
    "-DCMAKE_BUILD_TYPE=Release",
    "-DCMAKE_C_COMPILER=gcc",
    "-DCMAKE_INSTALL_PREFIX=/usr/local",
    "-DARM64=1",
    "-DARM_DYNAREC=ON",
    "-DBAD_SIGNAL=ON",
}


def validate_manifest(document: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if document.get("schema_version") != 1:
        errors.append("schema_version must be 1")
    if document.get("status") != "pinned_source_build_recipe":
        errors.append("Box64 lock must describe a source-build recipe, not an installed binary")

    component = document.get("component")
    if not isinstance(component, dict):
        errors.append("component object is required")
        component = {}
    if component.get("id") != "box64":
        errors.append("component.id must be box64")
    if component.get("version") != "0.4.4":
        errors.append("component.version must be 0.4.4")
    if component.get("source_commit") != EXPECTED_COMMIT:
        errors.append("Box64 source commit must match the reviewed v0.4.4 commit")
    if not COMMIT.fullmatch(str(component.get("source_commit", ""))):
        errors.append("Box64 source_commit must be a full immutable Git SHA")
    if component.get("source_pin_type") != "immutable_git_commit":
        errors.append("Box64 must be pinned to an immutable Git commit")
    if component.get("license") != "MIT":
        errors.append("Box64 license must be MIT")
    if not str(component.get("upstream_url", "")).startswith("https://github.com/ptitSeb/box64"):
        errors.append("Box64 upstream URL must identify the official HTTPS repository")

    target = document.get("target")
    if not isinstance(target, dict):
        errors.append("target object is required")
        target = {}
    if target.get("operating_system") != "Linux":
        errors.append("target operating_system must be Linux")
    if target.get("architecture") != "aarch64":
        errors.append("target architecture must be aarch64")
    if target.get("libc") != "glibc":
        errors.append("target libc must be glibc")
    if target.get("android_abi_artifact") is not False:
        errors.append("Box64 guest executable must not be represented as an Android ABI artifact")
    if target.get("installed_binary") != "/usr/local/bin/box64":
        errors.append("unexpected Box64 install path")
    if target.get("metadata_file") != "/usr/local/share/vyrox/box64.properties":
        errors.append("unexpected Box64 metadata path")

    build = document.get("build")
    if not isinstance(build, dict):
        errors.append("build object is required")
        build = {}
    if build.get("recipe") != "runtime-engine/src/main/kotlin/com/vyrox/engine/Box64BuildPlan.kt":
        errors.append("build recipe path does not match the implementation")
    if build.get("parallel_jobs") != 2:
        errors.append("Box64 build must use the bounded two-job default")
    options = build.get("cmake_options")
    if not isinstance(options, list) or not EXPECTED_OPTIONS.issubset(set(options)):
        errors.append("CMake options must include the reviewed ARM64, dynarec, BAD_SIGNAL, compiler and install settings")
    if build.get("execution") != "PRoot inside the selected Capsule ARM64 RootFS with fake-root guest identity":
        errors.append("Box64 must be built inside the selected RootFS, not Android")

    verification = document.get("verification")
    if not isinstance(verification, dict):
        errors.append("verification object is required")
        verification = {}
    if verification.get("source_commit_checked") is not True:
        errors.append("source commit validation is required")
    if verification.get("reported_version_checked") is not True:
        errors.append("binary version validation is required")
    if verification.get("installed_elf_machine") != "AArch64":
        errors.append("the installed ELF must be verified as AArch64")
    if verification.get("build_on_physical_android_device") is not False:
        errors.append("source manifest cannot claim a physical-device build without evidence")
    if verification.get("box64_x86_64_linux_execution_tested") is not False:
        errors.append("source manifest cannot claim an unperformed x86-64 execution test")
    if verification.get("windows_pe_execution_tested") is not False:
        errors.append("Box64 must not be described as a tested Windows PE executor")

    gates = document.get("release_gates")
    if not isinstance(gates, dict):
        errors.append("release_gates object is required")
    elif any(value is True for value in gates.values()):
        errors.append("No Box64/Windows release gate can be marked complete without actual device evidence")

    notes = document.get("notes")
    if not isinstance(notes, list) or not any("does not load Windows PE" in note for note in notes):
        errors.append("manifest must state that Box64 does not load Windows PE files")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=Path("third_party/box64-0.4.4.lock.json"))
    args = parser.parse_args()
    try:
        manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"Could not read Box64 manifest: {exc}")
        return 2

    errors = validate_manifest(manifest)
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print("Box64 manifest valid: v0.4.4 immutable source pinned; no Android binary or Windows execution claimed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

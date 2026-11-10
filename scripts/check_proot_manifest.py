#!/usr/bin/env python3
"""Validate the pinned Android PRoot source and CI build evidence."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any

SHA256 = re.compile(r"^[0-9a-f]{64}$")
COMMIT = re.compile(r"^[0-9a-f]{40}$")
EXPECTED = {
    "proot": ("5.1.107.102", "GPL-2.0", "pinned_source_built_for_apk_in_ci"),
    "libtalloc": ("2.4.2", "LGPL-3.0-or-later", "pinned_source_built_static_in_ci_license_review_required"),
    "libandroid-shmem": ("0.7", "BSD-3-Clause", "pinned_source_built_static_in_ci"),
}


def validate_manifest(document: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if document.get("schema_version") != 1:
        errors.append("schema_version must be 1")
    if document.get("status") != "ci_built_and_elf_checked_not_device_verified":
        errors.append("status must distinguish the CI build from physical-device verification")
    if document.get("target_host_abi") != "arm64-v8a":
        errors.append("target_host_abi must be arm64-v8a")

    policy = document.get("policy")
    if not isinstance(policy, dict):
        errors.append("policy object is required")
    else:
        if policy.get("binary_sources_committed") is not False:
            errors.append("native binaries must remain generated build outputs, not checked-in source files")
        if policy.get("apk_runtime_payload_generated_by_ci") is not True:
            errors.append("CI must explicitly record APK native payload generation")
        if policy.get("automatic_downloads") is not False:
            errors.append("source lock must not imply runtime downloads on the phone")
        if policy.get("require_physical_device_ptrace_test") is not True:
            errors.append("a physical Android PRoot test remains a release requirement")

    build_notes = document.get("build_notes")
    if not isinstance(build_notes, dict):
        errors.append("build_notes object is required")
    else:
        if not COMMIT.fullmatch(str(build_notes.get("termux_packages_commit", ""))):
            errors.append("termux_packages_commit must be a full commit SHA")
        if build_notes.get("proot_unbundle_loader") is not False:
            errors.append("PRoot loader must remain separately packaged until device-tested")
        if "does not verify Android execve/ptrace" not in str(build_notes.get("note", "")):
            errors.append("build notes must separate CI build success from device execution")

    components = document.get("components")
    if not isinstance(components, list):
        errors.append("components list is required")
        return errors

    by_id: dict[str, dict[str, Any]] = {}
    for index, item in enumerate(components):
        if not isinstance(item, dict):
            errors.append(f"components[{index}] must be an object")
            continue
        component_id = item.get("id")
        if not isinstance(component_id, str) or not component_id:
            errors.append(f"components[{index}].id is required")
            continue
        if component_id in by_id:
            errors.append(f"duplicate component id: {component_id}")
            continue
        by_id[component_id] = item

    if set(by_id) != set(EXPECTED):
        errors.append("components must contain exactly proot, libtalloc, and libandroid-shmem")

    for component_id, (version, license_name, expected_state) in EXPECTED.items():
        item = by_id.get(component_id)
        if item is None:
            continue
        if item.get("version") != version:
            errors.append(f"{component_id} version does not match the pinned source")
        license_value = item.get("source_license", item.get("license"))
        if license_value != license_name:
            errors.append(f"{component_id} license declaration does not match the reviewed source")
        if not SHA256.fullmatch(str(item.get("source_sha256", ""))):
            errors.append(f"{component_id} must have a full SHA-256 source digest")
        if not str(item.get("source_url", "")).startswith("https://"):
            errors.append(f"{component_id} source URL must use HTTPS")
        if item.get("state") != expected_state:
            errors.append(f"{component_id} state does not match the documented CI build evidence")

    proot_item = by_id.get("proot")
    if proot_item is not None and proot_item.get("source_ref") != "v5.1.107.102":
        errors.append("PRoot source_ref must be pinned to the Android Termux release tag")

    gate = document.get("release_gate")
    if not isinstance(gate, dict):
        errors.append("release_gate object is required")
    else:
        for required in (
            "ndk_build_passed",
            "abi_elf_checked",
            "static_dependencies_linked",
            "proot_loader_built",
            "proot_loader_packaged",
            "forbidden_dynamic_dependencies_absent",
            "runtime_package_sha256_embedded_in_build_info",
        ):
            if gate.get(required) is not True:
                errors.append(f"CI evidence is missing for completed gate: {required}")
        for pending in (
            "complete_dynamic_dependency_audit",
            "license_notices_reviewed",
            "physical_android_execve_ptrace_test_passed",
            "linux_rootfs_shell_execution_passed",
        ):
            if gate.get(pending) is not False:
                errors.append(f"release gate must remain open until evidence exists: {pending}")

    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=Path("third_party/proot-android.lock.json"),
    )
    args = parser.parse_args()
    try:
        document = json.loads(args.manifest.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        print(f"Could not read PRoot source lock: {exc}")
        return 2

    errors = validate_manifest(document)
    if errors:
        for error in errors:
            print(f"ERROR: {error}")
        return 1
    print("PRoot lock valid: pinned ARM64 sources built and ELF-checked in CI; physical-device gates remain open.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

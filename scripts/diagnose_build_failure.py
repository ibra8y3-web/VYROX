#!/usr/bin/env python3
"""Create a deterministic diagnostic report from a VYROX Gradle build log."""

from __future__ import annotations

import argparse
import re
from pathlib import Path
from typing import Any


ERROR_PATTERNS = [
    re.compile(r"^e:\s+file://", re.IGNORECASE),
    re.compile(r"\berror:", re.IGNORECASE),
    re.compile(r"\bfatal error:", re.IGNORECASE),
    re.compile(r"\bCMake Error", re.IGNORECASE),
    re.compile(r"\bAAPT: error", re.IGNORECASE),
    re.compile(r"\bExecution failed for task", re.IGNORECASE),
    re.compile(r"\bCould not (?:find|resolve)", re.IGNORECASE),
    re.compile(r"\bThere were failing tests", re.IGNORECASE),
    re.compile(r"\bLint found", re.IGNORECASE),
    re.compile(r"\bManifest merger failed", re.IGNORECASE),
]

CATEGORY_RULES: list[tuple[str, tuple[str, ...], str]] = [
    (
        "Kotlin source compilation",
        (r"^e:\s+file://", r"Compilation error", r"> Task .*compile.*Kotlin FAILED"),
        "Open the first Kotlin source location shown in the report. Fix the source-level compiler error and add or update a unit test when practical.",
    ),
    (
        "Native CMake / NDK compilation",
        (r"CMake Error", r"ninja:", r"clang\+\+", r"\.cpp:", r"\.c:"),
        "Check the failing CMake target, NDK version, ABI, native source location, and required headers or libraries. Do not disable native compilation to hide the error.",
    ),
    (
        "Dependency resolution",
        (r"Could not (?:find|resolve)", r"No matching variant", r"Could not GET", r"Could not HEAD"),
        "Verify dependency coordinates, repository configuration, plugin compatibility, and network availability. Keep versions pinned and do not replace a dependency with an unrelated artifact.",
    ),
    (
        "Android resources or manifest",
        (r"AAPT: error", r"Android resource linking failed", r"Manifest merger failed", r"resource .* not found"),
        "Check the referenced resource name, resource qualifier, manifest declaration, and the minimum supported Android API level.",
    ),
    (
        "Android lint",
        (r"Lint found", r"NewApi", r"lintVital", r"\bLint\b.*\berror"),
        "Open the lint report and fix the source or resource compatibility issue. Do not turn off lint or suppress a warning without a justified, narrow annotation.",
    ),
    (
        "Unit tests",
        (r"There were failing tests", r"\btests? failed\b", r"\bAssertionError\b", r"\bexpected:<"),
        "Inspect the failing test name and assertion. Decide whether the implementation or the expected behavior is wrong before changing the test.",
    ),
    (
        "Gradle configuration",
        (r"Could not find method", r"Could not get unknown property", r"Script compilation error", r"Unresolved reference"),
        "Check the Gradle Kotlin DSL block scope, plugin versions, and the DSL supported by the pinned Gradle and Android Gradle Plugin versions.",
    ),
]

TASK_PATTERN = re.compile(r"> Task (:[^\s]+) FAILED")
KOTLIN_LOCATION_PATTERN = re.compile(r"^e:\s+file://([^:]+):(\d+):(\d+)\s+(.*)$")


def classify_failure(log_text: str) -> dict[str, Any]:
    """Classify a build log and return the strongest available error clues."""
    lines = [line.rstrip() for line in log_text.splitlines()]
    non_empty = [line for line in lines if line.strip()]
    failed_tasks = TASK_PATTERN.findall(log_text)

    category = "Unknown build failure"
    suggestion = (
        "Read the first concrete error above the final Gradle exception. "
        "The final BUILD FAILED line is usually a summary, not the root cause."
    )

    for candidate, patterns, advice in CATEGORY_RULES:
        if any(re.search(pattern, log_text, re.IGNORECASE | re.MULTILINE) for pattern in patterns):
            category = candidate
            suggestion = advice
            break

    error_lines = [
        line for line in non_empty
        if any(pattern.search(line) for pattern in ERROR_PATTERNS)
    ]
    if not error_lines:
        error_lines = [
            line for line in non_empty
            if any(token in line.lower() for token in ("failed", "exception", "failure"))
        ]

    root_error = error_lines[0] if error_lines else (
        non_empty[-1] if non_empty else "No build log was produced."
    )

    kotlin_locations = []
    for line in non_empty:
        match = KOTLIN_LOCATION_PATTERN.search(line)
        if match:
            kotlin_locations.append(
                {
                    "file": match.group(1),
                    "line": int(match.group(2)),
                    "column": int(match.group(3)),
                    "message": match.group(4),
                }
            )

    return {
        "category": category,
        "suggestion": suggestion,
        "root_error": root_error,
        "error_lines": error_lines[:20],
        "failed_tasks": list(dict.fromkeys(failed_tasks)),
        "kotlin_locations": kotlin_locations[:10],
        "tail": non_empty[-35:],
    }


def build_report(log_text: str, run_url: str | None = None) -> str:
    """Render a Markdown report without executing or interpreting log content."""
    result = classify_failure(log_text)
    lines = [
        "# VYROX build failure diagnostic",
        "",
        "> This report is generated from build output. It is a diagnostic aid, not an automatic code patch.",
        "",
        f"**Failure category:** {result['category']}",
    ]
    if run_url:
        lines.extend(["", f"**Workflow run:** {run_url}"])

    lines.extend(["", "## First error clue", "", "~~~text", result["root_error"], "~~~"])

    if result["failed_tasks"]:
        lines.extend(["", "## Failed Gradle tasks", ""])
        lines.extend(f"- {task}" for task in result["failed_tasks"])

    if result["kotlin_locations"]:
        lines.extend(["", "## Kotlin source locations", ""])
        for item in result["kotlin_locations"]:
            lines.append(
                f"- {item['file']}:{item['line']}:{item['column']} — {item['message']}"
            )

    if result["error_lines"]:
        lines.extend(["", "## Error lines", ""])
        lines.extend(f"- {line[:500]}" for line in result["error_lines"])

    lines.extend(["", "## Suggested next check", "", result["suggestion"]])
    lines.extend(["", "## Last build log lines", "", "~~~text"])
    lines.extend(result["tail"])
    lines.extend(["~~~", ""])
    return "\n".join(lines)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True, help="Path to the captured Gradle log.")
    parser.add_argument("--output", type=Path, required=True, help="Destination Markdown report.")
    parser.add_argument("--run-url", default=None, help="Optional GitHub Actions run URL.")
    args = parser.parse_args()

    try:
        log_text = args.input.read_text(encoding="utf-8", errors="replace")
    except OSError as exc:
        log_text = f"Build log could not be read: {exc}"

    report = build_report(log_text, run_url=args.run_url)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(report, encoding="utf-8")
    print(report)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

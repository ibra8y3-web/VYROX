import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from diagnose_build_failure import build_report, classify_failure


class DiagnoseBuildFailureTests(unittest.TestCase):
    def test_classifies_kotlin_compiler_location(self):
        log = (
            "e: file:///home/runner/work/VYROX/app/src/main/java/Main.kt:42:17 "
            "Unresolved reference: something\n"
            "BUILD FAILED in 10s\n"
        )
        result = classify_failure(log)

        self.assertEqual(result["category"], "Kotlin source compilation")
        self.assertEqual(result["kotlin_locations"][0]["line"], 42)
        self.assertIn("Main.kt", result["root_error"])

    def test_successful_kotlin_task_does_not_mask_a_test_failure(self):
        result = classify_failure(
            "> Task :app:compileDebugKotlin UP-TO-DATE\\n"
            "There were failing tests. See the report at: build/reports/tests"
        )
        self.assertEqual(result["category"], "Unit tests")

    def test_classifies_dependency_resolution_failure(self):
        result = classify_failure(
            "Could not resolve com.example:missing:1.0.\nBUILD FAILED"
        )
        self.assertEqual(result["category"], "Dependency resolution")

    def test_classifies_native_build_failure(self):
        result = classify_failure(
            "CMake Error at CMakeLists.txt:12 (add_library):\n"
            "ninja: build stopped: subcommand failed."
        )
        self.assertEqual(result["category"], "Native CMake / NDK compilation")

    def test_lists_failed_gradle_tasks(self):
        result = classify_failure("> Task :app:compileDebugKotlin FAILED\nBUILD FAILED")
        self.assertEqual(result["failed_tasks"], [":app:compileDebugKotlin"])

    def test_missing_log_still_produces_a_useful_report(self):
        report = build_report("")
        self.assertIn("No build log was produced.", report)
        self.assertIn("Suggested next check", report)

    def test_report_includes_run_link_and_recent_log_lines(self):
        report = build_report("first line\nsecond line\nBUILD FAILED", "https://example.test/run/1")
        self.assertIn("https://example.test/run/1", report)
        self.assertIn("second line", report)


if __name__ == "__main__":
    unittest.main()

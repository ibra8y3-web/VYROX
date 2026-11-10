import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

from scripts.patch_android_shmem import patch_source


class PatchAndroidShmemTests(unittest.TestCase):
    def source_fixture(self):
        return """#include <stdio.h>
#include <stdlib.h>
#include <paths.h>
#include <pthread.h>
#include <errno.h>
#define ASHV_KEY_SYMLINK_PATH _PATH_TMP "ashv_key_%d"
void test(void) {
\t\tsprintf(symlink_path, ASHV_KEY_SYMLINK_PATH, key);
}
"""

    def test_replaces_glibc_tmp_path_with_private_tmpdir(self):
        patched = patch_source(self.source_fixture())

        self.assertNotIn("_PATH_TMP", patched)
        self.assertNotIn("#include <paths.h>", patched)
        self.assertIn('#define ASHV_KEY_SYMLINK_PATH "%s%sashv_key_%d"', patched)
        self.assertIn('getenv("TMPDIR")', patched)
        self.assertIn("ENAMETOOLONG", patched)
        self.assertIn("temporary_directory[0] != '/'", patched)

    def test_patch_is_idempotent(self):
        patched = patch_source(self.source_fixture())
        self.assertEqual(patched, patch_source(patched))

    def test_rejects_unexpected_upstream_source(self):
        with self.assertRaises(ValueError):
            patch_source("#include <stdio.h>\nint main(void) { return 0; }")


    def test_adds_string_header_to_pinned_proot_extension(self):
        from scripts.patch_android_shmem import patch_proot_memfd_source

        upstream = "#include <stdlib.h>\n#include <signal.h>\n"
        patched = patch_proot_memfd_source(upstream)
        self.assertIn("#include <string.h>", patched)
        self.assertIn("#include <stdlib.h>\n#include <string.h>", patched)
        self.assertEqual(patched, patch_proot_memfd_source(patched))

    def test_rejects_unexpected_proot_extension_source(self):
        from scripts.patch_android_shmem import patch_proot_memfd_source

        with self.assertRaises(ValueError):
            patch_proot_memfd_source("#include <stdio.h>\n")


if __name__ == "__main__":
    unittest.main()

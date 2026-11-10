#!/usr/bin/env python3
"""Patch the pinned libandroid-shmem source for an app-private Android TMPDIR.

The upstream library uses glibc's _PATH_TMP. Android/Bionic does not define
that constant. More importantly, a process-wide /tmp fallback would put
System V shared-memory coordination files outside the VYROX app sandbox.
VYROX therefore requires RuntimeProcessSupervisor to supply a private TMPDIR.
"""

from __future__ import annotations

import argparse
from pathlib import Path


OLD_DEFINE = '#define ASHV_KEY_SYMLINK_PATH _PATH_TMP "ashv_key_%d"'
NEW_DEFINE = '#define ASHV_KEY_SYMLINK_PATH "%s%sashv_key_%d"'
OLD_CALL = "sprintf(symlink_path, ASHV_KEY_SYMLINK_PATH, key);"
NEW_CALL = """const char *temporary_directory = getenv("TMPDIR");
\t\tif (temporary_directory == NULL || temporary_directory[0] != '/') {
\t\t\terrno = EINVAL;
\t\t\tpthread_mutex_unlock(&mutex);
\t\t\treturn -1;
\t\t}
\t\tsize_t temporary_directory_length = strlen(temporary_directory);
\t\tconst char *separator =
\t\t\ttemporary_directory[temporary_directory_length - 1] == '/' ? "" : "/";
\t\tint symlink_path_length = snprintf(
\t\t\tsymlink_path,
\t\t\tsizeof(symlink_path),
\t\t\tASHV_KEY_SYMLINK_PATH,
\t\t\ttemporary_directory,
\t\t\tseparator,
\t\t\t(int) key
\t\t);
\t\tif (symlink_path_length < 0 || (size_t) symlink_path_length >= sizeof(symlink_path)) {
\t\t\terrno = ENAMETOOLONG;
\t\t\tpthread_mutex_unlock(&mutex);
\t\t\treturn -1;
\t\t}"""


def patch_source(source: str) -> str:
    if NEW_DEFINE in source and 'getenv("TMPDIR")' in source and OLD_CALL not in source:
        return source

    if source.count(OLD_DEFINE) != 1:
        raise ValueError("Pinned libandroid-shmem source does not contain the expected _PATH_TMP definition.")
    if source.count(OLD_CALL) != 1:
        raise ValueError("Pinned libandroid-shmem source does not contain the expected shared-memory path call.")

    source = source.replace(OLD_DEFINE, NEW_DEFINE, 1)
    source = source.replace(OLD_CALL, NEW_CALL, 1)
    source = source.replace("#include <paths.h>\n", "")
    return source


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="Path to the extracted upstream shmem.c")
    args = parser.parse_args()

    source = args.source
    if not source.is_file():
        parser.error(f"Source file does not exist: {source}")

    original = source.read_text(encoding="utf-8")
    patched = patch_source(original)
    source.write_text(patched, encoding="utf-8")
    print(f"Prepared Android app-private TMPDIR support in {source}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

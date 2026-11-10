# Capsule backups, clones and snapshots

Capsules are app-private file workspaces with two file-backed directory trees:
- `drive_c` represents the C: directory tree.
- `drive_d` represents the D: data tree.

They are not mounted Windows filesystems, VM disks, or security sandboxes.

## Operations

- **Rename** updates Capsule metadata while preserving its stable Capsule ID and drive contents.
- **Clone** copies the C:/D: folder trees into a new Capsule ID. It does not clone processes or a Wine Prefix because Wine is not integrated.
- **Backup export** writes a versioned ZIP archive containing both drive trees and a small VYROX backup manifest.
- **Backup import** validates the manifest, path segments, duplicate Windows paths, entry counts and expanded-size limits before creating a fresh Capsule ID.
- **Snapshot** stores the same versioned backup format under the Capsule's private `snapshots/` directory and records a SHA-256 digest.
- **Restore snapshot** extracts to a staging directory first, then stages the existing C:/D: trees and swaps the new trees in. Old drive directories are retained if rollback cannot complete; they are not silently discarded.
- **Storage usage** traverses actual files and directories without following symbolic links.

## Archive safety

The ZIP reader rejects absolute paths, drive-qualified paths, parent-directory segments, invalid Windows names, duplicate case-insensitive paths, unsupported backup formats, and archives exceeding configured entry or expanded-byte limits. It creates files only inside a private staging directory. No archive content is executed.

## Limitations

A backup captures files only. It does not capture:
- processes or open file handles;
- RAM or GPU state;
- a Windows registry or Wine Prefix;
- Windows installer state or app database entries;
- Android shared-storage files that were never imported into the Capsule.

A successful backup test is not evidence that a Windows application can execute.

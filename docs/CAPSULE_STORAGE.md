# Capsule storage layout

Each newly created Capsule is a directory stored under the app-private
`filesDir/capsules/<uuid>/` root.

The initial file-backed layout is:

```text
<uuid>/
  capsule.properties
  drive_c/
    Windows/
    Program Files/
    Program Files (x86)/
    Users/
      Default/
      Public/
    Temp/
  drive_d/
    Downloads/
    Documents/
    Games/
    Data/
```

## API behavior

- `CapsuleStorage.getDriveRoot(id, 'C')` returns the canonical app-private
  directory backing the C: workspace.
- `CapsuleStorage.getDriveRoot(id, 'D')` returns the directory backing the
  D: data workspace.
- `listDirectory` lists files and folders without exposing paths outside that drive.
  Path lookup is case-insensitive, and names that differ only by letter case
  cannot be created as separate Windows entries.
- `createDirectory` creates one validated child folder.
- `importFile` copies a SAF-provided stream through a temporary file, enforces a
  2 GiB size limit, and refuses to overwrite an existing name.
- `deleteEntry` removes a selected entry only after the UI confirms the action.
- `renameEntry`, `moveEntry`, and `copyEntry` provide same-drive file operations.
- `searchEntries` performs a case-insensitive search, capped at 500 results and
  20,000 visited entries per query.
- Recursive copies reject symbolic links, are staged in a temporary folder, and
  are limited to 8 GiB / 10,000 entries before final rename.
- Other drive letters and invalid Capsule IDs are rejected.
- Capsule deletion removes that Capsule's metadata and workspace tree only.

## Boundaries

These are ordinary directories in Android app-private storage. The in-app file
explorer can browse them, create folders, import files, and delete selected
entries, rename/move/copy items, and search folders. Symbolic links are hidden
from normal browsing and are rejected by path resolution and copy operations.
They are not mounted filesystems, Windows drive letters visible to a
Windows process, a virtual hard disk, a VM, or a security sandbox. No Windows
process can use these paths until a future runtime layer explicitly maps them.

No external native engine is bundled by this change.

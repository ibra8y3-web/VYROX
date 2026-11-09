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
- Other drive letters and invalid Capsule IDs are rejected.
- Capsule deletion removes that Capsule's metadata and workspace tree only.

## Boundaries

These are ordinary directories in Android app-private storage. They are not
mounted filesystems, Windows drive letters visible to a Windows process, a
virtual hard disk, a VM, or a security sandbox. No Windows process can use
these paths until a future runtime layer explicitly maps them.

No external native engine is bundled by this change.

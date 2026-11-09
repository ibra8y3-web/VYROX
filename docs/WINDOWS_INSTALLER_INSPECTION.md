# Windows installer inspection

The importer validates file signatures after copying to a temporary file and
before moving the file into the app-private installer directory.

## EXE

The inspector requires:

- DOS `MZ` signature.
- A bounded PE header offset from the DOS header.
- `PE\0\0` signature.
- A recognized COFF machine type.
- A matching PE32 or PE32+ optional-header magic.

Recognized machine values include x86, x86-64, ARM32, and ARM64. Unknown PE machine
types and inconsistent optional-header formats are rejected instead of being
silently labeled as a supported executable.

## MSI

The inspector verifies the Compound File Binary header used by MSI databases.
The architecture is reported as unknown because the file header alone does not
reliably identify the architecture of all packages or their custom actions.

## Scope and safety

This is format inspection, not malware scanning, signature verification, or
execution. Imported installers remain stored as data and are never launched by
the importer. A valid PE header does not guarantee that the program is safe or
compatible with VYROX.

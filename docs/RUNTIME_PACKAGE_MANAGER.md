# Runtime package manager

VYROX now has a private runtime-package installation service. It is designed for
future Android-built Wine/translator packages, not arbitrary APK files.

## Package contract

A trusted package specification declares a stable package ID and version,
target Android ABI, SHA-256 archive digest, license, upstream HTTPS source,
required files, explicitly executable paths, and compressed/expanded size
ceilings.

The caller supplies both the trusted specification and archive stream. The
manager does not download files, discover untrusted URLs, or launch package
contents.

## Installation flow

1. Validate package identity, version, ABI, source URL, license, and digest.
2. Stream the archive into a private staging file while calculating SHA-256.
3. Reject a digest mismatch before extracting anything.
4. Extract into a staging directory, rejecting absolute paths, backslashes,
   drive prefixes, dot segments, duplicate entries, and Zip Slip.
5. Enforce archive size, expanded size, and entry-count limits.
6. Verify all required files and set execute permission only for paths
   explicitly declared by the trusted package specification.
7. Write metadata and atomically rename the payload into the app-private
   runtime-packages directory.

Removal verifies package metadata and the canonical directory boundary before
deleting one exact package version. Successful installation does not prove that
the package is a Windows runtime; an engine-specific binary probe and a
physical-device execution test are still required.

## Current state

The package manager and its unit tests are implemented. No Wine, Box64, FEX,
DXVK, or VKD3D-Proton binary package is downloaded or installed by VYROX.

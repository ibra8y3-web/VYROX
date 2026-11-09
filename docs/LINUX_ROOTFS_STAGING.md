# Linux RootFS staging

The runtime-engine module can import a user-provided Linux RootFS archive into a
Capsule under `linux/rootfs-arm64` or `linux/rootfs-x86_64`.

Supported formats:

- `.tar`
- `.tar.gz` / `.tgz`
- `.tar.xz` / `.txz`

The importer validates the Capsule UUID and metadata, limits the compressed
archive to 4 GiB, limits expanded files to 16 GiB and 200,000 entries, rejects
absolute/traversal paths, constrains symbolic and hard links to the staging
directory, skips device nodes/FIFOs/sockets, verifies `/etc/os-release`, and
detects the shell ELF machine type. It stages extraction before replacing an
existing architecture-specific RootFS. The archive SHA-256 is stored with
metadata.

## Dependencies

- Apache Commons Compress 1.28.0 (Apache-2.0)
- XZ for Java 1.12 (0BSD)

These libraries parse and decompress archives; they are not Windows runtime
engines.

## Current boundary

Importing a RootFS only stages files. It does not install PRoot, configure
Bionic/glibc interoperation, install Wine, add Box64/FEX, create a Wine prefix,
mount C:/D: inside Linux, or launch any process. It is not automatically trusted
or malware-scanned. A RootFS can contain executable content and should only be
imported from a source the user trusts.

# Third-party runtime sources

This folder contains source locks and validation only. It does not contain
downloaded executables, shared libraries, or opaque runtime archives.

Box64 is tracked separately in `box64-0.4.4.lock.json`; its build action targets a Linux RootFS and is not an Android APK executable. PRoot is built by CI from its own pinned source lock.\n\nEvery native component must have an immutable source revision, source digest,
license record, Android ABI build, ELF dependency audit, and physical-device
test before its status can be changed to integrated.

Run the source checks:

```bash
python3 scripts/check_proot_manifest.py\npython3 scripts/check_box64_manifest.py
python3 -m unittest discover -s scripts/tests -p "test_*.py"
```

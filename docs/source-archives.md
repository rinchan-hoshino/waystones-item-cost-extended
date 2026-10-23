# Source Archive Artifacts

This repository maintains two distinct representations of project source code:

1. **Canonical Development Repository**: Contains the complete multi-profile build system, Gradle toolchain, test suites, and project configurations necessary for compilation and development.
2. **CurseForge Source-Only Attachment**: A stripped archive designed strictly for review and reference. It is generated directly from Git-tracked source sets (`src/main/java`, `src/test/java`), runtime/mod resources (`src/main/resources`), `LICENSE`, and `META-INF/source-archive.json`. It intentionally excludes all build automation, Gradle wrapper binaries/scripts, bootstrap loaders, and external download routines.

> Note: The source archive preserves the original directory layout across all tracked source sets covering the build profiles, but it does not contain the development toolchain or standalone build harness. To compile profiles, check out the canonical repository.

### CLI Commands

- **Build source archive**:
  ```bash
  python3 tools/source_archive.py build --output build/profiles/waystones-item-cost-extended-1.1.1-sources.jar
  ```
  *(Automatically invoked during full `build_profiles.py` execution)*

- **Verify archive integrity & compliance**:
  ```bash
  python3 tools/source_archive.py verify build/profiles/waystones-item-cost-extended-1.1.1-sources.jar
  ```
  *Checks source-only contents and validates file hashes against `META-INF/source-archive.json`.*

- **Run verification test suite**:
  ```bash
  python3 -m unittest discover -s tools/tests -p "test_*.py"
  ```
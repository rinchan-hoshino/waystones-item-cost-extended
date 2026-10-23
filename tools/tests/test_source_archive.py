import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile


TOOL = Path(__file__).resolve().parents[1] / 'source_archive.py'


class SourceArchiveTest(unittest.TestCase):
    def test_verifier_rejects_build_bootstraps_in_the_uploaded_legacy_archive(self):
        # Catch passing the old whole-project archive through the release check.
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'legacy-sources.jar'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('LICENSE', 'license')
                archive.writestr('gradle/wrapper/gradle-wrapper.jar', b'compiled wrapper')
            result = subprocess.run([sys.executable, str(TOOL), 'verify', str(path)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 1, result.stderr)
            self.assertIn('gradle/wrapper/gradle-wrapper.jar', result.stderr)

    def test_verifier_rejects_compiled_code_disguised_as_a_source_resource(self):
        # Catch a binary renamed to an innocuous resource suffix.
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'sources.jar'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('common/src/main/resources/data.bin', b'\xca\xfe\xba\xbecompiled')
            result = subprocess.run([sys.executable, str(TOOL), 'verify', str(path)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 1, result.stderr)
            self.assertIn('data.bin', result.stderr)

    def test_verifier_rejects_source_changes_after_manifest_generation(self):
        # Catch replacing a source file while retaining the original hash manifest.
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'sources.jar'
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr('LICENSE', 'license')
                archive.writestr('common/src/main/java/A.java', 'class Changed {}')
                archive.writestr('META-INF/source-archive.json', json.dumps({
                    'schema': 1, 'files': {'LICENSE': '0' * 64,
                                          'common/src/main/java/A.java': '0' * 64}}))
            result = subprocess.run([sys.executable, str(TOOL), 'verify', str(path)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 1, result.stderr)
            self.assertIn('hash mismatch', result.stderr)

    def test_published_sources_preserve_code_and_resources_without_build_executables(self):
        # Catch the old whole-repository packaging that shipped Wrapper/plugin JARs.
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            files = {
                'LICENSE': b'project license\n',
                'common/src/main/java/example/Payment.java': b'package example; class Payment {}\n',
                'common/src/main/resources/icon.png': b'PNG fixture',
                'versions/legacy/src/main/java/example/Legacy.java': b'package example; class Legacy {}\n',
                'gradlew': b'#!/bin/sh\nexec java GradleWrapperMain "$@"\n',
                'gradle/wrapper/gradle-wrapper.jar': b'compiled wrapper',
                'tools/locked-maven/plugin.jar': b'compiled plugin',
                'tools/prepare_profile_dependencies.py': b'import urllib.request\n',
                'build.gradle': b"plugins { id 'java' }\n",
                'private-note.java': b'not a source-set member',
            }
            for name, data in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            subprocess.run(['git', '-C', str(root), 'add', '.'], check=True)
            output = root / 'sources.jar'
            result = subprocess.run(
                [sys.executable, str(TOOL), 'build', '--repo', str(root), '--output', str(output)],
                capture_output=True, text=True,
            )
            self.assertEqual(result.returncode, 0, result.stderr)
            with zipfile.ZipFile(output) as archive:
                self.assertEqual(set(archive.namelist()), {
                    'LICENSE',
                    'common/src/main/java/example/Payment.java',
                    'common/src/main/resources/icon.png',
                    'versions/legacy/src/main/java/example/Legacy.java',
                    'META-INF/source-archive.json',
                })
                for name in ('LICENSE', 'common/src/main/java/example/Payment.java',
                             'common/src/main/resources/icon.png',
                             'versions/legacy/src/main/java/example/Legacy.java'):
                    self.assertEqual(archive.read(name), files[name])
            first = output.read_bytes()
            subprocess.run([sys.executable, str(TOOL), 'build', '--repo', str(root),
                            '--output', str(output)], check=True, capture_output=True)
            self.assertEqual(first, output.read_bytes())


if __name__ == '__main__':
    unittest.main()

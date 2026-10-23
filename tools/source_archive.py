#!/usr/bin/env python3
"""Build the source-only attachment from tracked Java source sets and resources."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import subprocess
import sys
import zipfile


MANIFEST = 'META-INF/source-archive.json'
EXECUTABLE_SUFFIXES = {'.class', '.jar', '.zip', '.exe', '.dll', '.so', '.dylib',
                       '.sh', '.bat', '.cmd', '.ps1', '.py', '.pyc'}


def source_member(name):
    path = PurePosixPath(name)
    if path.is_absolute() or '..' in path.parts or '\\' in name:
        return False
    return name == 'LICENSE' or (
        '/src/' in name and (
            ('/main/java/' in name or '/test/java/' in name) and name.endswith('.java')
            or '/main/resources/' in name
        )
    )


def verify_archive(path):
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError('Source archive has duplicate members')
        for name in names:
            if name == MANIFEST:
                continue
            if not source_member(name) or PurePosixPath(name).suffix.lower() in EXECUTABLE_SUFFIXES:
                raise ValueError(f'Not a source attachment member: {name}')
            data = archive.read(name)
            if data.startswith((b'\xca\xfe\xba\xbe', b'PK\x03\x04', b'PK\x05\x06', b'\x7fELF', b'MZ', b'#!')):
                raise ValueError(f'Executable or nested archive content: {name}')
        if 'LICENSE' not in names or not any(name.endswith('.java') for name in names):
            raise ValueError('Source archive requires LICENSE and Java source files')
        if MANIFEST not in names:
            raise ValueError('Source archive is missing its integrity manifest')
        manifest = json.loads(archive.read(MANIFEST))
        if manifest.get('schema') != 1 or not isinstance(manifest.get('files'), dict):
            raise ValueError('Invalid source archive manifest')
        if set(manifest['files']) != set(names) - {MANIFEST}:
            raise ValueError('Source archive member set differs from its manifest')
        for name, expected in manifest['files'].items():
            if hashlib.sha256(archive.read(name)).hexdigest() != expected:
                raise ValueError(f'Source archive hash mismatch: {name}')
    return {'file': str(path), 'members': len(names),
            'sha512': hashlib.sha512(Path(path).read_bytes()).hexdigest()}


def build_archive(repo, output):
    repo, output = Path(repo).resolve(), Path(output).resolve()
    tracked = subprocess.check_output(['git', '-C', str(repo), 'ls-files', '-z']).decode().split('\0')
    payload = {}
    for name in sorted(filter(source_member, tracked)):
        path = repo / name
        if path.is_symlink():
            raise ValueError(f'Source member is a symbolic link: {name}')
        payload[name] = path.read_bytes()
    if 'LICENSE' not in payload or not any(name.endswith('.java') for name in payload):
        raise ValueError('Source archive requires LICENSE and Java source files')
    manifest = {'schema': 1, 'files': {
        name: hashlib.sha256(data).hexdigest() for name, data in payload.items()
    }}
    payload[MANIFEST] = (json.dumps(manifest, sort_keys=True, indent=2) + '\n').encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + '.tmp')
    try:
        with zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
            for name, data in sorted(payload.items()):
                info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = 0o100644 << 16
                archive.writestr(info, data)
        verify_archive(temporary)
        temporary.replace(output)
    finally:
        temporary.unlink(missing_ok=True)
    return verify_archive(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest='command', required=True)
    build = subparsers.add_parser('build')
    build.add_argument('--repo', type=Path, default=Path(__file__).resolve().parents[1])
    build.add_argument('--output', type=Path, required=True)
    verify = subparsers.add_parser('verify')
    verify.add_argument('archive', type=Path)
    args = parser.parse_args()
    try:
        result = build_archive(args.repo, args.output) if args.command == 'build' else verify_archive(args.archive)
    except (ValueError, OSError, zipfile.BadZipFile) as error:
        print(str(error), file=sys.stderr)
        return 1
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == '__main__':
    raise SystemExit(main())

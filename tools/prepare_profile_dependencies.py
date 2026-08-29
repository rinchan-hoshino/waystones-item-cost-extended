#!/usr/bin/env python3
from __future__ import annotations

import argparse
import hashlib
import json
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def download(url: str, target: Path, sha512: str) -> None:
    if target.exists() and hashlib.sha512(target.read_bytes()).hexdigest() == sha512:
        return
    target.parent.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(url) as response:
        data = response.read()
    if hashlib.sha512(data).hexdigest() != sha512:
        raise SystemExit(f"SHA-512 mismatch: {url}")
    target.write_bytes(data)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('--profile')
    args = parser.parse_args()
    matrix = json.loads((ROOT / 'support/profiles.json').read_text())
    profiles = matrix['profiles']
    if args.profile:
        profiles = [profile for profile in profiles if profile['id'] == args.profile]
        if not profiles:
            raise SystemExit(f"Unknown profile: {args.profile}")

    output = ROOT / 'build' / 'profile-dependencies'
    cache = output / 'downloads'
    for profile in profiles:
        for dependency_name in ('waystones', 'balm'):
            dependency = profile[dependency_name]
            target = output / f"{dependency_name}-{profile['id']}.jar"
            download(dependency['url'], target, dependency['sha512'])
            print(target)

        if profile['generation'] != 'shogi':
            continue
        shogi = profile['shogi']
        platform_jar = output / f"shogi-{profile['id']}.jar"
        download(shogi['url'], platform_jar, shogi['sha512'])
        with zipfile.ZipFile(platform_jar) as archive:
            nested = [name for name in archive.namelist() if name.startswith('META-INF/') and 'shogi-api-' in name and name.endswith('.jar')]
            if len(nested) != 1:
                raise SystemExit(f"Expected one nested Shogi API JAR in {platform_jar}, found {nested}")
            target = output / f"shogi-api-{profile['id']}.jar"
            target.write_bytes(archive.read(nested[0]))
            print(target)


if __name__ == '__main__':
    main()

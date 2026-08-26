#!/usr/bin/env python3
import argparse
import json
import re
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def version_tuple(value):
    return tuple(map(int, value.split('.')))


def minecraft_range(versions):
    low = min(map(version_tuple, versions))
    high = list(max(map(version_tuple, versions)))
    high[-1] += 1
    return f"[{'.'.join(map(str, low))},{'.'.join(map(str, high))})"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--rinlib-dir', type=Path, required=True)
    args = parser.parse_args()
    matrix = json.loads((ROOT / 'support/profiles.json').read_text())
    errors = []
    for profile in matrix['profiles']:
        profile_id = profile['id']
        paid = ROOT / 'build/profiles' / f"paid-waystones-{matrix['paid_waystones_version']}+{profile_id}.jar"
        rinlib = args.rinlib_dir / f"rinlib-{matrix['rinlib_version']}+{profile_id}.jar"
        for artifact in (paid, rinlib):
            if not artifact.exists():
                errors.append(f'missing {artifact}')
                continue
            with zipfile.ZipFile(artifact) as jar:
                bad_entry = jar.testzip()
                if bad_entry:
                    errors.append(f'{artifact}: corrupt entry {bad_entry}')
        if not paid.exists() or not rinlib.exists():
            continue
        with zipfile.ZipFile(paid) as jar:
            names = set(jar.namelist())
            expected_mixin = 'ItemCostMixin' if profile['generation'] == 'shogi' else 'ItemRequirementMixin'
            other_mixin = 'ItemRequirementMixin' if profile['generation'] == 'shogi' else 'ItemCostMixin'
            required = {
                'dev/rinchan/waystoneemeralds/payment/EmeraldInventory.class',
                f'dev/rinchan/waystoneemeralds/mixin/{expected_mixin}.class',
                'paid_waystones.mixins.json',
            }
            for entry in required - names:
                errors.append(f'{profile_id}: missing {entry}')
            if any(entry.endswith(f'{other_mixin}.class') for entry in names):
                errors.append(f'{profile_id}: contains adapter for the wrong generation')
            if any(entry.startswith('dev/rinchan/rinlib/') for entry in names):
                errors.append(f'{profile_id}: embeds RinLib classes')
            mixin = json.loads(jar.read('paid_waystones.mixins.json'))
            if mixin.get('mixins') != [expected_mixin]:
                errors.append(f'{profile_id}: wrong Mixin config {mixin.get("mixins")}')
            if profile['loader'] == 'fabric':
                metadata = json.loads(jar.read('fabric.mod.json'))
                if metadata['depends']['minecraft'] != profile['game_versions']:
                    errors.append(f'{profile_id}: wrong Fabric game versions')
            else:
                metadata_path = 'META-INF/mods.toml' if profile['loader'] == 'forge' or profile['minecraft'] == '1.20.4' else 'META-INF/neoforge.mods.toml'
                metadata = jar.read(metadata_path).decode()
                ranges = re.findall(r'modId="minecraft"[\s\S]*?versionRange="([^"]+)"', metadata)
                expected_range = minecraft_range(profile['game_versions'])
                if ranges != [expected_range]:
                    errors.append(f'{profile_id}: Minecraft range {ranges}, expected {expected_range}')
        with zipfile.ZipFile(rinlib) as jar:
            if 'dev/rinchan/rinlib/payment/DenominatedPayment.class' not in jar.namelist():
                errors.append(f'{profile_id}: RinLib payment planner missing')
    if errors:
        raise SystemExit('\n'.join(errors))
    print(f"verified {len(matrix['profiles'])} Paid Waystones/RinLib profile pairs")


if __name__ == '__main__':
    main()

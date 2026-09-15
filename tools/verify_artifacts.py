#!/usr/bin/env python3
"""Verify each release artifact and its external RinLib dependency."""
import argparse
import zipfile
from pathlib import Path
from verify_profile_artifacts import MATRIX, ROOT, VERSION, verify_jar

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--rinlib-dir', type=Path, required=True)
    args = parser.parse_args()
    for profile in MATRIX['profiles']:
        profile_id = profile['id']
        verify_jar(ROOT / 'build/profiles' / f'waystones-item-cost-extended-{VERSION}+{profile_id}.jar', profile)
        dependency = args.rinlib_dir / f"rinlib-{MATRIX['rinlib_version']}+{profile_id}.jar"
        with zipfile.ZipFile(dependency) as archive:
            if archive.testzip() or 'dev/rinchan/rinlib/payment/DenominatedPayment.class' not in archive.namelist():
                raise ValueError(f'Invalid RinLib payment dependency: {dependency}')
    print(f"Verified {len(MATRIX['profiles'])} release/dependency pairs")

if __name__ == '__main__':
    main()

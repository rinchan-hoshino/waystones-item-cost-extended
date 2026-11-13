#!/usr/bin/env python3
"""Verify each release artifact against its profile metadata."""
from verify_profile_artifacts import MATRIX, ROOT, VERSION, verify_jar

def main():
    for profile in MATRIX['profiles']:
        profile_id = profile['id']
        verify_jar(ROOT / 'build/profiles' / f'waystones-item-cost-extended-{VERSION}+{profile_id}.jar', profile)
    print(f"Verified {len(MATRIX['profiles'])} release artifacts")

if __name__ == '__main__':
    main()

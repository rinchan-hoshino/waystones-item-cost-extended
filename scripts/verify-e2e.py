#!/usr/bin/env python3
"""
Packaged-JAR real-runtime black-box E2E verification for Waystones Item Cost Extended 2.0.0.

Executes against the actual built release JAR (build/release/waystones-item-cost-extended-2.0.0+1.21.1-neoforge.jar)
with real upstream Waystones + Balm JARs on Minecraft 1.21.1 NeoForge:
  1. runGameTestServer (6 black-box scenarios through real WaystonesAPI.tryTeleport)
  2. ConfigScreenLifecycleTest (headless WaystonesItemCostConfigScreen init + close verification)
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROFILES_FILE = ROOT / "support" / "profiles.json"
RELEASE_DIR = ROOT / "build" / "release"


def detect_java_home(version: int) -> str:
    for env_var in (
        f"JAVA_{version}_HOME",
        f"JAVA_HOME_{version}_X64",
        f"JAVA_HOME_{version}_ARM64",
    ):
        val = os.environ.get(env_var, "").strip()
        if val and Path(val).exists():
            return val
    try:
        out = subprocess.check_output(
            ["/usr/libexec/java_home", "-v", str(version)],
            stderr=subprocess.DEVNULL,
            text=True,
        ).strip()
        if out:
            return out
    except Exception:
        pass
    return os.environ.get("JAVA_HOME", "")


def main() -> int:
    data = json.loads(PROFILES_FILE.read_text(encoding="utf-8"))
    mod_version = data["mod_version"]
    packaged_jar = RELEASE_DIR / f"waystones-item-cost-extended-{mod_version}+1.21.1-neoforge.jar"

    if not packaged_jar.exists():
        print(f"Packaged release JAR not found ({packaged_jar.name}); building 1.21.1-neoforge first...")
        subprocess.run([sys.executable, str(ROOT / "scripts" / "build-matrix.py"), "1.21.1-neoforge"], cwd=ROOT, check=True)

    if not packaged_jar.exists():
        raise FileNotFoundError(f"Expected packaged release JAR not found: {packaged_jar}")

    env = os.environ.copy()
    jh = detect_java_home(21)
    if jh:
        env["JAVA_HOME"] = jh
        env["PATH"] = f"{jh}/bin:{env.get('PATH', '')}"

    print(f"=== Running Packaged-JAR Black-Box E2E ({packaged_jar.name}) ===")
    cmd = [
        "./gradlew",
        "-Pe2e=true",
        "-Pprofile=1.21.1-neoforge",
        ":e2e-neoforge-1211:runGameTestServer",
        ":e2e-neoforge-1211:test",
        "--rerun-tasks",
    ]
    print(f"  [cmd] {' '.join(cmd)}")
    subprocess.run(cmd, cwd=ROOT, env=env, check=True)
    print("\nPackaged-JAR E2E verification PASSED.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

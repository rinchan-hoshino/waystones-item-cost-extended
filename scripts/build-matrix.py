#!/usr/bin/env python3
"""
Unified build & verification matrix for Waystones Item Cost Extended 2.1.2.
Builds and audits all 10 golden target JARs plus JUnit 5 logic tests.

Usage:
  python3 scripts/build-matrix.py                 # Run tests + build & verify all 10 targets
  python3 scripts/build-matrix.py 1.21.1-neoforge # Build & verify specific target(s)
"""

from __future__ import annotations

import hashlib
import json
import os
import shutil
import subprocess
import sys
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROFILES_FILE = ROOT / "support" / "profiles.json"
DEPS_DIR = ROOT / "build" / "profile-dependencies"
RELEASE_DIR = ROOT / "build" / "release"

FORBIDDEN_SUBSTRINGS = (
    "rinlib",
    "waystone_emeralds",
    "waystoneemeralds",
    "paid_waystones",
    "com/terraformersmc",
)

REQUIRED_ENTRIES = (
    "icon.png",
    "waystones_item_cost_extended.mixins.json",
    "waystones_item_cost_extended.client.mixins.json",
    "assets/waystones_item_cost_extended/lang/en_us.json",
    "assets/waystones_item_cost_extended/lang/zh_cn.json",
    "assets/waystones_item_cost_extended/lang/zh_tw.json",
    "assets/waystones_item_cost_extended/lang/ja_jp.json",
)


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


def sha512_file(path: Path) -> str:
    h = hashlib.sha512()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def ensure_dependencies(profile: dict) -> None:
    DEPS_DIR.mkdir(parents=True, exist_ok=True)
    dep_names = ["waystones", "balm"]
    if "lightmanscurrency" in profile:
        dep_names.append("lightmanscurrency")
    if profile["generation"] == "shogi":
        dep_names.append("shogi")

    for name in dep_names:
        dep = profile[name]
        target = DEPS_DIR / f"{name}-{profile['id']}.jar"
        if target.exists() and sha512_file(target) == dep["sha512"]:
            continue
        print(f"  [dep] Downloading {target.name}...")
        req = urllib.request.Request(
            dep["url"], headers={"User-Agent": "waystones-item-cost-extended-build/2.0.0"}
        )
        with urllib.request.urlopen(req, timeout=60) as resp, target.open("wb") as out:
            shutil.copyfileobj(resp, out)
        actual = sha512_file(target)
        if actual != dep["sha512"]:
            target.unlink(missing_ok=True)
            raise RuntimeError(f"SHA-512 mismatch for {target.name}: {actual}")

    if profile["generation"] == "shogi":
        platform_jar = DEPS_DIR / f"shogi-{profile['id']}.jar"
        api_jar = DEPS_DIR / f"shogi-api-{profile['id']}.jar"
        if not api_jar.exists():
            with zipfile.ZipFile(platform_jar) as zf:
                entry = next(
                    (
                        n
                        for n in zf.namelist()
                        if n.startswith("META-INF/")
                        and "shogi-api-" in n
                        and n.endswith(".jar")
                    ),
                    None,
                )
                if not entry:
                    raise RuntimeError(f"Missing nested shogi-api jar in {platform_jar}")
                api_jar.write_bytes(zf.read(entry))


def run_cmd(cmd: list[str], cwd: Path, java_version: int = 21) -> None:
    env = os.environ.copy()
    jh = detect_java_home(java_version)
    if jh:
        env["JAVA_HOME"] = jh
        env["PATH"] = f"{jh}/bin:{env.get('PATH', '')}"
    print(f"  [cmd] ({cwd.name}) {' '.join(cmd)}")
    subprocess.run(cmd, cwd=cwd, env=env, check=True)


def detect_gradle_9() -> str:
    dists = Path.home() / ".gradle" / "wrapper" / "dists"
    for prefix in ("gradle-9.5.1-bin", "gradle-9.3.0-bin", "gradle-9.2.1-bin", "gradle-9.8.0-bin"):
        d = dists / prefix
        if d.exists():
            matches = list(d.glob("*/gradle-*/bin/gradle"))
            if matches:
                return str(matches[0])
    return shutil.which("gradle") or str(ROOT / "gradlew")


def run_logic_tests() -> None:
    print("=== Running JUnit 5 unit & property-invariant tests ===")
    run_cmd(
        [
            "./gradlew",
            "-PlogicOnly=true",
            ":logic:clean",
            ":logic:test",
            "--tests",
            "dev.rinchan.waystonesitemcost.config.*",
            "--tests",
            "dev.rinchan.waystonesitemcost.payment.*",
            "--tests",
            "dev.rinchan.waystonesitemcost.physics.*",
        ],
        cwd=ROOT,
        java_version=21,
    )


def run_architecture_tests() -> None:
    print("\n=== Running ArchitectureContractTest against built release JARs ===")
    run_cmd(
        [
            "./gradlew",
            "-PlogicOnly=true",
            ":logic:test",
            "--tests",
            "dev.rinchan.waystonesitemcost.architecture.ArchitectureContractTest",
            "--rerun-tasks",
        ],
        cwd=ROOT,
        java_version=21,
    )


def build_profile(profile: dict, mod_version: str) -> Path:
    pid = profile["id"]
    mc = profile["minecraft"]
    loader = profile["loader"]
    gen = profile["generation"]
    java_ver = int(profile["java"])
    expected_name = f"waystones-item-cost-extended-{mod_version}+{mc}-{loader}.jar"

    print(f"\n=== Building {pid} (MC {mc}, {loader}, gen={gen}, Java {java_ver}) ===")
    ensure_dependencies(profile)

    if loader in ("forge", "neoforge"):
        module = "forge-legacy" if loader == "forge" else "neoforge-modern"
        run_cmd(
            [
                "./gradlew",
                f"-Pprofile={pid}",
                f":{module}:clean",
                f":{module}:build",
                "-x",
                "test",
            ],
            cwd=ROOT,
            java_version=21,
        )
        produced = ROOT / "build" / "profile-work" / pid / module / "libs" / expected_name
    elif pid == "26.1.2-fabric":
        fc_dir = ROOT / "fabric-cross26"
        gradle9 = detect_gradle_9()
        run_cmd(
            [
                gradle9,
                ":fabric:clean",
                ":fabric:jar",
                f"-PprofileId={pid}",
                f"-PprofileDependenciesDir={DEPS_DIR}",
            ],
            cwd=fc_dir,
            java_version=25,
        )
        produced = fc_dir / "fabric" / "build" / "libs" / expected_name
    else:
        # 1.20.1-fabric, 1.21.1-fabric, 26.2-fabric, 26.3-fabric via fabric/
        fab_dir = ROOT / "fabric"
        gradle9 = detect_gradle_9()
        is_unobf_26 = mc in ("26.2", "26.3")
        loom_ver = "1.17.20" if is_unobf_26 else "1.14.10"
        task = "jar" if is_unobf_26 else "remapJar"
        props = [
            f"-Pminecraft_version={mc}",
            f"-Ploader_version={profile['loader_version']}",
            f"-Ploom_version={loom_ver}",
            f"-Pfabric_api_version={profile['fabric_api']}",
            f"-Pwaystones_version={profile['waystones']['version_number']}",
            f"-Pbalm_version={profile['balm']['version_number']}",
            f"-Psource_generation={gen}",
            f"-Pjava_version={java_ver}",
            f"-Pmod_version={mod_version}",
            f"-Pgame_versions_json={json.dumps(profile['game_versions'])}",
            f"-PprofileId={pid}",
            f"-PprofileDependenciesDir={DEPS_DIR}",
        ]
        if gen == "shogi":
            props.append(f"-Pshogi_version={profile['shogi']['version_number']}")
        run_cmd(
            [gradle9, "clean", task] + props,
            cwd=fab_dir,
            java_version=max(21, java_ver),
        )
        libs_dir = fab_dir / "build" / ("devlibs" if is_unobf_26 else "libs")
        produced = libs_dir / expected_name
        if not produced.exists():
            # Loom remapJar may output archivesName-version.jar
            candidates = [
                p
                for p in libs_dir.glob("*.jar")
                if not p.name.endswith("-sources.jar") and not p.name.endswith("-dev.jar")
            ]
            if not candidates:
                raise FileNotFoundError(f"No JAR found in {libs_dir}")
            produced = candidates[0]

    if not produced.exists():
        raise FileNotFoundError(f"Expected JAR not produced: {produced}")

    RELEASE_DIR.mkdir(parents=True, exist_ok=True)
    dest = RELEASE_DIR / expected_name
    shutil.copy2(produced, dest)
    return dest


def audit_jar(jar_path: Path, profile: dict, mod_version: str) -> None:
    loader = profile["loader"]
    with zipfile.ZipFile(jar_path) as zf:
        names = zf.namelist()
        for name in names:
            low = name.lower()
            for bad in FORBIDDEN_SUBSTRINGS:
                if bad in low:
                    raise AssertionError(f"{jar_path.name} contains forbidden entry: {name}")

        for req in REQUIRED_ENTRIES:
            if req not in names:
                raise AssertionError(f"{jar_path.name} missing required entry: {req}")

        if loader == "fabric":
            meta = zf.read("fabric.mod.json").decode("utf-8")
            assert '"id": "waystones_item_cost_extended"' in meta, f"Bad mod id in {jar_path.name}"
            assert f'"version": "{mod_version}"' in meta, f"Bad version in {jar_path.name}"
        elif loader == "forge":
            meta = zf.read("META-INF/mods.toml").decode("utf-8")
            assert 'modId="waystones_item_cost_extended"' in meta, f"Bad mod id in {jar_path.name}"
            assert f'version="{mod_version}"' in meta, f"Bad version in {jar_path.name}"
        else:
            meta = zf.read("META-INF/neoforge.mods.toml").decode("utf-8")
            assert 'modId="waystones_item_cost_extended"' in meta, f"Bad mod id in {jar_path.name}"
            assert f'version="{mod_version}"' in meta, f"Bad version in {jar_path.name}"

        for bad in ("rinlib", "waystone_emeralds", "paid_waystones"):
            if bad in meta.lower():
                raise AssertionError(f"{jar_path.name} metadata contains forbidden token '{bad}'")

        if loader == "forge":
            assert "pack.mcmeta" in names, f"{jar_path.name} missing pack.mcmeta"
            pack_meta = json.loads(zf.read("pack.mcmeta").decode("utf-8"))
            assert (
                pack_meta.get("pack", {}).get("pack_format") == 15
            ), f"{jar_path.name} pack.mcmeta must have pack_format == 15, got {pack_meta}"
        elif "pack.mcmeta" in names:
            pack_meta = json.loads(zf.read("pack.mcmeta").decode("utf-8"))
            assert (
                pack_meta.get("pack", {}).get("pack_format") != 15
            ), f"{jar_path.name} must NOT contain pack_format == 15 pack.mcmeta"

        class_count = sum(1 for n in names if n.endswith(".class"))
        if class_count < 8:
            raise AssertionError(f"{jar_path.name} has suspiciously few classes: {class_count}")

    size_kb = jar_path.stat().st_size / 1024.0
    print(f"  [audit OK] {jar_path.name} ({size_kb:.1f} KB, sha256={sha256_file(jar_path)[:12]}...)")


def main() -> int:
    data = json.loads(PROFILES_FILE.read_text(encoding="utf-8"))
    mod_version = data["mod_version"]
    all_profiles = data["profiles"]

    requested = sys.argv[1:]
    if requested:
        profiles = [p for p in all_profiles if p["id"] in requested]
        missing = set(requested) - {p["id"] for p in profiles}
        if missing:
            print(f"Unknown profile(s): {sorted(missing)}", file=sys.stderr)
            return 2
    else:
        profiles = all_profiles
        if RELEASE_DIR.exists():
            for old_jar in RELEASE_DIR.glob("*.jar"):
                old_jar.unlink()
        run_logic_tests()

    results: list[tuple[str, Path]] = []
    for profile in profiles:
        jar_path = build_profile(profile, mod_version)
        audit_jar(jar_path, profile, mod_version)
        results.append((profile["id"], jar_path))

    run_architecture_tests()

    print("\n=== BUILD MATRIX SUMMARY ===")
    for pid, jar in results:
        print(f"  PASS  {pid:16s}  {jar.name:56s}  {jar.stat().st_size / 1024.0:6.1f} KB")
    print(f"\nAll {len(results)} target(s) built and verified cleanly.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

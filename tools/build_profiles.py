#!/usr/bin/env python3
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PROFILES_FILE = ROOT / "support" / "profiles.json"
DIST = ROOT / "build" / "profiles"
LOGS = ROOT / "build" / "profile-logs"
HOME = Path.home()
GRADLE_8 = ROOT / "gradlew"
GRADLE_9_2 = Path(os.environ.get("GRADLE_9_2", HOME / ".local/gradle-9.2.1/bin/gradle"))
GRADLE_9_5 = Path(os.environ.get("GRADLE_9_5", HOME / ".local/gradle-9.5.1/bin/gradle"))
JDK = {
    17: Path(os.environ.get("JAVA_21_HOME", HOME / ".local/jdk-21")),
    21: Path(os.environ.get("JAVA_21_HOME", HOME / ".local/jdk-21")),
    25: Path(os.environ.get("JAVA_25_HOME", HOME / ".local/jdk-25")),
}


def module_for(profile):
    loader = profile["loader"]
    mc = tuple(map(int, profile["minecraft"].split(".")))
    if loader == "fabric":
        return "fabric"
    if loader == "forge":
        return "forge-legacy" if mc < (1, 21, 1) else "forge-modern"
    if loader == "neoforge":
        return "neoforge-legacy" if mc < (1, 21) else "neoforge-modern"
    raise ValueError(loader)


def run_profile(profile, rinlib_dir):
    profile_id = profile["id"]
    expected = f"paid-waystones-1.0.1+{profile['minecraft']}-{profile['loader']}.jar"
    destination = DIST / expected
    if destination.exists():
        print(f"{profile_id}\t{destination}\t(existing)")
        return
    module = module_for(profile)
    java_home = JDK[profile["java"]]
    env = os.environ.copy()
    env["JAVA_HOME"] = str(java_home)
    env["PATH"] = f"{java_home / 'bin'}:{env['PATH']}"
    env["GRADLE_USER_HOME"] = str(Path(os.environ.get(
        "FABRIC_GRADLE_USER_HOME" if module == "fabric" else "GRADLE_USER_HOME",
        HOME / (".gradle-paid-fabric" if module == "fabric" else ".gradle-paid"),
    )))
    toolchains = [HOME / ".local/jdk-17", HOME / ".local/jdk-21", HOME / ".local/jdk-25"]
    env["GRADLE_OPTS"] = f"-Dorg.gradle.java.installations.paths={','.join(map(str, toolchains))}"

    if profile["generation"] == "shogi":
        subprocess.run([sys.executable, str(ROOT / "tools" / "prepare_profile_dependencies.py"), "--profile", profile_id], check=True, cwd=ROOT)

    if module == "fabric":
        shutil.rmtree(ROOT / "fabric" / "build" / "generated", ignore_errors=True)

    if module == "fabric" and profile["minecraft"] == "26.1.2":
        command = [
            str(GRADLE_9_2), "--no-daemon", "--no-configuration-cache", "-p", "fabric-cross26", ":fabric:build",
            f"-PrinlibDir={rinlib_dir}",
        ]
        output_dir = ROOT / "fabric-cross26" / "fabric" / "build" / "libs"
    elif module == "fabric" and profile["minecraft"] == "1.21.11":
        profiles = json.loads(PROFILES_FILE.read_text())["profiles"]
        neo = next(x for x in profiles if x["minecraft"] == "1.21.11" and x["loader"] == "neoforge")
        command = [
            str(GRADLE_9_2), "--no-daemon", "--no-configuration-cache", "-p", "fabric-cross", "build",
            "-Pminecraft_version=1.21.11",
            "-Pneo_form_version=1.21.11-20251209.172050",
            f"-Pfabric_loader_version={profile['loader_version']}",
            f"-Pfabric_version={profile['fabric_api']}",
            f"-Pwaystones_fabric_version={profile['waystones']['version_number']}",
            f"-Pwaystones_neoforge_version={neo['waystones']['version_number']}",
            f"-Pbalm_fabric_version={profile['balm']['version_number']}",
            f"-Pbalm_neoforge_version={neo['balm']['version_number']}",
            f"-Pgame_versions_json={json.dumps(profile['game_versions'], separators=(',', ':'))}",
            f"-PrinlibDir={rinlib_dir}",
        ]
        output_dir = ROOT / "fabric-cross" / "build" / "libs"
    elif module == "fabric":
        if profile["minecraft"].startswith("26."):
            fabric_gradle, loom_version = GRADLE_9_5, "1.17-SNAPSHOT"
        elif tuple(map(int, profile["minecraft"].split("."))) >= (1, 21, 1):
            fabric_gradle, loom_version = GRADLE_9_2, "1.14.10"
        else:
            fabric_gradle, loom_version = GRADLE_8, "1.11.8"
        command = [
            str(fabric_gradle), "--no-daemon", "--no-configuration-cache", "-p", "fabric", "clean", "build",
            f"-Ploom_version={loom_version}",
            f"-Pminecraft_version={profile['minecraft']}",
            f"-Ploader_version={profile['loader_version']}",
            f"-Pfabric_api_version={profile['fabric_api']}",
            f"-Pwaystones_version={profile['waystones']['version_number']}",
            f"-Pbalm_version={profile['balm']['version_number']}",
            f"-Psource_generation={profile['generation']}",
            f"-Pjava_version={profile['java']}",
            f"-Pgame_versions_json={json.dumps(profile['game_versions'], separators=(',', ':'))}",
            f"-PrinlibDir={rinlib_dir}",
        ]
        if profile["generation"] == "shogi":
            command.append(f"-Pshogi_version={profile['shogi']['version_number']}")
        output_dir = ROOT / "fabric" / "build" / "libs"
    else:
        gradle = GRADLE_9_5 if module == "forge-modern" else GRADLE_8
        command = [str(gradle), "--no-daemon", "--no-configuration-cache", f"-Pprofile={profile_id}", f"-PrinlibDir={rinlib_dir}", f":{module}:clean", f":{module}:build"]
        output_dir = ROOT / module / "build" / "libs"

    LOGS.mkdir(parents=True, exist_ok=True)
    log = LOGS / f"{profile_id}.log"
    with log.open("w") as stream:
        result = subprocess.run(command, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        tail = "\n".join(log.read_text(errors="replace").splitlines()[-60:])
        raise RuntimeError(f"{profile_id} failed; log={log}\n{tail}")

    candidates = [p for p in output_dir.glob("*.jar") if not p.name.endswith("-sources.jar") and not p.name.endswith("-javadoc.jar")]
    artifact = next((p for p in candidates if p.name == expected), None)
    if artifact is None:
        raise RuntimeError(f"{profile_id} did not produce {expected}: {[p.name for p in candidates]}")
    DIST.mkdir(parents=True, exist_ok=True)
    shutil.copy2(artifact, destination)
    print(f"{profile_id}\t{destination}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", action="append")
    parser.add_argument("--rinlib-dir", type=Path, required=True)
    args = parser.parse_args()
    data = json.loads(PROFILES_FILE.read_text())
    profiles = data["profiles"]
    if args.profile:
        wanted = set(args.profile)
        profiles = [p for p in profiles if p["id"] in wanted]
        missing = wanted - {p["id"] for p in profiles}
        if missing:
            raise SystemExit(f"Unknown profiles: {sorted(missing)}")
    for profile in profiles:
        run_profile(profile, args.rinlib_dir.resolve())


if __name__ == "__main__":
    main()

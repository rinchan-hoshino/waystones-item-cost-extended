#!/usr/bin/env python3
import argparse
import hashlib
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
GRADLE_8 = ROOT / "gradlew"
GRADLE_9_2 = ROOT / "tools/gradle-9.2.1/gradlew"
GRADLE_9_5 = ROOT / "tools/gradle-9.5.1/gradlew"
def java_home(version):
    value = os.environ.get(f"JAVA_{version}_HOME") or os.environ.get("JAVA_HOME")
    if not value or not (Path(value) / "bin/java").is_file():
        raise RuntimeError(f"Set JAVA_{version}_HOME to a JDK installation")
    return Path(value)



def source_fingerprint():
    digest = hashlib.sha256()
    for path in sorted(ROOT.rglob("*")):
        relative = path.relative_to(ROOT)
        if not path.is_file() or any(part in {".git", ".gradle", "build", "runtime-tests", "docs", "__pycache__"} for part in relative.parts):
            continue
        if path.suffix == ".md":
            continue
        digest.update(str(relative).encode())
        digest.update(path.read_bytes())
    return digest.hexdigest()


def verify_locked_maven() -> None:
    root = ROOT / "tools" / "locked-maven"
    lock = json.loads((root / "lock.json").read_text())
    expected = {entry["path"] for entry in lock["files"]}
    actual = {
        str(path.relative_to(root))
        for path in root.rglob("*")
        if path.is_file() and path.name not in {"README.md", "lock.json"}
    }
    if actual != expected:
        raise RuntimeError(f"Locked Maven file set differs: actual={sorted(actual)} expected={sorted(expected)}")
    for entry in lock["files"]:
        path = root / entry["path"]
        if path.stat().st_size != entry["size"] or sha512(path) != entry["sha512"]:
            raise RuntimeError(f"Locked Maven artifact mismatch: {path}")


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


def sha512(path: Path) -> str:
    digest = hashlib.sha512()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def snapshot_classpath(profile_id: str, classpath_output: Path) -> Path:
    store = ROOT / "build" / "audit-classpath-store"
    manifests = ROOT / "build" / "profile-audit-classpaths"
    store.mkdir(parents=True, exist_ok=True)
    manifests.mkdir(parents=True, exist_ok=True)
    records = []
    for raw in classpath_output.read_text().splitlines():
        if not raw:
            continue
        source = Path(raw)
        if not source.is_file():
            raise RuntimeError(f"{profile_id} classpath entry is not a file: {source}")
        checksum = sha512(source)
        suffix = source.suffix if source.suffix else ".bin"
        snapshot = store / f"{checksum}{suffix}"
        if snapshot.exists():
            if snapshot.stat().st_size != source.stat().st_size or sha512(snapshot) != checksum:
                raise RuntimeError(f"Corrupt classpath snapshot: {snapshot}")
        else:
            try:
                os.link(source, snapshot)
            except OSError:
                shutil.copy2(source, snapshot)
            if sha512(snapshot) != checksum:
                snapshot.unlink(missing_ok=True)
                raise RuntimeError(f"Classpath snapshot hash mismatch: {source}")
        records.append({
            "original": str(source),
            "snapshot": str(snapshot),
            "size": source.stat().st_size,
            "sha512": checksum,
        })
    if not records:
        raise RuntimeError(f"{profile_id} resolved an empty compile classpath")
    manifest = manifests / f"{profile_id}.json"
    manifest.write_text(json.dumps({"profile": profile_id, "entries": records}, indent=2) + "\n")
    audit_list = manifests / f"{profile_id}.txt"
    audit_list.write_text("".join(f"{record['snapshot']}\n" for record in records))
    return audit_list


def run_profile(profile, rinlib_dir, reuse_existing=False):
    profile_id = profile["id"]
    release_version = json.loads(PROFILES_FILE.read_text())["paid_waystones_version"]
    expected = f"waystones-item-cost-extended-{release_version}+{profile['minecraft']}-{profile['loader']}.jar"
    destination = DIST / expected
    receipt = DIST / (profile_id + ".json")
    fingerprint = source_fingerprint()
    if destination.exists() and reuse_existing and receipt.exists():
        previous = json.loads(receipt.read_text())
        if previous.get("source_fingerprint") == fingerprint and previous.get("sha512") == sha512(destination):
            print(f"{profile_id}\t{destination}\t(verified)")
            return
    module = module_for(profile)
    audit_project = f":{module}"
    gradle_java_home = java_home(25 if module == "fabric" and profile["java"] >= 25 else 21)
    env = os.environ.copy()
    env["JAVA_HOME"] = str(gradle_java_home)
    env["PATH"] = f"{gradle_java_home / 'bin'}:{env['PATH']}"
    toolchains = sorted({v for k, v in os.environ.items() if k.startswith("JAVA_") and k.endswith("_HOME")})

    subprocess.run(
        [sys.executable, str(ROOT / "tools" / "prepare_profile_dependencies.py"), "--profile", profile_id],
        check=True,
        cwd=ROOT,
    )
    profile_dependencies = ROOT / "build" / "profile-dependencies"

    if module == "fabric":
        shutil.rmtree(ROOT / "fabric" / "build" / "generated", ignore_errors=True)

    if module == "fabric" and profile["minecraft"] == "26.1.2":
        command = [
            str(GRADLE_9_2), "--no-daemon", "--no-configuration-cache", "-p", "fabric-cross26", ":fabric:jar",
            f"-PrinlibDir={rinlib_dir}",
            f"-PprofileDependenciesDir={profile_dependencies}",
            f"-PprofileId={profile_id}",
        ]
        output_dir = ROOT / "fabric-cross26" / "fabric" / "build" / "libs"
    elif module == "fabric" and profile["minecraft"] == "1.21.11":
        profiles = json.loads(PROFILES_FILE.read_text())["profiles"]
        neo = next(x for x in profiles if x["minecraft"] == "1.21.11" and x["loader"] == "neoforge")
        command = [
            str(GRADLE_9_2), "--no-daemon", "--no-configuration-cache", "-p", "fabric-cross", "remapJar",
            "-Pminecraft_version=1.21.11",
            "-Pneo_form_version=1.21.11-20251209.172050",
            f"-Pfabric_loader_version={profile['loader_version']}",
            f"-Pfabric_version={profile['fabric_api']}",
            f"-Pwaystones_fabric_version={profile['waystones']['version_number']}",
            f"-Pwaystones_neoforge_version={neo['waystones']['version_number']}",
            f"-Pbalm_fabric_version={profile['balm']['version_number']}",
            f"-Pbalm_neoforge_version={neo['balm']['version_number']}",
            f"-Pgame_versions_json={json.dumps(profile['game_versions'], separators=(',', ':'))}",
            f"-Pmod_version={release_version}",
            f"-Prinlib_version={json.loads(PROFILES_FILE.read_text())['rinlib_version']}",
            f"-PrinlibDir={rinlib_dir}",
            f"-PprofileDependenciesDir={profile_dependencies}",
            f"-PprofileId={profile_id}",
        ]
        output_dir = ROOT / "fabric-cross" / "build" / "libs"
        audit_project = ":"
    elif module == "fabric":
        if profile["minecraft"].startswith("26."):
            fabric_gradle, loom_version = GRADLE_9_5, "1.17.20"
        elif tuple(map(int, profile["minecraft"].split("."))) >= (1, 21, 1):
            fabric_gradle, loom_version = GRADLE_9_2, "1.14.10"
        else:
            fabric_gradle, loom_version = GRADLE_8, "1.11.8"
        command = [
            str(fabric_gradle), "--no-daemon", "--no-configuration-cache", "-p", "fabric", "clean", "remapJar",
            f"-Ploom_version={loom_version}",
            f"-Pminecraft_version={profile['minecraft']}",
            f"-Ploader_version={profile['loader_version']}",
            f"-Pfabric_api_version={profile['fabric_api']}",
            f"-Pwaystones_version={profile['waystones']['version_number']}",
            f"-Pbalm_version={profile['balm']['version_number']}",
            f"-Psource_generation={profile['generation']}",
            f"-Pjava_version={profile['java']}",
            f"-Pgame_versions_json={json.dumps(profile['game_versions'], separators=(',', ':'))}",
            f"-Pmod_version={release_version}",
            f"-Prinlib_version={json.loads(PROFILES_FILE.read_text())['rinlib_version']}",
            f"-PrinlibDir={rinlib_dir}",
            f"-PprofileDependenciesDir={profile_dependencies}",
        ]
        if profile["generation"] == "shogi":
            command.append(f"-Pshogi_version={profile['shogi']['version_number']}")
        output_dir = ROOT / "fabric" / "build" / "libs"
        audit_project = ":"
    else:
        gradle = GRADLE_9_5 if module == "forge-modern" else GRADLE_8
        command = [
            str(gradle), "--no-daemon", "--no-configuration-cache",
            f"-Pprofile={profile_id}",
            f"-PrinlibDir={rinlib_dir}",
            f"-PprofileDependenciesDir={profile_dependencies}",
            f":{module}:clean", f":{module}:jar",
        ]
        output_dir = ROOT / "build/profile-work" / profile_id / module / "libs"

    classpath_output = ROOT / "build" / "profile-classpaths" / f"{profile_id}.txt"
    production_classpath_output = ROOT / "build" / "profile-production-classpaths" / f"{profile_id}.txt"
    audit_task = ":writeAuditClasspath" if audit_project == ":" else f"{audit_project}:writeAuditClasspath"
    command[2:2] = [
        "--max-workers=2",
        "-I", str(ROOT / "tools" / "export-audit-classpath.init.gradle"),
        f"-PauditProjectPath={audit_project}",
        f"-PauditClasspathOutput={classpath_output}",
        f"-PauditProductionClasspathOutput={production_classpath_output}",
    ]
    command.extend([audit_task, "-Porg.gradle.java.installations.paths=" + ",".join(toolchains)])
    LOGS.mkdir(parents=True, exist_ok=True)
    log = LOGS / f"{profile_id}.log"
    with log.open("w") as stream:
        result = subprocess.run(command, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        tail = "\n".join(log.read_text(errors="replace").splitlines()[-60:])
        raise RuntimeError(f"{profile_id} failed; log={log}\n{tail}")
    if not classpath_output.is_file() or not production_classpath_output.is_file():
        raise RuntimeError(f"{profile_id} did not export both audit classpaths")
    snapshot_classpath(profile_id, production_classpath_output)

    candidates = [p for p in output_dir.glob("*.jar") if not p.name.endswith("-sources.jar") and not p.name.endswith("-javadoc.jar")]
    artifact = next((p for p in candidates if p.name == expected), None)
    if artifact is None:
        raise RuntimeError(f"{profile_id} did not produce {expected}: {[p.name for p in candidates]}")
    DIST.mkdir(parents=True, exist_ok=True)
    shutil.copy2(artifact, destination)
    if source_fingerprint() != fingerprint:
        raise RuntimeError("Sources changed during the build; rerun this profile")
    receipt.write_text(json.dumps({"profile": profile_id, "file": str(destination),
                                  "source_fingerprint": fingerprint, "sha512": sha512(destination)}, indent=2) + "\n")
    print(f"{profile_id}\t{destination}", flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", action="append")
    parser.add_argument("--rinlib-dir", type=Path, required=True)
    parser.add_argument("--reuse-existing", action="store_true", help="Reuse already-built artifacts only after the source tree is frozen")
    args = parser.parse_args()
    verify_locked_maven()
    data = json.loads(PROFILES_FILE.read_text())
    profiles = data["profiles"]
    if args.profile:
        wanted = set(args.profile)
        profiles = [p for p in profiles if p["id"] in wanted]
        missing = wanted - {p["id"] for p in profiles}
        if missing:
            raise SystemExit(f"Unknown profiles: {sorted(missing)}")
    for profile in profiles:
        run_profile(profile, args.rinlib_dir.resolve(), args.reuse_existing)


if __name__ == "__main__":
    main()

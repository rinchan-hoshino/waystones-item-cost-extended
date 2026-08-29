#!/usr/bin/env python3
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[1]
PROFILES_FILE = ROOT / "support" / "profiles.json"
CANONICAL_COMMIT = "308fd7c634c84dec7505337369f318bc6dee58b0"
DEFAULT_CANONICAL_REPOSITORY = Path("/home/rin/rinchan/work/watermelon-field-bulrush")


def digest(path: Path, algorithm: str) -> str:
    result = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(chunk)
    return result.hexdigest()


def extract_canonical_auditor(repository: Path, target: Path) -> Path:
    actual = subprocess.check_output(
        ["git", "-C", str(repository), "rev-parse", f"{CANONICAL_COMMIT}^{{commit}}"], text=True
    ).strip()
    if actual != CANONICAL_COMMIT:
        raise RuntimeError(f"Canonical ABI owner commit mismatch: {actual}")
    archive = subprocess.check_output([
        "git", "-C", str(repository), "archive", CANONICAL_COMMIT,
        "scripts/wmf-final-jar-abi-audit.py", "scripts/final-jar-abi-audit",
    ])
    with tarfile.open(fileobj=io.BytesIO(archive)) as bundle:
        bundle.extractall(target, filter="data")
    return target / "scripts" / "wmf-final-jar-abi-audit.py"


def verify_classpath(profile_id: str) -> tuple[list[Path], str]:
    root = ROOT / "build" / "profile-audit-classpaths"
    manifest_path = root / f"{profile_id}.json"
    list_path = root / f"{profile_id}.txt"
    manifest = json.loads(manifest_path.read_text())
    if manifest["profile"] != profile_id or not manifest["entries"]:
        raise RuntimeError(f"Invalid classpath manifest for {profile_id}")
    classpath = []
    for entry in manifest["entries"]:
        path = Path(entry["snapshot"])
        if not path.is_file() or path.stat().st_size != entry["size"] or digest(path, "sha512") != entry["sha512"]:
            raise RuntimeError(f"Classpath snapshot changed for {profile_id}: {path}")
        classpath.append(path)
    listed = [Path(line) for line in list_path.read_text().splitlines() if line]
    if listed != classpath:
        raise RuntimeError(f"Classpath list and manifest differ for {profile_id}")
    return classpath, digest(manifest_path, "sha256")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--profile", action="append")
    parser.add_argument("--canonical-repository", type=Path, default=DEFAULT_CANONICAL_REPOSITORY)
    args = parser.parse_args()
    matrix = json.loads(PROFILES_FILE.read_text())
    profiles = matrix["profiles"]
    if args.profile:
        wanted = set(args.profile)
        profiles = [profile for profile in profiles if profile["id"] in wanted]
        missing = wanted - {profile["id"] for profile in profiles}
        if missing:
            raise SystemExit(f"Unknown profiles: {sorted(missing)}")

    output_dir = ROOT / "build" / "profile-abi-logs"
    output_dir.mkdir(parents=True, exist_ok=True)
    report = {
        "schema": 1,
        "canonical_owner": {
            "repository": str(args.canonical_repository.resolve()),
            "commit": CANONICAL_COMMIT,
        },
        "profiles": [],
    }
    failures = []
    with tempfile.TemporaryDirectory(prefix="paid-waystones-canonical-abi-") as temporary:
        auditor = extract_canonical_auditor(args.canonical_repository.resolve(), Path(temporary))
        report["canonical_owner"]["script_sha256"] = digest(auditor, "sha256")
        for profile in profiles:
            profile_id = profile["id"]
            subject = ROOT / "build" / "profiles" / (
                f"paid-waystones-{matrix['paid_waystones_version']}+{profile['minecraft']}-{profile['loader']}.jar"
            )
            classpath, manifest_hash = verify_classpath(profile_id)
            command = ["python3", str(auditor), "--subject", str(subject)]
            for entry in classpath:
                command.extend(["--classpath", str(entry)])
            result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            log = output_dir / f"{profile_id}.log"
            log.write_text(result.stdout)
            lines = result.stdout.splitlines()
            status = result.returncode == 0 and lines and lines[-1] == "RESULT=PASS"
            record = {
                "profile": profile_id,
                "subject": str(subject),
                "subject_sha256": digest(subject, "sha256"),
                "subject_sha512": digest(subject, "sha512"),
                "classpath_entries": len(classpath),
                "classpath_manifest_sha256": manifest_hash,
                "summary": next((line for line in lines if line.startswith("subject_classes=")), None),
                "result": "PASS" if status else "FAIL",
                "log": str(log),
            }
            report["profiles"].append(record)
            print(f"{profile_id}\t{record['result']}")
            if not status:
                failures.append(profile_id)
    report["result"] = "PASS" if not failures else "FAIL"
    report["failures"] = failures
    destination = ROOT / "build" / "profile-abi-audit.json"
    destination.write_text(json.dumps(report, indent=2) + "\n")
    if failures:
        raise SystemExit(f"Canonical ABI audit failed: {failures}; report={destination}")
    print(f"profile_abi={len(profiles)}/{len(profiles)} status=PASS report={destination}")


if __name__ == "__main__":
    main()

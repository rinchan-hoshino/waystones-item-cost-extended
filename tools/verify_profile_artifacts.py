#!/usr/bin/env python3
"""Verify Waystones Item Cost Extended profile JAR metadata and generation-owned resources."""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
try:
    import tomllib
except ImportError:
    import tomli as tomllib
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PROFILES_FILE = ROOT / "support" / "profiles.json"
MOD_ID = "waystone_emeralds"
MATRIX = json.loads(PROFILES_FILE.read_text())
VERSION = MATRIX["paid_waystones_version"]
BASE = "dev/rinchan/waystoneemeralds/"
PAYMENT_CLASSES = (
    "payment/CraftingDenominations.class",
    "payment/ItemPayment.class",
    "payment/PaymentConfig.class",
    "minecraft/RecipeCurrencies.class",
    "minecraft/RecipeAccess.class",
)



def digest(path: Path, algorithm: str) -> str:
    value = hashlib.new(algorithm)
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def minecraft_range(versions: list[str]) -> str:
    parsed = sorted(tuple(int(part) for part in version.split(".")) for version in versions)
    high = list(parsed[-1])
    high[-1] += 1
    return f"[{'.'.join(map(str, parsed[0]))},{'.'.join(map(str, high))})"


def require_dependencies(actual: dict[str, object], expected: dict[str, object]) -> None:
    if set(actual) != set(expected):
        raise AssertionError(f"dependency IDs {sorted(actual)} != {sorted(expected)}")
    for mod_id, value in expected.items():
        if actual[mod_id] != value:
            raise AssertionError(f"dependency {mod_id}: {actual[mod_id]!r} != {value!r}")


def verify_fabric(metadata: dict[str, object], profile: dict[str, object]) -> None:
    if metadata["id"] != MOD_ID or metadata["version"] != VERSION:
        raise AssertionError("wrong Fabric identity")
    if metadata["mixins"] != ["paid_waystones.mixins.json"]:
        raise AssertionError("wrong Fabric Mixin declaration")
    expected = {
        "fabricloader": ">=0.15.0",
        "minecraft": profile["game_versions"],
        "java": f">={profile['java']}",
        "rinlib": ">=" + MATRIX["rinlib_version"],
        "waystones": f">={profile['waystones']['version_number'].split('+')[0]}",
        "balm-fabric": f">={profile['balm']['version_number'].split('+')[0]}",
    }
    if profile["generation"] == "shogi":
        expected["shogi"] = ">=26.1"
    require_dependencies(metadata["depends"], expected)


def verify_toml(metadata: dict[str, object], profile: dict[str, object]) -> None:
    mods = metadata.get("mods")
    if not isinstance(mods, list) or len(mods) != 1:
        raise AssertionError("wrong TOML mods table")
    if mods[0].get("modId") != MOD_ID or mods[0].get("version") != VERSION:
        raise AssertionError("wrong TOML identity")
    dependencies = metadata.get("dependencies", {}).get(MOD_ID)
    if not isinstance(dependencies, list):
        raise AssertionError("missing TOML dependencies")
    by_id = {item["modId"]: item for item in dependencies}
    expected_ids = {"minecraft", "rinlib", "waystones", "balm"}
    if profile["loader"] == "neoforge":
        expected_ids.add("neoforge")
    if profile["generation"] == "shogi":
        expected_ids.add("shogi")
    if set(by_id) != expected_ids:
        raise AssertionError(f"dependency IDs {sorted(by_id)} != {sorted(expected_ids)}")
    expected_ranges = {
        "minecraft": minecraft_range(profile["game_versions"]),
        "rinlib": "[" + MATRIX["rinlib_version"] + ",)",
        "waystones": f"[{profile['waystones']['version_number'].split('+')[0]},)",
        "balm": f"[{profile['balm']['version_number'].split('+')[0]},)",
    }
    if profile["loader"] == "neoforge":
        expected_ranges["neoforge"] = f"[{profile['loader_version']},)"
        if metadata.get("mixins") != [{"config": "paid_waystones.mixins.json"}]:
            raise AssertionError("wrong NeoForge Mixin declaration")
    if profile["generation"] == "shogi":
        expected_ranges["shogi"] = "[26.1,)"
    for mod_id, version_range in expected_ranges.items():
        if by_id[mod_id].get("versionRange") != version_range:
            raise AssertionError(f"{mod_id} range {by_id[mod_id].get('versionRange')!r} != {version_range!r}")
        if profile["loader"] == "forge" and by_id[mod_id].get("mandatory") is not True:
            raise AssertionError(f"{mod_id} is not mandatory")
        if profile["loader"] == "neoforge" and by_id[mod_id].get("type") != "required":
            raise AssertionError(f"{mod_id} is not required")


def verify_jar(jar: Path, profile: dict[str, object]) -> dict[str, object]:
    with zipfile.ZipFile(jar) as archive:
        bad = archive.testzip()
        if bad is not None:
            raise AssertionError(f"bad ZIP member {bad}")
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise AssertionError("duplicate ZIP members")
        unresolved = [name for name in names if b"${" in archive.read(name)]
        if unresolved:
            raise AssertionError(f"unexpanded placeholders in {unresolved}")
        mixin = json.loads(archive.read("paid_waystones.mixins.json"))
        expected_mixin = "ItemCostMixin" if profile["generation"] == "shogi" else "PaymentRequirementMixin"
        wrong_mixin = "PaymentRequirementMixin" if expected_mixin == "ItemCostMixin" else "ItemCostMixin"
        if mixin.get("package") != "dev.rinchan.waystoneemeralds.mixin" or mixin.get("mixins") != ([expected_mixin] if profile["generation"] == "shogi" else [expected_mixin, "ItemRequirementMixin", "ItemRequirementsContextMixin", "NetworkingMixin"]):
            raise AssertionError("wrong generation Mixin config")
        required = [BASE + name for name in PAYMENT_CLASSES] + [BASE + f"mixin/{expected_mixin}.class"]
        missing = [name for name in required if name not in names]
        if missing:
            raise AssertionError(f"missing classes {missing}")
        if BASE + f"mixin/{wrong_mixin}.class" in names:
            raise AssertionError(f"wrong generation class {wrong_mixin}")
        if any(name.startswith("dev/rinchan/rinlib/") or "rinlibprobe" in name or "ProfileChecks" in name for name in names):
            raise AssertionError("embedded library or runtime test code")
        if "icon.png" not in names:
            raise AssertionError("missing project icon")
        manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8", "replace")
        if "MixinConfigs: paid_waystones.mixins.json" not in manifest.replace("\r\n ", ""):
            raise AssertionError("missing MixinConfigs manifest entry")
        if profile["loader"] == "fabric":
            verify_fabric(json.loads(archive.read("fabric.mod.json")), profile)
        else:
            metadata_name = "META-INF/mods.toml" if profile["loader"] == "forge" or profile["minecraft"] == "1.20.4" else "META-INF/neoforge.mods.toml"
            verify_toml(tomllib.loads(archive.read(metadata_name).decode()), profile)
        maximum_major = 0
        for name in names:
            if name.endswith(".class"):
                data = archive.read(name)
                if data[:4] != b"\xca\xfe\xba\xbe":
                    raise AssertionError(f"bad class header {name}")
                maximum_major = max(maximum_major, struct.unpack(">H", data[6:8])[0])
        allowed_major = {17: 61, 21: 65, 25: 69}[profile["java"]]
        if maximum_major > allowed_major:
            raise AssertionError(f"class major {maximum_major} exceeds Java {profile['java']}")
    return {
        "profile": profile["id"],
        "minecraft": profile["minecraft"],
        "loader": profile["loader"],
        "generation": profile["generation"],
        "jar": str(jar),
        "size": jar.stat().st_size,
        "sha256": digest(jar, "sha256"),
        "sha512": digest(jar, "sha512"),
        "maximum_class_major": maximum_major,
        "status": "PASS",
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--artifacts", type=Path, default=ROOT / "build" / "profiles")
    parser.add_argument("--output", type=Path, default=ROOT / "build" / "profile-artifact-verification.json")
    args = parser.parse_args()
    profiles = json.loads(PROFILES_FILE.read_text())["profiles"]
    results = []
    for profile in profiles:
        jar = args.artifacts / f"waystones-item-cost-extended-{VERSION}+{profile['minecraft']}-{profile['loader']}.jar"
        if not jar.is_file():
            raise SystemExit(f"missing profile artifact: {jar}")
        results.append(verify_jar(jar, profile))
    report = {"schema": 1, "version": VERSION, "profiles": len(results), "status": "PASS", "results": results}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"profile_artifacts={len(results)}/{len(profiles)} status=PASS report={args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

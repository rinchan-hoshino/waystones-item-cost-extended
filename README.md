# Paid Waystones

Paid Waystones lets Waystones' native emerald item costs be paid with vanilla emeralds or emerald blocks.

Waystones remains the sole owner of pricing. Configure costs with `teleports.warpRequirements` in `config/waystones-common.toml`.

When a native item cost charges `minecraft:emerald`:

- One emerald block counts as nine emeralds.
- Excess value is returned as loose emeralds.
- Creative-mode players are not charged.
- Legacy Waystones rollback calls refund the consumed emerald value.
- Other item, experience, level, cooldown, source and dimensional requirements keep their native behavior.

Paid Waystones adds no currency account, pricing configuration, command, block or item.

## Supported versions

Version 1.0.1 follows every current official Waystones loader target that has native item-cost rules:

- Minecraft 1.20.4 and 1.20.6
- Minecraft 1.21 through 1.21.11
- Minecraft 26.1.2 and 26.2
- Fabric, Forge and NeoForge wherever Waystones publishes that loader

The exact machine-readable matrix and upstream Waystones version IDs are in [`support/profiles.json`](support/profiles.json). Minecraft 1.20.2 and older are intentionally unsupported because those Waystones releases do not provide the native item-cost system this mod extends.

## Requirements

- Waystones
- Balm
- RinLib 1.0.0
- Shogi on Minecraft 26.x

Both client and server should install the mod.

## Implementation

One payment implementation uses RinLib's denomination planner. Loader builds select one of two thin Waystones adapters:

- `ItemRequirementMixin` for the classic Waystones requirement API through Minecraft 1.21.11.
- `ItemCostMixin` for the Shogi-backed rule engine on Minecraft 26.x.

The native requirement and renderer classes remain in place, so pricing, menu rendering, synchronization and teleport orchestration stay owned by Waystones.

## Build

Build one profile:

```bash
python3 tools/build_profiles.py \
  --profile 1.21.11-neoforge \
  --rinlib-dir /path/to/rinlib/build/portable-profiles
```

Build every profile by omitting `--profile`.

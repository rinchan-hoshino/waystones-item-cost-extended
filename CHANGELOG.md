# Changelog

## 2.0.0

### Core Architecture & Platform Support
- Standardized Mod ID `waystones_item_cost_extended` and unified Java package `dev.rinchan.waystonesitemcost`.
- Standardized Mixin configs to `waystones_item_cost_extended.mixins.json` and `waystones_item_cost_extended.client.mixins.json` with soft-degradation logging (`MixinGuard`) so unexpected Waystones/Shogi internal changes safely fall back to vanilla behavior instead of crashing.
- Converged build matrix to 8 mainstream targets: `1.20.1` (Fabric/Forge), `1.21.1` (Fabric/NeoForge), `26.1.2` (Fabric/NeoForge), and `26.2` (Fabric/NeoForge) with zero external library dependencies.

### New Features
- **Zero-Dependency TOML Config**: Configuration via `config/waystones-item-cost-extended.toml` with hot-save support.
- **Sensible Defaults**: Enabled `recipePayments = true`, `searchNestedContainers = true`, and `showPaymentPreviewTooltip = true` by default.
- **Nested Container Deductions**: Deduct payment items from Bundles and Shulker Boxes inside inventory while strictly keeping returned change in ordinary inventory slots `0..35`.
- **UI Payment Preview & Full-Inventory Diagnostics**: Added localized tooltip breakdown lines (actual payment, change returned, wallet usage, nested container usage) and explicit red warnings (`INVENTORY_FULL_FOR_CHANGE`) displaying the exact number of slots to free both in tooltips and on teleport rejection.
- **Native Config Screen**: Added in-game Config Screen (Mod Menu on Fabric with compile-only stub, native config factory on Forge/NeoForge) featuring a Waystones item cost rule status bar and a one-click **"Copy Emerald Cost Config Example"** button.
- **Minecraft 1.20.1 Support**: Added `[legacy_1_20_1]` custom item cost rules (base, per-km, cross-dimension, optional vanilla XP waiver) powered by `WaystoneTeleportEvent.Pre` and `WaystoneButton` rendering, sharing the exact same payment engine.
- **Minecraft 26.x Recipe Sync**: Added Server → Client reversible crafting denomination sync for `26.1.2` and `26.2` so client previews match server datapacks.
- **Expanded Lightman's Currency Support**: Added optional native integration for `1.20.1` (Forge) and `26.1.2` (NeoForge) alongside `1.21.1` (NeoForge).
- **4-Language Localization & New Icon**: Added `en_us`, `zh_cn`, `zh_tw`, and `ja_jp` translations and a new original 64×64 pixel-art badge icon (exported at 512×512).

## 1.2.0
- Added optional native Lightman's Currency integration for NeoForge 1.21.1.

## 1.1.1
- Fixed recipe lookup cost in Waystones menu.

## 1.1.0
- Extended Waystones item payments with shared reversible crafting denominations.

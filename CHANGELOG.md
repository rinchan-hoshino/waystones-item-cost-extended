# Changelog

## 1.2.0

- Adds optional native Lightmans Currency (1.21-2.3.0.6+) support on Minecraft 1.21.1 NeoForge.
- Supports all CoinAPI-registered currencies and custom coin chains across isolated denomination hierarchies.
- Scans equipped native and Curios wallets via WalletHandler, main inventory, hotbar, and occupied offhand coin stacks without accessing bank balances or loose unequipped wallets.
- Uses native CoinContainerMoneyHandler over pre-mutation snapshots for combined coin and item cost previews. Commits only after checking inventory and wallet snapshots; rollback restores touched slots and wallets once, and only when storage still matches the committed state.
- Performs native denomination arithmetic honoring wallet auto-exchange settings, rejecting transactions during preview if change cannot fit into wallet or main inventory storage.
- Preserves creative bypass, zero-cost routes, and recipePayments=false boundaries for reversible crafting conversions.

## 1.0.2

- Moves payment mutation behind a pure transaction boundary with pre-mutation plan validation.
- Adds deterministic payment, rollback, aggregate, conservation-property, and malformed-plan tests.
- Adds a logic-only Gradle entry point so semantic tests do not resolve Minecraft or loader projects.
- Preserves the existing Waystones Requirement and Shogi behavior across all published loader targets.

## 1.0.1

- Migrates the shared payment contract to formal RinLib 1.0.0.
- Does not change prices, payment planning, change, creative-mode behavior or Waystones integration.

## 1.0.0

- Added all official Waystones native-item-cost targets from Minecraft 1.20.4 through 26.2.
- Added Fabric and Forge support alongside NeoForge where Waystones publishes those loaders.
- Replaced the custom requirement type and client renderer with thin native-class Mixins.
- Added the Shogi-backed item-cost adapter used by Waystones on Minecraft 26.x.
- Moved denomination planning into RinLib 0.4.0.
- Preserved emerald-block conversion, emerald change, creative bypass and legacy rollback behavior.

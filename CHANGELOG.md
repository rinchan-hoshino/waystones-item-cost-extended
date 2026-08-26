# Changelog

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

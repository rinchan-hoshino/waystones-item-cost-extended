# Feature Map — Waystones Item Cost Extended 2.1.3

## 1. Module & Package Architecture (`dev.rinchan.waystonesitemcost`)

| Layer | Directory | Responsibility |
|---|---|---|
| Pure Payment Engine | `common/.../payment/` | `DenominatedPayment`, `CraftingDenominations`, `ChangeSlotPlanner`, `PaymentStatus`, `ItemPayment`, `CurrencyPayment` |
| Physics Sub-World Resolver | `common/.../physics/` | `PhysicsPosCore` (pure distance/fallback kernel), `PhysicsModBridge` (zero-dep Sable & VS2 `MethodHandle` bridge), `PhysicsPosResolver` |
| Configuration | `common/.../config/` | `SimpleToml`, `LegacyCostEntry`, `PaymentConfig` (TOML + hot-save), `WaystonesRuleDetector` |
| Minecraft Helpers | `common/.../minecraft/` | `RecipeCurrencies`, `PaymentTooltipHelper`, `MixinGuard` |
| Version Adapters | `adapters/{1.20.1,1.21.1,26}/` | `RecipeAccess` (recipe reading, Bundle/Shulker nested container I/O, config snippets), `PhysicsPosStorage` (server `SavedData`), `WaystonesItemCostConfigScreen` |
| Networking | `networking/{1.20.1,1.21.1,26}/` | `RecipeSync`, `PhysicsPosSync` (Server → Client sync across all 10 targets) & `NetworkingMixin` |
| Waystones Generations | `versions/{legacy,requirement,shogi}/` | `legacy` (1.20.1 event, XP distance, menu sync & button), `requirement` (1.21.1 combined/item/distance mixins & tooltips), `shogi` (26.x `ItemCost`, distance/comparator/button mixins & tooltips) |
| Optional Compat | `compat/{none,lightman-1.20.1,lightman-1.21.1,lightman-26.1}/` | Native Lightman's Currency coin & wallet payment integration (soft-loaded via `ModList`) |
| Loader Entrypoints | `loaders/{fabric,fabric-modmenu-stub,forge-1.20.1,neoforge}/` | Mod Menu compile-only stub + Forge/NeoForge native Config Screen factories |
| Verification | `logic/`, `e2e-neoforge-1211/`, `scripts/` | JUnit 5 unit + 2,500-round property invariant bombardment, `ArchitectureContractTest`, 10-target JAR build matrix, and packaged-JAR 1.21.1 NeoForge E2E GameTest + ConfigScreen lifecycle suite |

## 2. 10-Target Matrix

| Profile ID | MC | Loader | Generation | Java | Recipe Sync | Lightman Compat | Config Screen |
|---|---|---|---|---|---|---|---|
| `1.20.1-fabric` | 1.20.1 | Fabric | `legacy` | 17 | Yes | No (soft-skipped) | Mod Menu |
| `1.20.1-forge` | 1.20.1 | Forge | `legacy` | 17 | Yes | Yes (`2.3.0.6`) | Forge native |
| `1.21.1-fabric` | 1.21.1 | Fabric | `requirement` | 21 | Yes | No | Mod Menu |
| `1.21.1-neoforge` | 1.21.1 | NeoForge | `requirement` | 21 | Yes | Yes (`2.3.0.6`) | NeoForge native |
| `26.1.2-fabric` | 26.1.2 | Fabric | `shogi` | 25 | Yes | No | Mod Menu |
| `26.1.2-neoforge` | 26.1.2 | NeoForge | `shogi` | 25 | Yes | Yes (`1.0.0.4`) | NeoForge native |
| `26.2-fabric` | 26.2 | Fabric | `shogi` | 25 | Yes | No | Mod Menu |
| `26.2-neoforge` | 26.2 | NeoForge | `shogi` | 25 | Yes | No (not yet released) | NeoForge native |
| `26.3-fabric` | 26.3 | Fabric | `shogi` | 25 | Yes | No | Mod Menu |
| `26.3-neoforge` | 26.3 | NeoForge | `shogi` | 25 | Yes | No (not yet released) | NeoForge native |

## 3. Mixin Inventory (All Soft-Degraded via `required: false` + `MixinGuard`)

| Mixin | Scope | Target | Purpose |
|---|---|---|---|
| `NetworkingMixin` | All 10 | `ModNetworking.initialize` | Register `RecipeSync` & `PhysicsPosSync` packets (and `LegacyTeleportHandler` on 1.20.1) |
| `LegacyPhysicsDistanceMixin` | 1.20.1 | `PlayerWaystoneManager.getExperienceLevelCost` | Resolve physics ship real-world distance for vanilla XP cost |
| `LegacyWaystoneMenuSyncMixin` | 1.20.1 | `WaystoneSelectionMenu.createWaystoneSelection` | Refresh & sync physics Waystone positions before menu open |
| `ItemRequirementsContextMixin` | 1.21.1 | `WarpRequirementsContextImpl.apply/resolve` | Bucket multiple `waystones:item` costs into `CombinedRequirement` |
| `ItemRequirementMixin` | 1.21.1 | `ItemRequirement.canAfford/consume/rollback` | Route single item requirements through `RequirementPayment` |
| `PaymentRequirementMixin` | 1.21.1 | `CombinedRequirement.canAfford/consume/rollback` | Atomic multi-item transaction with rollback |
| `PhysicsRequirementDistanceMixin` | 1.21.1 | `RequirementRegistry.registerDefaults` | Override `distance` & `is_within_distance` with physics real-world distance |
| `PhysicsDistanceComparatorMixin` | 1.21.1, 26.x | `DistanceToPlayerComparator.compare` | Sort Waystones by real-world physics distance |
| `ItemRequirementTooltipMixin` | 1.21.1 (client) | `ItemRequirement.appendHoverText` | Payment/change/wallet/container breakdown & full-inventory warning |
| `CombinedRequirementTooltipMixin` | 1.21.1 (client) | `CombinedRequirement.appendHoverText` | Combined full-inventory-for-change warning |
| `PhysicsWaystoneButtonMixin` | 1.21.1, 26.x (client) | `WaystoneButton.renderDistance` | Render real-world distance for physics ship Waystones |
| `ItemCostMixin` | 26.1.2, 26.2, 26.3 | `ItemCost.apply` | Stateful aggregate `ItemPayment` for Shogi item costs |
| `PhysicsTeleportContextMixin` | 26.1.2, 26.2, 26.3 | `WaystoneTeleportContextImpl.getVariable` | Override `"distance"` variable with physics real-world distance |
| `PhysicsIsWithinDistanceMixin` | 26.1.2, 26.2, 26.3 | `IsWithinDistance.apply` | Evaluate `is_within_distance` using physics real-world distance |
| `ShogiItemTooltipMixin` | 26.1.2, 26.2, 26.3 (client) | `ItemRequirementRenderer.appendHoverText` | Payment/change/wallet/container breakdown & full-inventory warning |
| `LegacyWaystoneButtonMixin` | 1.20.1 (client) | `WaystoneButton.renderWidget` | Custom item cost icon, count, and hover preview tooltip |

## 4. Verification Gates

Enable the local `pre-push` hook once per clone, then run the full 3-gate verification pipeline (natively when JDK 17/21/25 are present, or via `Dockerfile.verify` with `--container`):

```bash
./scripts/setup-hooks.sh
./scripts/verify.sh [--container]
```

| Gate | Implementation | Direct Command | Coverage |
|---|---|---|---|
| **Gate 1: Packaged-JAR E2E** | `e2e-neoforge-1211/` (`WicePackagedJarGameTests`, `ConfigScreenLifecycleTest`) | `python3 scripts/verify-e2e.py` | Boots real MC `1.21.1` NeoForge with real upstream `Waystones` + `Balm` + packaged release `.jar`; verifies emerald block break & change, iron nugget merge, `ShulkerBox` + `Bundle` nested deductions, full-inventory block with red actionbar warning & zero swallowed funds, combined atomic requirements, and headless `WaystonesItemCostConfigScreen` lifecycle |
| **Gate 2: Property Invariant Bombardment** | `logic/.../payment/PaymentConservationPropertyTest.java` | `./gradlew -PlogicOnly=true :logic:test --tests "*PaymentConservationPropertyTest"` | 1,500 fixed-seed payment rounds across standard (`1/9`, `1/9/81`) & non-standard reversible crafting graphs + Shulker/Bundle/full-inventory boundaries (value conservation & zero money swallowing) + 1,000 fixed-seed `CraftingDenominations` graph resolution rounds |
| **Gate 3: Architecture Contract Guardrails** | `logic/.../architecture/ArchitectureContractTest.java` | `./gradlew -PlogicOnly=true :logic:test --tests "*ArchitectureContractTest"` | Scans built release `.jar`s: Dedicated Server isolation (zero client-only refs in non-`client` `.class` constant pools), Mixin bidirectional closure (`*.mixins.json` ↔ `.jar` ↔ `@Mixin` source), and clean package boundary (zero `rinlib` / `com/terraformersmc/modmenu` stub leakage) |

## 5. Assets & Localization

- **Languages**: `en_us`, `zh_cn`, `zh_tw`, `ja_jp` under `assets/waystones_item_cost_extended/lang/`
- **Icon**: `docs/artwork/icon-64.png` (64×64) → `common/src/main/resources/icon.png` (512×512) generated by `tools/draw_icon.py`.

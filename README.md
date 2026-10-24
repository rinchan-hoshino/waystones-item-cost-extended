# Waystones Item Cost Extended

<p align="center"><img src="common/src/main/resources/icon.png" alt="An activated waystone with an emerald and an emerald block" width="224" /></p>

<!-- PROJECT_PAGE_START -->

Waystones Item Cost Extended is a Waystones add-on for compressed and decompressed item payments and combined item costs.

Configure item costs through `warpRequirements` in versions of Waystones that support it.

You can combine several item types in the same payment. For example, charge 3 emeralds and 2 oak logs:

```toml
warpRequirements = [
    "add_item_cost(minecraft:emerald, 3)",
    "add_item_cost(minecraft:oak_log, 2)"
]
```

On Minecraft 26.x, use Waystones’ Shogi syntax:

```toml
warpRequirements = [
    "item_cost('minecraft:emerald', 3)",
    "item_cost('minecraft:oak_log', 2)"
]
```

You can also allow items connected by reversible compression and decompression recipes to be used for payment. Set the following in `config/waystones-item-cost-extended.properties`, then restart the game or server:

```properties
recipePayments=true
```

For example, with a cost of 3 iron ingots, you can pay with 27 iron nuggets or 1 iron block and receive 6 iron ingots in change.

```toml
warpRequirements = ["add_item_cost(minecraft:iron_ingot, 3)"]
```

On Minecraft 26.x:

```toml
warpRequirements = ["item_cost('minecraft:iron_ingot', 3)"]
```

## Lightmans Currency integration

On Minecraft 1.21.1 (NeoForge), Waystones Item Cost Extended provides optional native integration with [Lightmans Currency](https://modrinth.com/mod/lightmans-currency) (1.21-2.3.0.6+). It is never bundled and not required.

When present, registered coins can be used directly in `warpRequirements`:

```toml
warpRequirements = [
    "add_item_cost(lightmanscurrency:coin_iron, 20)",
    "add_item_cost(minecraft:oak_log, 2)"
]
```

- **Coin Selection**: Charges any coin registered in Lightman's native `CoinAPI`, including custom coin chains. Separate coin chains cannot fund one another.
- **Payment Storage**: Evaluates equipped native and Curios wallets via `WalletHandler`, hotbar slots, main inventory, and occupied offhand coin stacks. Bank accounts are not debited, and loose wallets inside inventory slots are not opened.
- **Transaction Safety**: Uses native `CoinContainerMoneyHandler` on snapshot copies of wallet contents and inventory slots. Combined item and coin costs preview together and commit after checking both inventory and wallet snapshots; all changed touched storage is guarded before commit. Native requirement rollback restores snapshots once when storage still matches the committed state; changed touched slots are never overwritten. Creative mode and zero-cost routes bypass payment entirely.
- **Denomination Arithmetic & Change**: Respects configured exchange rates and preserves wallet auto-exchange settings. If change cannot fit into the wallet or main inventory, the charge is refused upfront during preview, preventing partial debits or dropped items.
- **Configuration**: Native coin payments require no toggle. The `recipePayments` setting in `config/waystones-item-cost-extended.properties` defaults to `false` and solely controls reversible crafting conversions.

## Installation requirements

Install the following dependencies:

- [Waystones](https://modrinth.com/mod/waystones)
- [RinLib](https://modrinth.com/mod/rinlib)

## Screenshots

**Combined item costs** — destinations charge different combinations of emeralds and oak logs. Both items are deducted after teleporting.

![Combined item costs before teleporting](https://cdn.modrinth.com/data/CXi2TSAN/images/c9ea8ccab13217e98b4c08eef9807c94d219d170.png)
![Emeralds and oak logs deducted after teleporting](https://cdn.modrinth.com/data/CXi2TSAN/images/188f6c43c9973cacc0e909b2355104b30d90c2e1.png)

**Decompressed item payments** — paying an iron-ingot cost with iron nuggets.

![Iron nuggets before teleporting](https://cdn.modrinth.com/data/CXi2TSAN/images/1054ef8e2a5efa1f5a6a51b54e13c78705f27c05.png)
![Iron nuggets after teleporting](https://cdn.modrinth.com/data/CXi2TSAN/images/180da901591b0962cd490b9110b531392d20dabf.png)

**Compressed item payments** — paying an iron-ingot cost with iron blocks.

![Iron blocks before teleporting](https://cdn.modrinth.com/data/CXi2TSAN/images/77710104e29eeeeb35954463b68c0aeb274cdf63.png)
![Iron blocks and returned ingots after teleporting](https://cdn.modrinth.com/data/CXi2TSAN/images/faf7b25736f0deb5c20fc8a132383c39f39726ef.png)

<!-- PROJECT_PAGE_END -->

package dev.rinchan.rinlibprobe;

import dev.rinchan.waystoneemeralds.payment.ItemPayment;
import io.github.lightman314.lightmanscurrency.api.money.MoneyAPI;
import io.github.lightman314.lightmanscurrency.api.capability.money.MoneyHandler;
import io.github.lightman314.lightmanscurrency.common.attachments.WalletHandler;
import io.github.lightman314.lightmanscurrency.common.items.data.WalletDataWrapper;
import net.blay09.mods.waystones.api.requirement.WarpRequirement;
import net.blay09.mods.waystones.requirement.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.List;
import static dev.rinchan.rinlibprobe.RuntimeProbe.require;

/** Exercises real Lightman wallets and real, transformed Waystones requirements. */
public final class ProfileChecks {
    private static ItemStack item(String name, int count) {
        return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("lightmanscurrency:" + name)), count);
    }

    private static WarpRequirement rules(String... lines) {
        var context = new WarpRequirementsContextImpl(null);
        for (var line : lines) {
            var parsed = RequirementModifierParser.parse(line);
            if (parsed.isEmpty()) throw new IllegalArgumentException(line);
            for (var modifier : parsed.stream().toList()) context.apply(modifier);
        }
        return context.resolve();
    }

    private static long balance(Player player) {
        var api = MoneyAPI.getApi();
        return MoneyHandler.combine(List.of(api.GetPlayersMoneyHandler(player),
            api.GetContainersMoneyHandler(player.getInventory(), player)))
            .getStoredMoney().getRandomValue().getCoreValue();
    }

    public static void run(MinecraftServer server) {
        var player = ProbePlayers.create(server, "NativeCoins", 201);
        var wallet = item("wallet_iron", 1);
        var wrapper = new WalletDataWrapper(wallet);
        var contents = wrapper.getContents();
        contents.setItem(0, item("coin_copper", 3));
        contents.setItem(1, item("coin_iron", 4));
        wrapper.setContents(contents, null);
        WalletHandler.get(player).setWallet(wallet);
        player.getInventory().setItem(0, item("coin_gold", 2));
        player.getInventory().setItem(1, new ItemStack(Items.OAK_LOG, 3));
        require(balance(player) == 243, "native fixture contains 243 copper units");
        var originalWallet = wallet.copy();
        var mixed = rules("add_item_cost(lightmanscurrency:coin_iron, 20)", "add_item_cost(minecraft:oak_log, 2)");
        require(mixed.canAfford(player), "wallet and loose higher denominations jointly cover mixed costs");
        require(balance(player) == 243 && player.getInventory().getItem(1).getCount() == 3,
            "affordability preview never mutates real funds or ordinary items");
        Actions.consume(mixed, player);
        require(balance(player) == 43 && player.getInventory().getItem(1).getCount() == 1,
            "native payment charges twenty iron and two logs with exact change");
        Actions.rollback(mixed, player);
        Actions.rollback(mixed, player);
        require(balance(player) == 243 && player.getInventory().getItem(1).getCount() == 3,
            "rollback refunds combined payment only once");
        require(ItemStack.matches(originalWallet, WalletHandler.get(player).getWallet()),
            "rollback preserves original wallet contents and settings");
        var tooMuch = rules("add_item_cost(lightmanscurrency:coin_iron, 20)", "add_item_cost(lightmanscurrency:coin_gold, 1)");
        require(!tooMuch.canAfford(player) && balance(player) == 243,
            "different coin denominations cannot spend the same funds twice");
        var missingItem = rules("add_item_cost(lightmanscurrency:coin_iron, 20)", "add_item_cost(minecraft:diamond, 1)");
        require(!missingItem.canAfford(player) && balance(player) == 243,
            "missing noncurrency requirement leaves all native money untouched");
        var payment = new ItemPayment(player);
        require(payment.charge(item("coin_iron", 1), 20), "wallet debit can be planned");
        WalletHandler.get(player).setWallet(ItemStack.EMPTY);
        require(!payment.commit() && player.getInventory().getItem(0).getCount() == 2,
            "wallet replacement invalidates the whole commit before any inventory debit");
        WalletHandler.get(player).setWallet(originalWallet.copy());
        player.getAbilities().instabuild = true;
        Actions.consume(mixed, player);
        require(balance(player) == 243 && player.getInventory().getItem(1).getCount() == 3,
            "creative payment leaves wallet and inventory untouched");
        player.getAbilities().instabuild = false;
        var free = new ItemRequirement(item("coin_iron", 1), 0);
        Actions.consume(free, player);
        Actions.rollback(free, player);
        require(balance(player) == 243, "zero-cost payment neither creates nor spends currency");
        var quoted = new ItemPayment(player);
        require(quoted.available(item("coin_copper", 1)) == 243
            && quoted.available(item("coin_iron", 1)) == 24
            && quoted.available(item("coin_gold", 1)) == 2,
            "available amounts use each configured denomination without modifying funds");
        var poor = ProbePlayers.create(server, "NineCopper", 202);
        poor.getInventory().setItem(0, item("coin_copper", 9));
        var insufficient = new ItemPayment(poor);
        require(!insufficient.charge(item("coin_iron", 1), 1) && balance(poor) == 9,
            "insufficient balance cannot partially spend nine copper");
        require(!insufficient.charge(item("coin_iron", 1), -1), "negative currency requests are rejected");
        var full = ProbePlayers.create(server, "FullSlots", 203);
        for (int slot = 0; slot < 36; slot++) full.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
        full.getInventory().setItem(0, item("coin_gold", 64));
        var noRoom = new ItemPayment(full);
        require(!noRoom.charge(item("coin_iron", 1), 1) && full.getInventory().getItem(0).getCount() == 64,
            "unplaceable change refuses payment without dropping or deleting funds");
        require(full.getInventory().getItem(36).isEmpty() && full.getInventory().getItem(40).isEmpty(),
            "change never uses empty armor or offhand slots");
        var hand = ProbePlayers.create(server, "OffhandCoin", 204);
        hand.getInventory().setItem(40, item("coin_gold", 1));
        var handPayment = new ItemPayment(hand);
        require(handPayment.available(item("coin_iron", 1)) == 10 && handPayment.charge(item("coin_iron", 1), 3)
            && handPayment.commit() && balance(hand) == 70,
            "offhand coins participate once and return the exact change");
        require(handPayment.rollback() && balance(hand) == 100, "offhand payment can be refunded");
        customChain(server);
        physicalTeleport(server);
    }

    private static void customChain(MinecraftServer server) {
        var path = java.nio.file.Path.of(io.github.lightman314.lightmanscurrency.api.money.coins.CoinAPI.MONEY_FILE_LOCATION);
        try {
            String original = java.nio.file.Files.readString(path);
            try {
                var document = com.google.gson.JsonParser.parseString(original).getAsJsonObject();
                document.getAsJsonArray("Chains").add(com.google.gson.JsonParser.parseString("""
                    {"chain":"probe","name":{"text":"Probe"},"displayType":"lightmanscurrency:coin",
                    "InputType":"DEFAULT","CoreChain":[{"Coin":"minecraft:amethyst_shard","baseUnit":true},
                    {"Coin":"minecraft:diamond","exchangeRate":7}]}
                    """));
                java.nio.file.Files.writeString(path, document.toString());
                io.github.lightman314.lightmanscurrency.api.money.coins.CoinAPI.getApi().ReloadCoinDataFromFile(false);
                var player = ProbePlayers.create(server, "CustomChain", 205);
                player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 1));
                player.getInventory().setItem(1, new ItemStack(Items.AMETHYST_SHARD, 4));
                var payment = new ItemPayment(player);
                require(payment.available(new ItemStack(Items.AMETHYST_SHARD)) == 11,
                    "custom coin chains use configured seven-to-one rates");
                require(!payment.charge(item("coin_iron", 1), 1), "unrelated currency chains cannot pay each other");
                require(payment.charge(new ItemStack(Items.AMETHYST_SHARD), 10) && payment.commit(),
                    "configured coins outside the Lightman namespace can pay");
                require(new ItemPayment(player).available(new ItemStack(Items.AMETHYST_SHARD)) == 1,
                    "custom-chain change conserves the configured value");
                require(payment.rollback() && player.getInventory().getItem(0).is(Items.DIAMOND)
                    && player.getInventory().getItem(1).getCount() == 4, "custom-chain refund restores original items");
            } finally {
                java.nio.file.Files.writeString(path, original);
                io.github.lightman314.lightmanscurrency.api.money.coins.CoinAPI.getApi().ReloadCoinDataFromFile(false);
            }
        } catch (java.io.IOException error) { throw new java.io.UncheckedIOException(error); }
    }

    private static void physicalTeleport(MinecraftServer server) {
        var player = ProbePlayers.create(server, "CoinTeleport", 206);
        player.getInventory().setItem(0, item("coin_gold", 2));
        player.getInventory().setItem(1, new ItemStack(Items.OAK_LOG, 3));
        var level = server.overworld();
        var position = new net.minecraft.core.BlockPos(32, 81, 0);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++)
            level.setBlock(position.offset(dx, -1, dz), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), 3);
        var state = net.blay09.mods.waystones.block.ModBlocks.waystone.defaultBlockState();
        level.setBlock(position, state, 3);
        level.setBlock(position.above(), state.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,
            net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER), 3);
        var block = (net.blay09.mods.waystones.block.entity.WaystoneBlockEntityBase) level.getBlockEntity(position);
        block.initializeWaystone(level, player, net.blay09.mods.waystones.api.WaystoneOrigin.PLAYER);
        var teleport = new net.blay09.mods.waystones.core.WaystoneTeleportContextImpl(player, block.getWaystone());
        teleport.setRequirements(rules("add_item_cost(lightmanscurrency:coin_iron, 15)", "add_item_cost(minecraft:oak_log, 2)"));
        teleport.setPlaysEffect(false).setPlaysSound(false);
        net.blay09.mods.waystones.core.WaystoneTeleportManager.tryTeleport(teleport);
        require(player.getX() > 30 && player.getX() < 35, "native paid teleport reaches the physical waystone");
        require(balance(player) == 50 && player.getInventory().getItem(1).getCount() == 1,
            "completed native teleport charges coins and ordinary items exactly once");
    }
}

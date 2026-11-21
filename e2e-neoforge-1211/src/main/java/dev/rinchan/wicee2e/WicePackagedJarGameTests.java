package dev.rinchan.wicee2e;

import com.mojang.authlib.GameProfile;
import com.mojang.datafixers.util.Either;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.blay09.mods.waystones.api.Waystone;
import net.blay09.mods.waystones.api.WaystoneOrigin;
import net.blay09.mods.waystones.api.WaystoneStyles;
import net.blay09.mods.waystones.api.WaystonesAPI;
import net.blay09.mods.waystones.api.error.WaystoneTeleportError;
import net.blay09.mods.waystones.config.WaystonesConfig;
import net.blay09.mods.waystones.core.WaystoneImpl;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.joml.Vector3d;

/**
 * Packaged-JAR black-box E2E GameTests running on real Minecraft 1.21.1 NeoForge with
 * real upstream Waystones + Balm + the actual built waystones-item-cost-extended release JAR.
 *
 * ZERO compile dependency on waystones-item-cost-extended internals; every payment scenario
 * executes end-to-end through WaystonesAPI.createDefaultTeleportContext + WaystonesAPI.tryTeleport.
 */
@GameTestHolder(WiceE2EHarnessMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class WicePackagedJarGameTests {
    private static final String TEMPLATE = "empty";

    private static final class RecordingTestPlayer extends FakePlayer {
        private final List<CapturedMessage> messages = new ArrayList<>();

        private record CapturedMessage(Component component, boolean actionBar) {}

        private RecordingTestPlayer(ServerLevel level, String name) {
            super(level, new GameProfile(UUID.randomUUID(), name));
            this.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
            this.getAbilities().instabuild = false;
            this.getAbilities().mayfly = false;
        }

        @Override
        public void displayClientMessage(Component message, boolean actionBar) {
            messages.add(new CapturedMessage(message, actionBar));
        }

        private List<CapturedMessage> capturedMessages() {
            return messages;
        }

        private void clearMessages() {
            messages.clear();
        }
    }

    private record WaystonePair(Waystone source, Waystone target) {}

    private static WaystonePair placeTwoWaystones(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos srcPos = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos dstPos = helper.absolutePos(new BlockPos(3, 1, 3));
        Waystone src = WaystonesAPI.placeWaystone(level, srcPos, WaystoneStyles.DEFAULT)
            .orElseThrow(() -> new AssertionError("Failed to place source Waystone at " + srcPos));
        Waystone dst = WaystonesAPI.placeWaystone(level, dstPos, WaystoneStyles.DEFAULT)
            .orElseThrow(() -> new AssertionError("Failed to place target Waystone at " + dstPos));
        return new WaystonePair(src, dst);
    }

    private static RecordingTestPlayer createPlayerAtSource(GameTestHelper helper, WaystonePair pair, String name) {
        RecordingTestPlayer player = new RecordingTestPlayer(helper.getLevel(), name);
        BlockPos srcPos = pair.source().getPos();
        player.setPos(srcPos.getX() + 0.5, srcPos.getY(), srcPos.getZ() + 0.5);
        return player;
    }

    private static boolean tryRealWaystoneTeleport(RecordingTestPlayer player, WaystonePair pair, List<String> warpRequirements) {
        var teleports = WaystonesConfig.getActive().teleports;
        boolean prevEnableCosts = teleports.enableCosts;
        boolean prevEnableCooldowns = teleports.enableCooldowns;
        List<String> prevRequirements = teleports.warpRequirements;
        try {
            teleports.enableCosts = true;
            teleports.enableCooldowns = false;
            teleports.warpRequirements = new ArrayList<>(warpRequirements);

            var ctxEither = WaystonesAPI.createDefaultTeleportContext(
                player,
                pair.target(),
                ctx -> ctx.setFromWaystone(pair.source())
            );
            var ctx = ctxEither.left().orElseThrow(() ->
                new AssertionError("Failed to build WaystoneTeleportContext: " + ctxEither.right()));
            var result = WaystonesAPI.tryTeleport(ctx);
            return result.left().isPresent();
        } finally {
            teleports.enableCosts = prevEnableCosts;
            teleports.enableCooldowns = prevEnableCooldowns;
            teleports.warpRequirements = prevRequirements;
        }
    }

    private static int countInMainInventory(Inventory inv, Item item) {
        int total = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    @GameTest(template = TEMPLATE)
    public static void packagedJarLoadedAndMixinsActive(GameTestHelper helper) {
        helper.assertTrue(ModList.get().isLoaded("balm"), "Upstream Balm must be loaded");
        helper.assertTrue(ModList.get().isLoaded("waystones"), "Upstream Waystones must be loaded");
        helper.assertTrue(ModList.get().isLoaded("waystones_item_cost_extended"), "Packaged WICE mod must be loaded");

        var modFile = ModList.get().getModFileById("waystones_item_cost_extended");
        helper.assertTrue(modFile != null, "ModFile for waystones_item_cost_extended must exist");
        String filePath = modFile.getFile().getFilePath().toString();
        helper.assertTrue(filePath.endsWith(".jar"),
            "E2E must execute against the real packaged .jar, not exploded classes; got: " + filePath);

        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void emeraldBlockBreaksIntoEmeraldChange(GameTestHelper helper) {
        var pair = placeTwoWaystones(helper);
        var player = createPlayerAtSource(helper, pair, "E2EBlockBreak");
        player.getInventory().setItem(0, new ItemStack(Items.EMERALD_BLOCK, 1));

        boolean teleported = tryRealWaystoneTeleport(player, pair, List.of("add_item_cost(minecraft:emerald, 2)"));
        helper.assertTrue(teleported, "Teleport with 1 emerald block for 2 emerald cost must succeed");

        int blocksLeft = countInMainInventory(player.getInventory(), Items.EMERALD_BLOCK);
        int emeraldsLeft = countInMainInventory(player.getInventory(), Items.EMERALD);
        helper.assertTrue(blocksLeft == 0, "Expected 0 emerald blocks left, got " + blocksLeft);
        helper.assertTrue(emeraldsLeft == 7, "Expected 7 emerald change (9 - 2), got " + emeraldsLeft);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void ironNuggetsMergeToPayIngotCost(GameTestHelper helper) {
        var pair = placeTwoWaystones(helper);
        var player = createPlayerAtSource(helper, pair, "E2ENuggetMerge");
        player.getInventory().setItem(0, new ItemStack(Items.IRON_NUGGET, 20));

        boolean teleported = tryRealWaystoneTeleport(player, pair, List.of("add_item_cost(minecraft:iron_ingot, 2)"));
        helper.assertTrue(teleported, "Teleport with 20 iron nuggets for 2 iron ingot (18 nuggets) cost must succeed");

        int nuggetsLeft = countInMainInventory(player.getInventory(), Items.IRON_NUGGET);
        helper.assertTrue(nuggetsLeft == 2, "Expected 2 iron nuggets left (20 - 18), got " + nuggetsLeft);
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void nestedShulkerBoxAndBundlePaymentWithChange(GameTestHelper helper) {
        var pair = placeTwoWaystones(helper);
        var player = createPlayerAtSource(helper, pair, "E2ENested");

        // Slot 0: real Bundle holding 1x emerald_block (value 9)
        ItemStack bundle = new ItemStack(Items.BUNDLE);
        bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(List.of(new ItemStack(Items.EMERALD_BLOCK, 1))));
        player.getInventory().setItem(0, bundle);

        // Slot 1: real Shulker Box holding 2x emerald (value 2)
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.EMERALD, 2))));
        player.getInventory().setItem(1, shulker);

        boolean teleported = tryRealWaystoneTeleport(player, pair, List.of("add_item_cost(minecraft:emerald, 5)"));
        helper.assertTrue(teleported, "Teleport funded by Bundle (1 block) + ShulkerBox (2 emeralds) for 5 emerald cost must succeed");

        // Verify change = 9 + 2 - 5 = 6 emeralds in main inventory
        int emeraldsInMain = countInMainInventory(player.getInventory(), Items.EMERALD);
        helper.assertTrue(emeraldsInMain == 6, "Expected 6 emerald change in main inventory, got " + emeraldsInMain);

        // Verify Bundle and Shulker Box were both deducted
        BundleContents bundleAfter = player.getInventory().getItem(0).get(DataComponents.BUNDLE_CONTENTS);
        helper.assertTrue(bundleAfter == null || bundleAfter.isEmpty(), "Bundle should be empty after deduction");

        ItemContainerContents shulkerAfter = player.getInventory().getItem(1).get(DataComponents.CONTAINER);
        boolean shulkerHasEmerald = shulkerAfter != null
            && shulkerAfter.stream().anyMatch(s -> !s.isEmpty() && (s.is(Items.EMERALD) || s.is(Items.EMERALD_BLOCK)));
        helper.assertTrue(!shulkerHasEmerald, "Shulker Box should have its emeralds deducted");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void fullInventoryBlocksChangeWithRedWarningAndNeverSwallowsMoney(GameTestHelper helper) {
        var pair = placeTwoWaystones(helper);
        var player = createPlayerAtSource(helper, pair, "E2EFullInv");

        // Fill all 36 main slots: slot 0 = 2x emerald_block, slots 1..35 = 64x cobblestone
        player.getInventory().setItem(0, new ItemStack(Items.EMERALD_BLOCK, 2));
        for (int slot = 1; slot < 36; slot++) {
            player.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        // Armor and offhand remain empty (must never receive change)
        player.clearMessages();

        boolean blockedResult = tryRealWaystoneTeleport(player, pair, List.of("add_item_cost(minecraft:emerald, 2)"));
        helper.assertTrue(!blockedResult, "Teleport must be BLOCKED when main inventory has no space for 7 emerald change");

        // Zero money swallowed!
        helper.assertTrue(player.getInventory().getItem(0).is(Items.EMERALD_BLOCK)
                && player.getInventory().getItem(0).getCount() == 2,
            "Blocked payment must NOT deduct emerald blocks");
        helper.assertTrue(player.getInventory().getItem(40).isEmpty(),
            "Offhand must remain empty on blocked payment");

        // Must have emitted red-styled actionbar warning message
        boolean sawRedWarning = player.capturedMessages().stream().anyMatch(m -> {
            if (!m.actionBar()) return false;
            TextColor color = m.component().getStyle().getColor();
            boolean isRed = color != null && color.equals(TextColor.fromLegacyFormat(ChatFormatting.RED));
            boolean isFullKey = m.component().getContents() instanceof TranslatableContents tc
                && tc.getKey().equals("message.waystones_item_cost_extended.inventory_full_for_change")
                && tc.getArgs().length == 1
                && Integer.valueOf(1).equals(tc.getArgs()[0]);
            return isRed && isFullKey;
        });
        helper.assertTrue(sawRedWarning,
            "Expected RED actionbar warning message.waystones_item_cost_extended.inventory_full_for_change(1); got: "
                + player.capturedMessages());

        // Free 1 slot (slot 35) -> retry must SUCCEED with exact change!
        player.getInventory().setItem(35, ItemStack.EMPTY);
        boolean retryResult = tryRealWaystoneTeleport(player, pair, List.of("add_item_cost(minecraft:emerald, 2)"));
        helper.assertTrue(retryResult, "Teleport must succeed once 1 slot is freed for change");
        helper.assertTrue(player.getInventory().getItem(0).getCount() == 1,
            "Expected 1 emerald block left in slot 0 after retry");
        helper.assertTrue(player.getInventory().getItem(35).is(Items.EMERALD)
                && player.getInventory().getItem(35).getCount() == 7,
            "Expected 7 emeralds change in freed slot 35");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void combinedMultiItemRequirementAtomic(GameTestHelper helper) {
        var pair = placeTwoWaystones(helper);
        var player = createPlayerAtSource(helper, pair, "E2ECombined");
        player.getInventory().setItem(0, new ItemStack(Items.EMERALD_BLOCK, 1));
        player.getInventory().setItem(1, new ItemStack(Items.IRON_NUGGET, 9));

        boolean teleported = tryRealWaystoneTeleport(player, pair, List.of(
            "add_item_cost(minecraft:emerald, 2)",
            "add_item_cost(minecraft:iron_ingot, 1)"
        ));
        helper.assertTrue(teleported, "Combined multi-item requirement teleport must succeed");

        helper.assertTrue(countInMainInventory(player.getInventory(), Items.EMERALD_BLOCK) == 0,
            "Emerald block should be consumed");
        helper.assertTrue(countInMainInventory(player.getInventory(), Items.IRON_NUGGET) == 0,
            "9 iron nuggets should be consumed for 1 iron ingot cost");
        helper.assertTrue(countInMainInventory(player.getInventory(), Items.EMERALD) == 7,
            "Expected 7 emerald change");
        helper.succeed();
    }

    @GameTest(template = TEMPLATE)
    public static void physicsAirshipWaystoneChargesRealWorldDistanceNotPlotDistance(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var pair = placeTwoWaystones(helper);
        var player = createPlayerAtSource(helper, pair, "E2EPhysicsShip");
        player.setPos(0.5, 70.0, 0.5);

        // Simulated Sable/VS2 airship Waystone stored in remote Plot coordinates (~28,963,235 blocks away)
        UUID shipWaystoneUid = UUID.randomUUID();
        Waystone shipWaystone = new WaystoneImpl(
            ResourceLocation.fromNamespaceAndPath("waystones", "waystone"),
            shipWaystoneUid,
            level.dimension(),
            new BlockPos(20_480_100, 70, 20_480_100),
            WaystoneOrigin.PLAYER,
            player.getUUID()
        );

        // Register its known real-world ship coordinate (120, 70, -80) (~144 blocks from player) via packaged JAR API
        try {
            Class<?> resolverClass = Class.forName("dev.rinchan.waystonesitemcost.physics.PhysicsPosResolver");
            Method recordMethod = resolverClass.getMethod(
                "recordServerPosition",
                MinecraftServer.class, UUID.class, ResourceKey.class, double.class, double.class, double.class
            );
            recordMethod.invoke(null, level.getServer(), shipWaystoneUid, level.dimension(), 120.0, 70.0, -80.0);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke PhysicsPosResolver.recordServerPosition in packaged JAR", e);
        }

        // Player holds 1 Emerald Block (9 emeralds) - enough for ~144-block fee, nowhere near 28.96M-block fee!
        player.getInventory().setItem(0, new ItemStack(Items.EMERALD_BLOCK, 1));

        var teleports = WaystonesConfig.getActive().teleports;
        boolean prevEnableCosts = teleports.enableCosts;
        boolean prevEnableCooldowns = teleports.enableCooldowns;
        List<String> prevRequirements = teleports.warpRequirements;
        try {
            teleports.enableCosts = true;
            teleports.enableCooldowns = false;
            teleports.warpRequirements = new ArrayList<>(List.of(
                "add_item_cost(minecraft:emerald, 1)",
                "scaled_add_item_cost(distance, minecraft:emerald, 0.01)",
                "[is_not_within_distance(500)] add_item_cost(minecraft:emerald, 9999)"
            ));

            var ctxEither = WaystonesAPI.createUncheckedDefaultTeleportContext(
                player,
                shipWaystone,
                ctx -> ctx.setFromWaystone(pair.source())
            );
            var ctx = ctxEither.left().orElseThrow(() ->
                new AssertionError("Failed to create teleport context for physics ship waystone: " + ctxEither.right()));

            var requirement = WaystonesAPI.resolveRequirements(ctx);
            helper.assertTrue(requirement.canAfford(player),
                "Physics airship Waystone at Plot (20480100,70,20480100) with real-world (120,70,-80) must be affordable with 1 emerald block (real ~144-block fee, NOT 28.96M plot fee!)");

            requirement.consume(player);
            int blocksLeft = countInMainInventory(player.getInventory(), Items.EMERALD_BLOCK);
            int emeraldsLeft = countInMainInventory(player.getInventory(), Items.EMERALD);
            int totalValueLeft = blocksLeft * 9 + emeraldsLeft;
            int totalPaid = 9 - totalValueLeft;
            helper.assertTrue(totalPaid >= 1 && totalPaid <= 4,
                "Expected realistic near-distance fee of 1..4 emeralds for ~144 blocks, but paid " + totalPaid
                    + " (blocksLeft=" + blocksLeft + ", emeraldsLeft=" + emeraldsLeft + ")");
        } finally {
            teleports.enableCosts = prevEnableCosts;
            teleports.enableCooldowns = prevEnableCooldowns;
            teleports.warpRequirements = prevRequirements;
        }
        helper.succeed();
    }

    @GameTest(template = TEMPLATE, timeoutTicks = 200)
    public static void sableRealAirshipWaystoneDistanceAndUnloadedWakeup(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var pair = placeTwoWaystones(helper);
        var player = createPlayerAtSource(helper, pair, "E2ESableAirship");
        BlockPos srcPos = pair.source().getPos();
        double targetWorldX = srcPos.getX() + 120.5;
        double targetWorldY = srcPos.getY() + 0.5;
        double targetWorldZ = srcPos.getZ() - 80.5;

        if (!ModList.get().isLoaded("sable")) {
            // Standard CI run without Sable JAR: verify tryTeleportAsync succeeds cleanly with zero regressions.
            player.getInventory().setItem(0, new ItemStack(Items.EMERALD_BLOCK, 1));
            var ctx = WaystonesAPI.createDefaultTeleportContext(player, pair.target(), c -> c.setFromWaystone(pair.source()))
                .left()
                .orElseThrow(() -> new AssertionError("Failed to build async teleport context"));
            AtomicReference<Either<List<Entity>, WaystoneTeleportError>> resultRef = new AtomicReference<>();
            WaystonesAPI.tryTeleportAsync(ctx).thenAccept(resultRef::set);
            helper.succeedWhen(() -> {
                var res = resultRef.get();
                helper.assertTrue(res != null, "Waiting for async teleport completion");
                helper.assertTrue(res.left().isPresent(), "Async teleport failed: " + res.right());
            });
            return;
        }

        try {
            // 1. Verify PhysicsModBridge detected real Sable 2.0.6 with zero signature mismatches
            Class<?> bridgeClass = Class.forName("dev.rinchan.waystonesitemcost.physics.PhysicsModBridge");
            boolean sableLoaded = (boolean) bridgeClass.getMethod("isSableLoaded").invoke(null);
            helper.assertTrue(sableLoaded, "PhysicsModBridge.isSableLoaded() MUST be true when real Sable 2.0.6 JAR is loaded!");

            // 2. Allocate a real Sable ServerSubLevel at world coordinates (120.5, 70.5, -80.5)
            // Use MethodHandles.publicLookup() instead of Class.getMethod to avoid resolving ClientLevel sibling overloads on Dedicated Server.
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Class<?> containerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer");
            Class<?> subLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel");
            Class<?> poseClass = Class.forName("dev.ryanhcode.sable.companion.math.Pose3d");
            Object container = lookup.findStatic(containerClass, "getContainer", MethodType.methodType(containerClass, Level.class))
                .invoke(level);
            helper.assertTrue(container != null, "Sable SubLevelContainer.getContainer(level) must not be null");

            Object pose = poseClass.getDeclaredConstructor().newInstance();
            Object subLevel = lookup.findVirtual(containerClass, "allocateNewSubLevel", MethodType.methodType(subLevelClass, poseClass))
                .invoke(container, pose);
            helper.assertTrue(subLevel != null, "allocateNewSubLevel must return a ServerSubLevel");

            Object plot = subLevel.getClass().getMethod("getPlot").invoke(subLevel);
            BlockPos plotCenter = (BlockPos) plot.getClass().getMethod("getCenterBlock").invoke(plot);
            BlockPos plotWaystonePos = new BlockPos(plotCenter.getX(), 70, plotCenter.getZ());

            // Ensure the Plot chunk exists in Sable before placing blocks inside it
            Class<?> levelPlotClass = Class.forName("dev.ryanhcode.sable.sublevel.plot.LevelPlot");
            ChunkPos plotChunk = new ChunkPos(plotWaystonePos);
            lookup.findVirtual(levelPlotClass, "newEmptyChunk", MethodType.methodType(void.class, ChunkPos.class))
                .invoke(plot, plotChunk);

            // Build a small 3x3 stone deck inside the Sable Plot and place a real Waystone on it
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    level.setBlockAndUpdate(plotWaystonePos.offset(dx, -1, dz), Blocks.STONE.defaultBlockState());
                }
            }
            Waystone shipWaystone = WaystonesAPI.placeWaystone(level, plotWaystonePos, WaystoneStyles.DEFAULT)
                .orElseThrow(() -> new AssertionError("Failed to place real Waystone inside Sable Plot at " + plotWaystonePos));

            // Configure Pose3d AFTER block placement so plotWaystonePos projects directly to world (targetWorldX, targetWorldY, targetWorldZ)
            Object logicalPose = subLevel.getClass().getMethod("logicalPose").invoke(subLevel);
            Vector3d rotPoint = (Vector3d) logicalPose.getClass().getMethod("rotationPoint").invoke(logicalPose);
            Vector3d worldPosVec = (Vector3d) logicalPose.getClass().getMethod("position").invoke(logicalPose);
            rotPoint.set(plotWaystonePos.getX() + 0.5, plotWaystonePos.getY() + 0.5, plotWaystonePos.getZ() + 0.5);
            worldPosVec.set(targetWorldX, targetWorldY, targetWorldZ);
            subLevel.getClass().getMethod("forceUpdateGlobalBounds").invoke(subLevel);

            // 3. Verify live Sable Plot detection and exact real-world projection
            boolean inPlot = (boolean) bridgeClass.getMethod("isInPhysicsSpace", Level.class, double.class, double.class, double.class)
                .invoke(null, level, plotWaystonePos.getX() + 0.5, plotWaystonePos.getY() + 0.5, plotWaystonePos.getZ() + 0.5);
            helper.assertTrue(inPlot, "isInPhysicsSpace must return true for Sable Plot pos " + plotWaystonePos);

            Object projected = bridgeClass.getMethod("projectToWorld", Level.class, double.class, double.class, double.class)
                .invoke(null, level, plotWaystonePos.getX() + 0.5, plotWaystonePos.getY() + 0.5, plotWaystonePos.getZ() + 0.5);
            helper.assertTrue(projected != null, "projectToWorld must project live Sable Plot pos");
            double px = (double) projected.getClass().getMethod("x").invoke(projected);
            double py = (double) projected.getClass().getMethod("y").invoke(projected);
            double pz = (double) projected.getClass().getMethod("z").invoke(projected);
            helper.assertTrue(Math.abs(px - targetWorldX) < 1.0 && Math.abs(py - targetWorldY) < 1.0 && Math.abs(pz - targetWorldZ) < 1.0,
                "Expected projected world pos ~(" + targetWorldX + ", " + targetWorldY + ", " + targetWorldZ + "), got (" + px + ", " + py + ", " + pz + ")");

            // 4. Refresh server cache & verify realistic distance cost (~144 blocks -> 1..4 emeralds, NOT 57957!)
            Class<?> resolverClass = Class.forName("dev.rinchan.waystonesitemcost.physics.PhysicsPosResolver");
            resolverClass.getMethod("refreshWaystoneOnServer", MinecraftServer.class, UUID.class, ResourceKey.class, BlockPos.class)
                .invoke(null, level.getServer(), shipWaystone.getWaystoneUid(), shipWaystone.getDimension(), shipWaystone.getPos());

            player.getInventory().setItem(0, new ItemStack(Items.EMERALD_BLOCK, 1));
            var teleports = WaystonesConfig.getActive().teleports;
            boolean prevEnableCosts = teleports.enableCosts;
            boolean prevEnableCooldowns = teleports.enableCooldowns;
            List<String> prevRequirements = teleports.warpRequirements;
            teleports.enableCosts = true;
            teleports.enableCooldowns = false;
            teleports.warpRequirements = new ArrayList<>(List.of(
                "add_item_cost(minecraft:emerald, 1)",
                "scaled_add_item_cost(distance, minecraft:emerald, 0.01)",
                "[is_not_within_distance(500)] add_item_cost(minecraft:emerald, 9999)"
            ));

            var ctx = WaystonesAPI.createUncheckedDefaultTeleportContext(player, shipWaystone, c -> c.setFromWaystone(pair.source()))
                .left()
                .orElseThrow(() -> new AssertionError("Failed to create teleport context for Sable airship waystone"));
            var req = WaystonesAPI.resolveRequirements(ctx);
            helper.assertTrue(req.canAfford(player),
                "Real Sable airship Waystone must be affordable with 1 emerald block (~144m real distance)");

            // 5. Simulate long-unloaded airship chunk: move ServerSubLevel into Sable's SubLevelHoldingChunkMap
            ChunkPos worldChunk = new ChunkPos(BlockPos.containing(targetWorldX, targetWorldY, targetWorldZ));
            Class<?> serverContainerClass = Class.forName("dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer");
            Class<?> holdingMapClass = Class.forName("dev.ryanhcode.sable.sublevel.storage.holding.SubLevelHoldingChunkMap");
            Object holdingMap = lookup.findVirtual(serverContainerClass, "getHoldingChunkMap", MethodType.methodType(holdingMapClass))
                .invoke(container);
            Class<?> serverSubLevelClass = Class.forName("dev.ryanhcode.sable.sublevel.ServerSubLevel");
            lookup.findVirtual(holdingMapClass, "moveToUnloaded", MethodType.methodType(void.class, serverSubLevelClass, ChunkPos.class))
                .invoke(holdingMap, subLevel, worldChunk);

            // Confirm the SubLevel is genuinely unloaded (projectToWorld == null; plot chunk itself may remain cached in test ServerChunkCache)
            Object afterUnloadProj = bridgeClass.getMethod("projectToWorld", Level.class, double.class, double.class, double.class)
                .invoke(null, level, plotWaystonePos.getX() + 0.5, plotWaystonePos.getY() + 0.5, plotWaystonePos.getZ() + 0.5);
            helper.assertTrue(afterUnloadProj == null, "SubLevel must be unloaded before wakeup test");

            // 6. Execute real WaystonesAPI.tryTeleportAsync -> must wake SubLevel, pass isValidInLevel, deduct realistic fee, and succeed!
            AtomicReference<Either<List<Entity>, WaystoneTeleportError>> resultRef = new AtomicReference<>();
            WaystonesAPI.tryTeleportAsync(ctx).thenAccept(resultRef::set);

            helper.succeedWhen(() -> {
                var res = resultRef.get();
                helper.assertTrue(res != null, "Waiting for Sable unloaded-airship async teleport completion");
                try {
                    helper.assertTrue(res.left().isPresent(),
                        "Unloaded Sable airship teleport MUST succeed without MissingWaystone! Error: " + res.right());
                    Object afterWakeProj = bridgeClass.getMethod("projectToWorld", Level.class, double.class, double.class, double.class)
                        .invoke(null, level, plotWaystonePos.getX() + 0.5, plotWaystonePos.getY() + 0.5, plotWaystonePos.getZ() + 0.5);
                    helper.assertTrue(afterWakeProj != null,
                        "SubLevel must be woken up and projected again after teleport");
                    int blocksLeft = countInMainInventory(player.getInventory(), Items.EMERALD_BLOCK);
                    int emeraldsLeft = countInMainInventory(player.getInventory(), Items.EMERALD);
                    int paid = 9 - (blocksLeft * 9 + emeraldsLeft);
                    helper.assertTrue(paid >= 1 && paid <= 4,
                        "Expected realistic fee 1..4 emeralds for ~144m airship teleport, paid " + paid);
                } catch (Throwable t) {
                    if (t instanceof AssertionError ae) {
                        throw ae;
                    }
                    throw new AssertionError("Failed to verify post-wakeup Sable projection", t);
                } finally {
                    teleports.enableCosts = prevEnableCosts;
                    teleports.enableCooldowns = prevEnableCooldowns;
                    teleports.warpRequirements = prevRequirements;
                }
            });
        } catch (Throwable e) {
            if (e instanceof AssertionError ae) {
                throw ae;
            }
            throw new AssertionError("Real Sable 2.0.6 E2E reflection setup failed", e);
        }
    }
}

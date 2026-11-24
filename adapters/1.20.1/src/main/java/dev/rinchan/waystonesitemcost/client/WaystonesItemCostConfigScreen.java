package dev.rinchan.waystonesitemcost.client;

import dev.rinchan.waystonesitemcost.config.PaymentConfig;
import dev.rinchan.waystonesitemcost.config.WaystonesRuleDetector;
import dev.rinchan.waystonesitemcost.minecraft.RecipeAccess;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public final class WaystonesItemCostConfigScreen extends Screen {
    private final Screen parent;
    private boolean recipePayments;
    private boolean searchNested;
    private boolean showTooltip;
    private boolean fixPhysicsShipDistance;
    private boolean legacyEnable;
    private boolean legacyWaiveXp;
    private long copiedUntilMs;

    public WaystonesItemCostConfigScreen(Screen parent) {
        super(Component.translatable("screen.waystones_item_cost_extended.config.title"));
        this.parent = parent;
        var data = PaymentConfig.get();
        this.recipePayments = data.recipePayments();
        this.searchNested = data.searchNestedContainers();
        this.showTooltip = data.showPaymentPreviewTooltip();
        this.fixPhysicsShipDistance = data.fixPhysicsShipDistance();
        this.legacyEnable = data.legacyEnableCustomItemCosts();
        this.legacyWaiveXp = data.legacyWaiveVanillaXpCost();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int w = Math.min(290, Math.max(240, this.width - 40));
        int x = centerX - w / 2;
        int y = 50;

        addRenderableWidget(CycleButton.onOffBuilder(recipePayments)
            .create(x, y, w, 20, Component.translatable("screen.waystones_item_cost_extended.config.recipe_payments"),
                (btn, val) -> recipePayments = val));
        y += 22;

        addRenderableWidget(CycleButton.onOffBuilder(searchNested)
            .create(x, y, w, 20, Component.translatable("screen.waystones_item_cost_extended.config.search_nested"),
                (btn, val) -> searchNested = val));
        y += 22;

        addRenderableWidget(CycleButton.onOffBuilder(showTooltip)
            .create(x, y, w, 20, Component.translatable("screen.waystones_item_cost_extended.config.show_tooltip"),
                (btn, val) -> showTooltip = val));
        y += 22;

        addRenderableWidget(CycleButton.onOffBuilder(fixPhysicsShipDistance)
            .create(x, y, w, 20, Component.translatable("screen.waystones_item_cost_extended.config.fix_physics_ship_distance"),
                (btn, val) -> fixPhysicsShipDistance = val));
        y += 22;

        addRenderableWidget(CycleButton.onOffBuilder(legacyEnable)
            .create(x, y, w, 20, Component.translatable("screen.waystones_item_cost_extended.config.legacy_enable"),
                (btn, val) -> legacyEnable = val));
        y += 22;

        addRenderableWidget(CycleButton.onOffBuilder(legacyWaiveXp)
            .create(x, y, w, 20, Component.translatable("screen.waystones_item_cost_extended.config.legacy_waive_xp"),
                (btn, val) -> legacyWaiveXp = val));
        y += 24;

        addRenderableWidget(Button.builder(
                Component.translatable("screen.waystones_item_cost_extended.config.copy_example"),
                btn -> {
                    if (this.minecraft != null) {
                        this.minecraft.keyboardHandler.setClipboard(RecipeAccess.configExampleSnippet());
                        copiedUntilMs = System.currentTimeMillis() + 3000L;
                    }
                })
            .bounds(x, y, w, 20)
            .build());

        int bottomY = this.height - 28;
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, btn -> saveAndClose())
            .bounds(centerX - 102, bottomY, 100, 20)
            .build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, btn -> onClose())
            .bounds(centerX + 2, bottomY, 100, 20)
            .build());
    }

    private void saveAndClose() {
        var existing = PaymentConfig.get();
        PaymentConfig.save(new PaymentConfig.Data(
            recipePayments,
            searchNested,
            showTooltip,
            fixPhysicsShipDistance,
            legacyEnable,
            legacyWaiveXp,
            existing.legacyItemCosts()
        ));
        onClose();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);

        var status = WaystonesRuleDetector.detect(true);
        Component statusText = status == WaystonesRuleDetector.Status.LEGACY_ENABLED
            ? Component.translatable("screen.waystones_item_cost_extended.config.status.legacy_enabled").withStyle(ChatFormatting.GREEN)
            : Component.translatable("screen.waystones_item_cost_extended.config.status.legacy_disabled").withStyle(ChatFormatting.GOLD);
        graphics.drawCenteredString(this.font, statusText, this.width / 2, 34, 0xFFFFFF);

        if (System.currentTimeMillis() < copiedUntilMs) {
            graphics.drawCenteredString(this.font,
                Component.translatable("screen.waystones_item_cost_extended.config.copied").withStyle(ChatFormatting.AQUA),
                this.width / 2, this.height - 46, 0xFFFFFF);
        }
    }
}

package dev.rinchan.waystonesitemcost.client;

import dev.rinchan.waystonesitemcost.config.PaymentConfig;
import dev.rinchan.waystonesitemcost.config.WaystonesRuleDetector;
import dev.rinchan.waystonesitemcost.minecraft.RecipeAccess;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
    private long copiedUntilMs;

    public WaystonesItemCostConfigScreen(Screen parent) {
        super(Component.translatable("screen.waystones_item_cost_extended.config.title"));
        this.parent = parent;
        var data = PaymentConfig.get();
        this.recipePayments = data.recipePayments();
        this.searchNested = data.searchNestedContainers();
        this.showTooltip = data.showPaymentPreviewTooltip();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int w = 240;
        int x = centerX - w / 2;
        int y = 60;

        addRenderableWidget(CycleButton.onOffBuilder(recipePayments)
            .create(x, y, w, 20, Component.translatable("screen.waystones_item_cost_extended.config.recipe_payments"),
                (btn, val) -> recipePayments = val));
        y += 26;

        addRenderableWidget(CycleButton.onOffBuilder(searchNested)
            .create(x, y, w, 20, Component.translatable("screen.waystones_item_cost_extended.config.search_nested"),
                (btn, val) -> searchNested = val));
        y += 26;

        addRenderableWidget(CycleButton.onOffBuilder(showTooltip)
            .create(x, y, w, 20, Component.translatable("screen.waystones_item_cost_extended.config.show_tooltip"),
                (btn, val) -> showTooltip = val));
        y += 32;

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
            existing.legacyEnableCustomItemCosts(),
            existing.legacyWaiveVanillaXpCost(),
            existing.legacyItemCosts()
        ));
        onClose();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreenAndShow(parent);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 16, 0xFFFFFFFF);

        var status = WaystonesRuleDetector.detect(false);
        Component statusText = status == WaystonesRuleDetector.Status.CONFIGURED
            ? Component.translatable("screen.waystones_item_cost_extended.config.status.configured").withStyle(ChatFormatting.GREEN)
            : Component.translatable("screen.waystones_item_cost_extended.config.status.not_configured").withStyle(ChatFormatting.GOLD);
        graphics.centeredText(this.font, statusText, this.width / 2, 36, 0xFFFFFFFF);

        if (System.currentTimeMillis() < copiedUntilMs) {
            graphics.centeredText(this.font,
                Component.translatable("screen.waystones_item_cost_extended.config.copied").withStyle(ChatFormatting.AQUA),
                this.width / 2, this.height - 46, 0xFFFFFFFF);
        }
    }
}

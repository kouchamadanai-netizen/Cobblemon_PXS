package com.cobblemon.playerxp.share.client;

import com.cobblemon.playerxp.share.LevelShareConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * This mod's visual config screen.
 *
 * <p>Entry points: <b>Mod List → Cobblemon Player XP Share → Config</b>,
 * plus the in-game key binding <b>“Open Cobblemon XP Share Config”</b> (unbound by default).</p>
 *
 * <p>Layout: master switch → share ratio → master option (+ sub option) → fainted → Apply/Cancel.</p>
 *
 * <p><b>The sub option is configurable only while the master option is on</b>; when the master option
 * is off it is greyed out and a hint is shown.</p>
 *
 * <p><b>Layout caveat</b>: every child of {@link LinearLayout} is **left-aligned** by default
 * (alignment factor 0); only an explicit {@code alignHorizontallyCenter()} centers it within the column.</p>
 */
public class LevelShareConfigScreen extends Screen {

    /** Height reserved for the 「actual effect of the current ratio」 hint line. */
    private static final int HINT_HEIGHT = 12;
    /** Height reserved for the description text below a button. */
    private static final int DESC_HEIGHT = 12;

    /** Full row width (master option). */
    private static final int WIDGET_WIDTH = 300;
    /** Sub option width (slightly narrower than the master option, to look subordinate). */
    private static final int SUB_WIDTH = 280;
    /** Apply / Cancel button width. */
    private static final int HALF_WIDTH = 146;

    private final Screen parent;

    /** Draft values — not written to the config until 「Apply」 is pressed. */
    private boolean draftEnabled;
    private double draftRatio;
    private boolean draftBenchedShare;
    private boolean draftWholeTeam;
    private boolean draftIncludeFainted;
    private boolean draftShareNotification;
    private boolean draftMergeNotification;
    /** Whether the initial values have been read from the config once (keeps unapplied edits when the screen is rebuilt on resize). */
    private boolean draftsInitialized;

    private LinearLayout layout;
    private ShareRatioSlider ratioSlider;
    private Button enabledButton;
    private Button masterButton;
    private Button subButton;
    private Button faintedButton;
    private Button notificationButton;
    private Button mergeNotificationButton;

    public LevelShareConfigScreen(Screen parent) {
        super(Component.translatable("cobblemon_player_xp_share.config.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (!this.draftsInitialized) {
            LevelShareConfig config = LevelShareConfig.INSTANCE;
            this.draftEnabled = config.isEnabled();
            this.draftRatio = config.getShareRatio();
            this.draftBenchedShare = config.isBenchedShare();
            this.draftWholeTeam = config.isWholeTeam();
            this.draftIncludeFainted = config.isIncludeFainted();
            this.draftShareNotification = config.isShareNotification();
            this.draftMergeNotification = config.isMergeNotification();
            this.draftsInitialized = true;
        }

        this.layout = LinearLayout.vertical().spacing(6);

        // ---- Master switch ----
        this.enabledButton = fullWidthButton(enabledLabel(), b -> {
            this.draftEnabled = !this.draftEnabled;
            b.setMessage(enabledLabel());
            refreshEnabledState();
        });

        // ---- Share ratio (0% ~ 1000%) ----
        this.ratioSlider = new ShareRatioSlider(0, 0, WIDGET_WIDTH, 20, this.draftRatio,
                value -> this.draftRatio = value);
        this.layout.addChild(this.ratioSlider, this.layout.newCellSettings().alignHorizontallyCenter());

        // Placeholder for the 「actual effect of the current ratio」 (the text is drawn live in render())
        this.layout.addChild(new StringWidget(Component.empty(), this.font),
                this.layout.newCellSettings().alignHorizontallyCenter().paddingTop(HINT_HEIGHT));

        // ---- Master option: share status, not sent out / sent out ----
        this.masterButton = fullWidthButton(masterLabel(), b -> {
            this.draftBenchedShare = !this.draftBenchedShare;
            b.setMessage(masterLabel());
            // Both the label and the description of the sub option follow the master option
            this.subButton.setMessage(subLabel());
            // The fainted option is configurable only under 「not sent out」
            refreshEnabledState();
        });
        // Placeholder for the description below the master option (the text is drawn live in render())
        addDescriptionSpacer();

        // ---- Sub option: whole-team share, no / yes (both master-option branches share this text) ----
        this.subButton = subButton(subLabel(), b -> {
            this.draftWholeTeam = !this.draftWholeTeam;
            b.setMessage(subLabel());
        });
        // Placeholder for the description below the sub option
        addDescriptionSpacer();

        // ---- Fainted Pokémon (configurable only under 「share status: not sent out」) ----
        this.faintedButton = fullWidthButton(faintedLabel(), b -> {
            this.draftIncludeFainted = !this.draftIncludeFainted;
            b.setMessage(faintedLabel());
        });

        // ---- Share experience notification ----
        this.notificationButton = fullWidthButton(notificationLabel(), b -> {
            this.draftShareNotification = !this.draftShareNotification;
            b.setMessage(notificationLabel());
            refreshEnabledState();
        });

        // ---- Display type when there are multiple notifications (configurable only while notifications are on) ----
        this.mergeNotificationButton = subButton(mergeNotificationLabel(), b -> {
            this.draftMergeNotification = !this.draftMergeNotification;
            b.setMessage(mergeNotificationLabel());
        });
        // Placeholder for the description below this option (the text is drawn live in render())
        addDescriptionSpacer();

        // ---- Apply / Cancel ----
        LinearLayout buttons = LinearLayout.horizontal().spacing(8);
        buttons.addChild(Button.builder(
                Component.translatable("cobblemon_player_xp_share.config.apply"),
                b -> applyAndClose()).width(HALF_WIDTH).build());
        buttons.addChild(Button.builder(
                Component.translatable("gui.cancel"),
                b -> onClose()).width(HALF_WIDTH).build());
        this.layout.addChild(buttons, this.layout.newCellSettings().alignHorizontallyCenter());

        this.layout.visitWidgets(this::addRenderableWidget);
        this.layout.arrangeElements();
        FrameLayout.centerInRectangle(this.layout, this.getRectangle());

        refreshEnabledState();
    }

    /** Full-row toggle button, also added to the layout. */
    private Button fullWidthButton(Component label, Button.OnPress onPress) {
        Button button = Button.builder(label, onPress).width(WIDGET_WIDTH).build();
        this.layout.addChild(button, this.layout.newCellSettings().alignHorizontallyCenter());
        return button;
    }

    /** Sub option button, slightly inset to express that it is subordinate. */
    private Button subButton(Component label, Button.OnPress onPress) {
        Button button = Button.builder(label, onPress).width(SUB_WIDTH).build();
        this.layout.addChild(button, this.layout.newCellSettings().alignHorizontallyCenter());
        return button;
    }

    /** Reserve a block of height for the description text below a button (the text itself is drawn in render()). */
    private void addDescriptionSpacer() {
        this.layout.addChild(new StringWidget(Component.empty(), this.font),
                this.layout.newCellSettings().alignHorizontallyCenter().paddingTop(DESC_HEIGHT));
    }

    // ------------------------------------------------------------------
    // Text
    // ------------------------------------------------------------------

    private Component enabledLabel() {
        return Component.translatable(this.draftEnabled
                ? "cobblemon_player_xp_share.config.enabled.on"
                : "cobblemon_player_xp_share.config.enabled.off");
    }

    /** Master option: share status, not sent out / sent out. */
    private Component masterLabel() {
        return Component.translatable(this.draftBenchedShare
                ? "cobblemon_player_xp_share.config.master.benched"
                : "cobblemon_player_xp_share.config.master.summoned");
    }

    /** Description below the master option. */
    private Component masterDescription() {
        return Component.translatable("cobblemon_player_xp_share.config.master.desc."
                + (this.draftBenchedShare ? "benched" : "summoned"));
    }

    /** Sub option: whole-team share, no / yes (both master-option branches share the same text). */
    private Component subLabel() {
        return Component.translatable(this.draftWholeTeam
                ? "cobblemon_player_xp_share.config.sub.on"
                : "cobblemon_player_xp_share.config.sub.off");
    }

    /** Description below the sub option, varying with the master-option branch and the sub option value. */
    private Component subDescription() {
        String branch = this.draftBenchedShare ? "benched" : "summoned";
        String choice = this.draftWholeTeam ? "whole" : "selected";
        return Component.translatable("cobblemon_player_xp_share.config.sub.desc." + branch + "." + choice);
    }

    private Component faintedLabel() {
        return Component.translatable(this.draftIncludeFainted
                ? "cobblemon_player_xp_share.config.fainted.on"
                : "cobblemon_player_xp_share.config.fainted.off");
    }

    private Component notificationLabel() {
        return Component.translatable(this.draftShareNotification
                ? "cobblemon_player_xp_share.config.notification.on"
                : "cobblemon_player_xp_share.config.notification.off");
    }

    private Component mergeNotificationLabel() {
        return Component.translatable(this.draftMergeNotification
                ? "cobblemon_player_xp_share.config.merge.on"
                : "cobblemon_player_xp_share.config.merge.off");
    }

    /** Explanation below the 「display type when there are multiple notifications」 button. */
    private Component mergeNotificationDescription() {
        return Component.translatable(this.draftMergeNotification
                ? "cobblemon_player_xp_share.config.merge.desc.on"
                : "cobblemon_player_xp_share.config.merge.desc.off");
    }

    /** Describe the actual effect of the current ratio in one sentence (taking the player gaining 1 XP as the example). */
    private Component ratioHint() {
        // The ratio itself is 「per 1 XP the player gains → how many XP the target Pokémon gains」
        return Component.translatable("cobblemon_player_xp_share.config.hint", String.format("%.0f", this.draftRatio));
    }

    // ------------------------------------------------------------------
    // Availability wiring: the sub option follows the master option
    // ------------------------------------------------------------------

    /**
     * Refresh the enabled state of every widget.
     *
     * <p>Rules:</p>
     * <ul>
     *   <li>master switch off → everything is greyed out;</li>
     *   <li>the sub option is available under both master-option branches (label and description follow
     *       the branch) and is not greyed out by the master option;</li>
     *   <li><b>the fainted option is configurable only when the master option is 「share status: not sent out」</b>.</li>
     * </ul>
     */
    private void refreshEnabledState() {
        boolean on = this.draftEnabled;
        if (this.ratioSlider != null) {
            this.ratioSlider.active = on;
        }
        if (this.masterButton != null) {
            this.masterButton.active = on;
        }
        if (this.subButton != null) {
            this.subButton.active = on;
        }
        if (this.faintedButton != null) {
            this.faintedButton.active = on && this.draftBenchedShare;
        }
        if (this.notificationButton != null) {
            this.notificationButton.active = on;
        }
        if (this.mergeNotificationButton != null) {
            // Whether to merge only matters while notifications are on
            this.mergeNotificationButton.active = on && this.draftShareNotification;
        }
    }

    private void applyAndClose() {
        LevelShareConfig config = LevelShareConfig.INSTANCE;
        config.setEnabled(this.draftEnabled);
        config.setShareRatio(this.draftRatio);
        config.setBenchedShare(this.draftBenchedShare);
        config.setWholeTeam(this.draftWholeTeam);
        // The fainted option is meaningful only under 「share status: not sent out」;
        // when the master option is 「sent out」 it is not configurable, so write it as off to avoid
        // leaving a true that has no effect.
        config.setIncludeFainted(this.draftBenchedShare && this.draftIncludeFainted);
        config.setShareNotification(this.draftShareNotification);
        config.setMergeNotification(this.draftMergeNotification);
        LevelShareConfig.save();
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(LevelShareClient.savedMessage(this.draftRatio), false);
        }
        onClose();
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);

        if (this.ratioSlider == null) {
            return;
        }

        // Ratio hint, updated live as the slider moves
        graphics.drawCenteredString(this.font, ratioHint(),
                this.ratioSlider.getX() + this.ratioSlider.getWidth() / 2,
                this.ratioSlider.getY() + this.ratioSlider.getHeight() + 4,
                0xFFA0A0A0);

        // Description below the master option
        if (this.masterButton != null) {
            graphics.drawCenteredString(this.font, masterDescription(),
                    this.masterButton.getX() + this.masterButton.getWidth() / 2,
                    this.masterButton.getY() + this.masterButton.getHeight() + 3,
                    0xFFA0A0A0);
        }

        // Description below the sub option
        if (this.subButton != null) {
            graphics.drawCenteredString(this.font, subDescription(),
                    this.subButton.getX() + this.subButton.getWidth() / 2,
                    this.subButton.getY() + this.subButton.getHeight() + 3,
                    0xFFA0A0A0);
        }

        // Description below 「display type when there are multiple notifications」 (shown only while this option is configurable)
        if (this.mergeNotificationButton != null && this.draftEnabled && this.draftShareNotification) {
            graphics.drawCenteredString(this.font, mergeNotificationDescription(),
                    this.mergeNotificationButton.getX() + this.mergeNotificationButton.getWidth() / 2,
                    this.mergeNotificationButton.getY() + this.mergeNotificationButton.getHeight() + 3,
                    0xFFA0A0A0);
        }

        // Lay a grey wash over every widget while the master switch is off
        if (!this.draftEnabled) {
            drawDisabledOverlay(graphics, this.ratioSlider);
            drawDisabledOverlay(graphics, this.masterButton);
            drawDisabledOverlay(graphics, this.subButton);
            drawDisabledOverlay(graphics, this.faintedButton);
            drawDisabledOverlay(graphics, this.notificationButton);
            drawDisabledOverlay(graphics, this.mergeNotificationButton);
        } else if (!this.draftShareNotification) {
            // While notifications are off, 「merge notifications」 has no effect
            drawDisabledOverlay(graphics, this.mergeNotificationButton);
        }
    }

    private void drawDisabledOverlay(GuiGraphics graphics, AbstractWidget widget) {
        if (widget == null) {
            return;
        }
        graphics.fill(widget.getX(), widget.getY(),
                widget.getX() + widget.getWidth(), widget.getY() + widget.getHeight(),
                0x80000000);
    }
}
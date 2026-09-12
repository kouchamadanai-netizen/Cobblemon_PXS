package com.cobblemon.playerxp.share.client;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.function.DoubleConsumer;

/**
 * The 「share ratio」 slider: <b>100% ~ 1000%, one step per 100%</b>.
 *
 * <p>There are only these 10 steps, 100%, 200%, … 1000%; no intermediate values such as 0% or 350% —
 * dragging snaps to the nearest step.</p>
 *
 * <p>Implementation: {@link AbstractSliderButton} stores the slider position internally as a
 * normalized {@code [0,1]} value; here it is quantized into 10 steps and then mapped linearly onto
 * {@code [1, 10]} (that is, 100% ~ 1000%).</p>
 */
public class ShareRatioSlider extends AbstractSliderButton {

    /** Number of steps: 100% through 1000%, 10 in total. */
    public static final int STEPS = 10;
    /** Highest step percentage. */
    public static final double MAX_PERCENT = 1000.0D;
    /** Lowest step percentage. */
    public static final double MIN_PERCENT = 100.0D;
    /** Maximum ratio (1000% = 10.0). */
    public static final double MAX_RATIO = MAX_PERCENT / 100.0D;
    /** Minimum ratio (100% = 1.0). */
    public static final double MIN_RATIO = MIN_PERCENT / 100.0D;

    private final DoubleConsumer onChange;

    public ShareRatioSlider(int x, int y, int width, int height, double initialRatio, DoubleConsumer onChange) {
        super(x, y, width, height, Component.empty(), toNormalized(initialRatio));
        this.onChange = onChange;
        updateMessage();
    }

    /** Ratio → normalized slider position, aligned to the nearest step. */
    private static double toNormalized(double ratio) {
        int step = toStep(ratio);
        return (double) step / (STEPS - 1);
    }

    /** Ratio → step index (0 = 100%, 9 = 1000%). */
    private static int toStep(double ratio) {
        double percent = ratio * 100.0D;
        double index = Math.round((percent - MIN_PERCENT) / 100.0D);
        return (int) Mth.clamp(index, 0.0D, (double) (STEPS - 1));
    }

    /** @return the current ratio, e.g. 5.0 means 500%. Always an integer value in 1.0 ~ 10.0. */
    public double getRatio() {
        return getPercent() / 100.0D;
    }

    /** @return the current step percentage, always an integer multiple of 100. */
    public double getPercent() {
        return MIN_PERCENT + Math.round(this.value * (STEPS - 1)) * 100.0D;
    }

    public void setRatio(double ratio) {
        this.value = toNormalized(ratio);
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        // Localised label; the percentage is passed in as the argument so that every
        // language can place it wherever it belongs.
        setMessage(Component.translatable(
                "cobblemon_player_xp_share.config.ratio",
                String.format("%.0f%%", getPercent())));
    }

    @Override
    protected void applyValue() {
        // Snap to the nearest step, keeping value and displayed value consistent
        this.value = toNormalized(getRatio());
        if (this.onChange != null) {
            this.onChange.accept(getRatio());
        }
    }
}

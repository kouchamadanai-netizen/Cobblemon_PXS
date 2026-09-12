package com.cobblemon.playerxp.share;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * This mod's configuration file (Common config).
 *
 * <p>Written to {@code config/cobblemon_player_xp_share-common.toml}.</p>
 *
 * <p>It can be edited directly, or changed in game through the config screen:
 * <b>Mods -> Cobblemon Player XP Share -> Config</b>, or the key binding
 * "Open Cobblemon Player XP Share config".</p>
 */
public final class LevelShareConfig {

    /** Upper bound of the share ratio: 1000% (10 XP per 1 player XP, per Pokémon). */
    public static final double MAX_RATIO = 10.0D;

    /** Lower bound of the share ratio: 100% (below that it rounds to 0, i.e. sharing nothing). */
    public static final double MIN_RATIO = 1.0D;

    /** Default share ratio: 300% (100 player XP gives each Pokémon 300 XP). */
    public static final double DEFAULT_RATIO = 3.0D;

    public static final ModConfigSpec SPEC;
    public static final LevelShareConfig INSTANCE;

    static {
        Pair<LevelShareConfig, ModConfigSpec> pair = new ModConfigSpec.Builder().configure(LevelShareConfig::new);
        INSTANCE = pair.getLeft();
        SPEC = pair.getRight();
    }

    private final ModConfigSpec.BooleanValue enabled;
    private final ModConfigSpec.DoubleValue shareRatio;
    private final ModConfigSpec.BooleanValue benchedShare;
    private final ModConfigSpec.BooleanValue wholeTeam;
    private final ModConfigSpec.BooleanValue includeFainted;
    private final ModConfigSpec.BooleanValue shareNotification;
    private final ModConfigSpec.BooleanValue mergeNotification;
    private final ModConfigSpec.BooleanValue requireCobblemon;

    private LevelShareConfig(ModConfigSpec.Builder builder) {
        builder.comment(
                "When the player gains experience, share a portion of it with Pokémon."
        ).push("experience_sharing");

        this.enabled = builder
                .comment(
                        "Master switch. false = fully disable experience sharing."
                )
                .define("enabled", true);

        this.shareRatio = builder
                .comment(
                        "Share ratio: experience given to EACH target Pokémon per 1 experience the player gains.",
                        "Default 3.0 (100 player XP -> 300 XP each).",
                        "The GUI slider snaps to 100% steps: 1.0 = 100%, 2.0 = 200% ... 10.0 = 1000%.",
                        "Range 1.0 ~ 10.0."
                )
                .defineInRange("share_ratio", DEFAULT_RATIO, MIN_RATIO, MAX_RATIO);

        // ---- Main option ----
        this.benchedShare = builder
                .comment(
                        "【Main option】Whether benched (not sent out) Pokémon may obtain shared experience.",
                        "  false = only sent-out Pokémon can obtain it. [default]",
                        "  true  = benched Pokémon can obtain it; whether it goes to the whole team or only",
                        "          the selected one is decided by 'whole_team' below."
                )
                .define("benched_share", false);

        // ---- Sub option of the main option ----
        this.wholeTeam = builder
                .comment(
                        "【Sub option】Whether the whole team shares.",
                        "  true  = every eligible Pokémon obtains it.",
                        "  false = only the one selected with R + mouse wheel obtains it. [default]"
                )
                .define("whole_team", false);

        this.includeFainted = builder
                .comment(
                        "Whether fainted (HP = 0) Pokémon also receive shared experience."
                )
                .define("include_fainted_pokemon", false);

        // ---- Notification ----
        this.shareNotification = builder
                .comment(
                        "【Notification】Whether to tell the player in the action bar when experience is shared.",
                        "  whole team -> \"Shared 「N」 experience with your Pokémon team.\"",
                        "  single one -> \"Shared 「N」 experience with 「Pokémon name」.\""
                )
                .define("share_notification", true);

        this.mergeNotification = builder
                .comment(
                        "【Notification】Display type when there are multiple notifications.",
                        "  true  (default) = Merged: a burst of notifications is counted together and merged",
                        "                    once you stop gaining experience (window: 20 ticks, extended by",
                        "                    each new share). If the previous message is already fading",
                        "                    (40 ticks after it was sent), the count is reset and restarts.",
                        "  false           = Frequent updates: refreshes almost immediately (window: 5 ticks)."
                )
                .define("merge_notification", true);

        this.requireCobblemon = builder
                .comment(
                        "Reserved: when true, experience is only granted once Cobblemon's storage is ready."
                )
                .define("require_cobblemon_storage", true);

        builder.pop();
    }

    public boolean isEnabled() {
        return this.enabled.get();
    }

    /**
     * @return the share ratio applied to each target Pokémon per 1 experience the
     *         player gains (1.0 ~ 10.0, i.e. 100% ~ 1000%).
     */
    public double getShareRatio() {
        return this.shareRatio.get();
    }

    /**
     * @return the main option: {@code true} = benched Pokémon may obtain shared
     *         experience; {@code false} = only sent-out Pokémon may.
     */
    public boolean isBenchedShare() {
        return this.benchedShare.get();
    }

    /** @return the sub option: whether the whole team obtains the share. Its meaning depends on the main option. */
    public boolean isWholeTeam() {
        return this.wholeTeam.get();
    }

    public boolean isIncludeFainted() {
        return this.includeFainted.get();
    }

    /** @return whether the player is notified in the action bar when experience is shared. */
    public boolean isShareNotification() {
        return this.shareNotification.get();
    }

    /** @return whether a burst of shares is merged into a single notification. */
    public boolean isMergeNotification() {
        return this.mergeNotification.get();
    }

    public boolean isRequireCobblemonStorage() {
        return this.requireCobblemon.get();
    }

    // ------------------------------------------------------------------
    // Setters, used by the config screen
    // ------------------------------------------------------------------

    public void setEnabled(boolean value) {
        this.enabled.set(value);
    }

    /**
     * @param ratio 1.0 ~ 10.0 (100% ~ 1000%); values outside the range are clamped
     *              and snapped to the nearest whole step (the GUI uses 100% steps).
     */
    public void setShareRatio(double ratio) {
        double clamped = Math.max(MIN_RATIO, Math.min(MAX_RATIO, ratio));
        // Keep the same granularity as the slider: whole steps only.
        double stepped = Math.round(clamped);
        this.shareRatio.set(Math.max(MIN_RATIO, Math.min(MAX_RATIO, stepped)));
    }

    public void setBenchedShare(boolean value) {
        this.benchedShare.set(value);
    }

    public void setWholeTeam(boolean value) {
        this.wholeTeam.set(value);
    }

    public void setIncludeFainted(boolean value) {
        this.includeFainted.set(value);
    }

    public void setShareNotification(boolean value) {
        this.shareNotification.set(value);
    }

    public void setMergeNotification(boolean value) {
        this.mergeNotification.set(value);
    }

    /** Writes the current values back to disk ({@code config/cobblemon_player_xp_share-common.toml}). */
    public static void save() {
        INSTANCE.enabled.save();
        INSTANCE.shareRatio.save();
        INSTANCE.benchedShare.save();
        INSTANCE.wholeTeam.save();
        INSTANCE.includeFainted.save();
        INSTANCE.shareNotification.save();
        INSTANCE.mergeNotification.save();
        INSTANCE.requireCobblemon.save();
    }
}

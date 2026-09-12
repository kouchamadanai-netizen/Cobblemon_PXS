package com.cobblemon.playerxp.share;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.pokemon.experience.ExperienceSource;
import com.cobblemon.mod.common.api.pokemon.experience.SidemodExperienceSource;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.activestate.ActivePokemonState;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerXpEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Core logic of the experience sharing.
 *
 * <p>Why XP is not granted directly inside {@link PlayerXpEvent.XpChange}: that event
 * fires <em>before the player's own XP is actually credited</em>, and another mod may
 * cancel it (in which case the player never receives the XP). So the approach here is
 * 「record + settle on the next tick」:</p>
 *
 * <ol>
 *   <li>When {@code XpChange} fires, record the amount and the player's total XP and level at that moment;</li>
 *   <li>On the next server tick, read the player's real total XP and diff it against the baseline
 *       to obtain the amount that was 「actually credited」;</li>
 *   <li>Multiply that real value by the configured ratio, pick the set of recipients from the
 *       master option / sub option, and grant to each in turn.</li>
 * </ol>
 *
 * <p>All state is read and written on the server main thread only: {@code XpChange},
 * {@code ServerTickEvent}, the player logout event and packet handling (NeoForge runs handlers
 * on the main thread by default) all happen on that one thread, so these {@link HashMap}s need
 * no extra synchronization.</p>
 */
public final class LevelShareHandler {

    /** Experience source id used for this mod's grants; it shows up in Cobblemon's XP gain events. */
    public static final ExperienceSource SHARE_SOURCE = new SidemodExperienceSource(LevelShareMod.MOD_ID);

    /**
     * Player experience waiting to be settled.
     * key = player UUID, value = the amount accumulated from one or more {@code XpChange} calls plus its baseline.
     */
    private static final Map<UUID, PendingShare> PENDING = new HashMap<>();

    /** Remainder cache for fractional ratios, so ratios like 0.5 do not lose XP to rounding over time. */
    private static final Map<UUID, Double> REMAINDERS = new HashMap<>();

    /**
     * 「Shared experience」 notifications waiting to be sent.
     *
     * <p>Multiple shares by the same player inside the merge window are summed up, and a single
     * message is sent when the window ends.</p>
     */
    private static final Map<UUID, PendingNotification> NOTIFICATIONS = new HashMap<>();

    /** Merge mode window length in ticks. 20 ticks = 1 second: send merged once the player stops for 1 s. */
    private static final int MERGE_WINDOW_TICKS = 20;

    /** Frequent-update mode window length in ticks. 5 ticks = 0.25 s: refreshes faster. */
    private static final int FREQUENT_WINDOW_TICKS = 5;

    /**
     * Threshold in ticks for 「the notification is already fading」.
     *
     * <p>The action bar text starts fading about 60 ticks (3 seconds) after it was last set.
     * 40 ticks (2 seconds) is used as the point where it 「enters the fade phase」,
     * leaving a small margin to avoid jitter around the boundary.</p>
     */
    private static final int ACTIONBAR_FADE_TICKS = 40;

    private LevelShareHandler() {
    }

    // ------------------------------------------------------------------
    // 1. Bookkeeping: the player gains experience
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onPlayerXpChange(PlayerXpEvent.XpChange event) {
        // Read the config object once per event, to avoid repeated map lookups on the hot path
        LevelShareConfig config = LevelShareConfig.INSTANCE;
        if (!config.isEnabled()) {
            return;
        }
        // Only the server-side logic applies; client-sync events are ignored outright
        // Note: NeoForge 21.1's PlayerEvent exposes getEntity(), not getPlayer()
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        int amount = event.getAmount();
        if (amount <= 0) {
            return;
        }

        UUID uuid = player.getUUID();
        PendingShare pending = PENDING.get(uuid);
        if (pending == null) {
            // First record: store the player's total XP and level before the event fired,
            // so the next tick can derive the real credited amount
            pending = new PendingShare(player.totalExperience, player.experienceLevel);
            PENDING.put(uuid, pending);
        }
        pending.amount += amount;
    }

    // ------------------------------------------------------------------
    // 2. Settlement: the actual grant on the next server tick
    // ------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();

        // Send due notifications first (when the merge window ends)
        flushNotifications(server);

        if (PENDING.isEmpty()) {
            // The vast majority of ticks end here: a volatile-free isEmpty check, negligible cost
            return;
        }

        // Take the pending entries out first, so new events cannot modify the map while iterating
        Map<UUID, PendingShare> batch = new HashMap<>(PENDING);
        PENDING.clear();

        for (Map.Entry<UUID, PendingShare> entry : batch.entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                // The player is offline: skip this grant
                continue;
            }

            int gained = entry.getValue().resolveActuallyGained(player);
            if (gained > 0) {
                shareExperience(player, gained);
            }
        }
    }

    /** Clear the caches when the server stops, so nothing leaks across saves. */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        clearPlayerState();
    }

    /**
     * Send out all notifications accumulated up to the end of the window.
     *
     * <p>Why merge: when picking up XP orbs, every orb credits XP once; sending one message per
     * orb could flood a dozen messages within a second. Summing them here sends a single message,
     * and the N shown is the total XP shared inside the window.</p>
     */
    private static void flushNotifications(MinecraftServer server) {
        if (NOTIFICATIONS.isEmpty()) {
            return;
        }

        int now = server.getTickCount();
        // Collect the due ones first, then remove them together, to avoid modifying the map while iterating
        List<UUID> due = null;
        for (Map.Entry<UUID, PendingNotification> entry : NOTIFICATIONS.entrySet()) {
            if (now >= entry.getValue().flushAtTick) {
                if (due == null) {
                    due = new ArrayList<>(2);
                }
                due.add(entry.getKey());
            }
        }
        if (due == null) {
            return;
        }

        for (UUID uuid : due) {
            PendingNotification pending = NOTIFICATIONS.remove(uuid);
            if (pending == null) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player == null) {
                // The player is offline, so drop this notification
                continue;
            }
            pending.send(player);
        }
    }

    // ------------------------------------------------------------------

    /**
     * A pending 「shared experience」 notification (used by merge mode).
     *
     * <p>Multiple shares produced inside the window accumulate into {@link #amount}, and one
     * message is sent at {@link #flushAtTick}.</p>
     */
    private static final class PendingNotification {

        /** Accumulated total experience. */
        private long amount;
        /** Master option shape: {@code true} = share while not sent out. */
        private final boolean benchedShare;
        /** In single-Pokémon mode, the name of that Pokémon (null in whole-team mode). */
        private final String soleName;
        /** Send at this tick. Every new share pushes it further out. */
        private int flushAtTick;
        /** Tick when this notification was last actually sent; {@code -1} means it never was. */
        private int lastSentTick = -1;
        /** Whether it has already entered the fade phase after the previous send (once it has, this round's count stops accumulating, see {@link #accumulateNotification}). */
        private boolean faded;

        private PendingNotification(boolean benchedShare, String soleName, int flushAtTick) {
            this.benchedShare = benchedShare;
            this.soleName = soleName;
            this.flushAtTick = flushAtTick;
        }

        /** Whether a new share belongs to 「the same」 pending notification — the shape must match to merge amounts. */
        private boolean matches(boolean benchedShare, String soleName) {
            if (this.benchedShare != benchedShare) {
                return false;
            }
            return this.soleName == null ? soleName == null : this.soleName.equals(soleName);
        }

        private void send(ServerPlayer player) {
            // Goes through the same assembly logic as sendNotification, so the wording cannot diverge
            sendActionBar(player,
                    buildMessage(this.benchedShare, this.soleName != null, this.amount, this.soleName));
            // Remember the send time, for the 「reset while fading」 check
            MinecraftServer server = player.getServer();
            this.lastSentTick = server == null ? -1 : server.getTickCount();
        }
    }

    /** Clear all of a player's temporary state on logout, so UUID reuse or long idling cannot leave residue. */
    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            UUID uuid = player.getUUID();
            PENDING.remove(uuid);
            REMAINDERS.remove(uuid);
            NOTIFICATIONS.remove(uuid);
            SelectedSlotTracker.clear(uuid);
        }
    }

    private static void clearPlayerState() {
        PENDING.clear();
        REMAINDERS.clear();
        NOTIFICATIONS.clear();
        SelectedSlotTracker.clearAll();
    }

    // ------------------------------------------------------------------
    // 3. The actual sharing logic
    // ------------------------------------------------------------------

    private static void shareExperience(ServerPlayer player, int playerExperienceGained) {
        if (playerExperienceGained <= 0) {
            return;
        }

        LevelShareConfig config = LevelShareConfig.INSTANCE;

        double ratio = config.getShareRatio();
        if (ratio <= 0.0D) {
            return;
        }
        if (config.isRequireCobblemonStorage() && Cobblemon.INSTANCE.getStorage() == null) {
            return;
        }

        PlayerPartyStore party;
        try {
            party = Cobblemon.INSTANCE.getStorage().getParty(player);
        } catch (Throwable t) {
            LevelShareMod.LOGGER.debug("Could not read the party of player {}, skipping this share", player.getGameProfile().getName(), t);
            return;
        }
        if (party == null) {
            return;
        }

        UUID uuid = player.getUUID();

        // ---- Compute the XP each target should get, carrying any sub-1 remainder to the next round ----
        double raw = playerExperienceGained * ratio;
        double remainder = REMAINDERS.getOrDefault(uuid, 0.0D);
        double accumulated = raw + remainder;
        int perPokemon = (int) Math.floor(accumulated);
        double newRemainder = accumulated - perPokemon;
        if (newRemainder <= 0.000001D) {
            newRemainder = 0.0D;
        }
        REMAINDERS.put(uuid, newRemainder);

        if (perPokemon <= 0) {
            return;
        }

        // ---- Decide the recipient scope of this share (master option + sub option) ----
        //
        //   Master option (whether non-sent-out Pokémon can receive a share)
        //     false = only sent-out Pokémon can receive a share
        //         sub option = true  → all sent-out Pokémon receive it
        //         sub option = false → only the sent-out Pokémon picked with R key + wheel receives it
        //     true  = non-sent-out Pokémon can receive a share
        //         sub option = true  → all non-sent-out Pokémon receive it (= the whole team)
        //         sub option = false → only the one picked with R key + wheel receives it (even if not sent out)
        boolean benchedShare = config.isBenchedShare();
        boolean bothOnlySelected = !config.isWholeTeam();
        int selectedSlot = SelectedSlotTracker.get(uuid);

        if (bothOnlySelected && selectedSlot < 0) {
            // Only the selected one may receive, but the player has nothing selected: grant to nobody
            return;
        }

        boolean includeFainted = config.isIncludeFainted();
        boolean notify = config.isShareNotification();
        boolean debug = LevelShareMod.LOGGER.isDebugEnabled();
        String playerName = debug || LevelShareMod.LOGGER.isTraceEnabled()
                ? player.getGameProfile().getName()
                : null;
        boolean grantedAny = false;
        // Used by the notification: how much was granted team-wide, and in single mode which Pokémon it was
        long totalShared = 0L;
        Pokemon soleTarget = null;

        // Iterating by index with get(int) is deliberate, instead of Kotlin's collection/iterator API:
        // the latter's JVM signatures drag in kotlin-stdlib's KMappedMarker, which pure-Java compilation
        // cannot resolve. PartyStore.get(int) returns null for an empty slot.
        int slots = party.size();
        for (int slot = 0; slot < slots; slot++) {
            Pokemon pokemon = party.get(slot);
            if (pokemon == null) {
                continue;
            }

            // In the sent-out branch only sent-out Pokémon qualify; in the benched branch every Pokémon counts.
            // This must depend ONLY on the master option: tying it to the sub option (as it once was,
            // via !bothOnlySelected) made "sent out + whole team = no" skip the check entirely and hand XP
            // to the selected Pokémon even while it was not sent out.
            if (!benchedShare && !isSummoned(pokemon)) {
                continue;
            }
            if (bothOnlySelected && slot != selectedSlot) {
                continue;
            }

            // A Pokémon already at max level (100 by default) cannot gain more XP; skip the useless call
            if (!pokemon.canLevelUpFurther()) {
                continue;
            }
            if (!includeFainted && pokemon.isFainted()) {
                continue;
            }

            int levelBefore = pokemon.getLevel();
            pokemon.addExperienceWithPlayer(player, SHARE_SOURCE, perPokemon);
            grantedAny = true;
            totalShared += perPokemon;
            soleTarget = pokemon;

            if (debug) {
                LevelShareMod.LOGGER.debug("Player {} gained {} XP -> {} was granted {} XP (level {} -> {})",
                        playerName, playerExperienceGained,
                        pokemon.getSpecies().getName(), perPokemon,
                        levelBefore, pokemon.getLevel());
            }
        }

        if (grantedAny) {
            LevelShareMod.LOGGER.trace("Player {} gained {} XP this tick and shared {} XP",
                    player.getGameProfile().getName(), playerExperienceGained, perPokemon);
            if (notify) {
                if (config.isMergeNotification()) {
                    // Merge mode: accumulate first, send once when the window ends (avoids orb-pickup spam)
                    accumulateNotification(player, benchedShare, bothOnlySelected, totalShared, soleTarget);
                } else {
                    // Per-share mode: send one message immediately for every share
                    sendNotification(player, benchedShare, bothOnlySelected, totalShared, soleTarget);
                }
            }
        }
    }

    /**
     * Accumulate this share into the pending notification; multiple shares for the <b>same target</b>
     * inside the window merge into one message.
     *
     * <p>In 「single Pokémon」 mode the text carries the Pokémon name, so if the target changes inside
     * the window (the player switched the selection with R key + wheel, or swapped the sent-out one),
     * the previous message must be sent first and a new one opened for the new target — otherwise the
     * XP of two different Pokémon would be added together while only one of the names is shown.</p>
     *
     * <p><b>Reset while fading</b>: if the previous notification is already fading
     * (see {@link #ACTIONBAR_FADE_TICKS}), the previous display round is essentially over, so the
     * accumulated count is <b>reset to zero immediately</b> and this share starts a new round instead
     * of being added to the previous numbers.</p>
     */
    private static void accumulateNotification(ServerPlayer player, boolean benchedShare, boolean bothOnlySelected,
                                               long totalShared, Pokemon soleTarget) {
        UUID uuid = player.getUUID();
        int now = player.getServer().getTickCount();
        int window = LevelShareConfig.INSTANCE.isMergeNotification()
                ? MERGE_WINDOW_TICKS
                : FREQUENT_WINDOW_TICKS;
        int flushAt = now + window;

        // Target name to display for this notification (null in whole-team mode)
        String soleName = (bothOnlySelected && soleTarget != null)
                ? soleTarget.getDisplayName(false).getString()
                : null;

        PendingNotification pending = NOTIFICATIONS.get(uuid);
        if (pending != null && !pending.matches(benchedShare, soleName)) {
            // The wording shape changed (target switched / option toggled): send the accumulated one, then reopen
            NOTIFICATIONS.remove(uuid);
            pending.send(player);
            pending = null;
        }

        if (pending == null) {
            pending = new PendingNotification(benchedShare, soleName, flushAt);
            NOTIFICATIONS.put(uuid, pending);
        } else {
            // The already-sent one is fading → reset this round's count now and start over from this share
            if (!pending.faded && pending.lastSentTick >= 0
                    && now - pending.lastSentTick >= ACTIONBAR_FADE_TICKS) {
                pending.amount = 0L;
                pending.faded = true;
            }
            // The window is pushed out from the most recent share
            pending.flushAtTick = flushAt;
        }
        pending.amount += totalShared;
    }

    /**
     * Assemble the notification text from master option × sub option (without any label).
     *
     * <p><b>Why it is built in segments</b>: the requirement is to render the value/name inside
     * 「」 in green. Writing it as 「one {@code %s} for the whole sentence」 would make it impossible
     * to colour only part of it after placeholder substitution, so the language files split it into
     * a 「prefix / suffix」 pair of keys and the middle value is coloured separately with
     * {@link ChatFormatting#GREEN}.</p>
     *
     * @return the assembled text; {@code null} when nothing should be sent (no target in single mode)
     */
    private static MutableComponent buildMessage(boolean benchedShare, boolean bothOnlySelected,
                                                 long totalShared, Pokemon soleTarget) {
        if (bothOnlySelected && soleTarget == null) {
            return null;
        }
        String soleName = bothOnlySelected ? soleTarget.getDisplayName(false).getString() : null;
        return buildMessage(benchedShare, bothOnlySelected, totalShared, soleName);
    }

    /**
     * Core text assembly (by name, so merge mode can reuse a cached name).
     *
     * @param soleName the Pokémon name in single mode; pass {@code null} in whole-team mode
     * @return the assembled text; {@code null} when the name is {@code null} in single mode
     */
    private static MutableComponent buildMessage(boolean benchedShare, boolean bothOnlySelected,
                                                 long totalShared, String soleName) {
        if (bothOnlySelected) {
            // Only one Pokémon gains XP, so the text carries its name
            if (soleName == null) {
                return null;
            }
            // Shared 「<N>」 experience with 「<name>」.
            return bracket("cobblemon_player_xp_share.notify.single.name.prefix",
                            soleName, "cobblemon_player_xp_share.notify.single.name.suffix")
                    .append(bracket("cobblemon_player_xp_share.notify.single.amount.prefix",
                            totalShared, "cobblemon_player_xp_share.notify.single.amount.suffix"));
        }

        // Whole-team share: the master option decides the wording (benched / sent out)
        if (benchedShare) {
            return bracket("cobblemon_player_xp_share.notify.team.benched.prefix",
                    totalShared, "cobblemon_player_xp_share.notify.team.benched.suffix");
        }
        return bracket("cobblemon_player_xp_share.notify.team.summoned.prefix",
                totalShared, "cobblemon_player_xp_share.notify.team.summoned.suffix");
    }

    /** Build one segment {@code <prefix>「<green value>」<suffix>}. */
    private static MutableComponent bracket(String prefixKey, Object value, String suffixKey) {
        return Component.translatable(prefixKey)
                .append(Component.literal(String.valueOf(value)).withStyle(ChatFormatting.GREEN))
                .append(Component.translatable(suffixKey));
    }

    /**
     * Send the player a 「experience shared」 hint right away (per-share mode, or the accumulated one
     * in merge mode).
     *
     * <p>The text varies with master option (share while sent out or not) × sub option (share with the
     * whole team or not):</p>
     * <ul>
     *   <li>whole team + share while not sent out →「shared 「N」 experience with your Pokémon team.」</li>
     *   <li>whole team + share while sent out     →「shared 「N」 experience with your sent-out Pokémon team.」</li>
     *   <li>single Pokémon (same for both master options) →「shared 「N」 experience with 「Pokémon name」.」</li>
     * </ul>
     *
     * <p>Sent with {@code displayClientMessage(component, true)} to the <b>action bar</b> — the line
     * just above the hotbar, which takes no chat space and leaves no history.</p>
     */
    private static void sendNotification(ServerPlayer player, boolean benchedShare, boolean bothOnlySelected,
                                         long totalShared, Pokemon soleTarget) {
        MutableComponent body = buildMessage(benchedShare, bothOnlySelected, totalShared, soleTarget);
        if (body != null) {
            // In per-share mode every message stands alone, and no label prefix is added
            sendActionBar(player, body);
        }
    }

    /** Send to the action bar (just above the hotbar). */
    private static void sendActionBar(ServerPlayer player, Component message) {
        player.displayClientMessage(message, true);
    }

    /**
     * Handle the 「selected battle slot」 reported by the client.
     *
     * <p>This is the mod's only inbound packet. Because Cobblemon's R key + wheel selection lives on
     * the client only, the server can learn which one the player picked only if the client reports it.</p>
     *
     * <p>Everything is wrapped in try-catch: even a malformed payload must not crash the player's
     * network thread.</p>
     */
    public static void onSelectedSlotPacket(SelectedSlotPayload payload, IPayloadContext context) {
        try {
            if (context.player() instanceof ServerPlayer player) {
                SelectedSlotTracker.update(player.getUUID(), payload.slot());
            }
        } catch (Throwable t) {
            LevelShareMod.LOGGER.debug("Failed to handle the selected slot payload", t);
        }
    }

    /**
     * Whether the Pokémon is currently summoned into the world (that is, 「sent out」).
     *
     * <p>Cobblemon models this with a sealed {@code PokemonState}, of which there are only three:</p>
     * <ul>
     *   <li>{@code SentOutState} (registry name {@code "sent-out"}) — released from a ball / following the player;</li>
     *   <li>{@code ShoulderedState} (registry name {@code "shouldered"}) — sitting on the player's shoulder;</li>
     *   <li>{@code InactivePokemonState} (registry name {@code "inactive"}) — merely resting in the party.</li>
     * </ul>
     *
     * <p>The first two both extend {@link ActivePokemonState}, so the 「is on the field」 test is simply
     * {@code state instanceof ActivePokemonState}.</p>
     *
     * <p>Note: when a Pokémon enters a battle, Cobblemon reclaims its state and what actually fights is a
     * clone inside {@code BattlePokemon}. That is, a party member in battle counts as 「not sent out」
     * under this test, which is exactly the semantics this config wants — battle experience is settled by
     * Cobblemon itself from battle participation, a path entirely separate from the 「player experience」
     * this mod shares.</p>
     */
    private static boolean isSummoned(Pokemon pokemon) {
        try {
            return pokemon.getState() instanceof ActivePokemonState;
        } catch (Throwable t) {
            // Be conservative when the state cannot be read: treat the Pokémon as
            // not sent out, so the whole team never receives XP by accident.
            LevelShareMod.LOGGER.debug("Could not read the Pokémon's battle state; treating it as not sent out", t);
            return false;
        }
    }

    // ------------------------------------------------------------------

    /**
     * One pending record of player experience.
     *
     * <p>It stores the player's total XP and level 「before the event fired」; the next tick uses their
     * current values to reconstruct how much experience was really credited.</p>
     */
    private static final class PendingShare {

        /**
         * Accumulated experience amount reported by events.
         *
         * <p>It serves as a <em>reference</em> only: the real credited amount comes from the
         * 「total experience delta」, and this value is the fallback when that cannot be computed reliably.</p>
         */
        private int amount;
        /** The player's total experience at record time. */
        private final int totalExperienceAtRecord;
        /** The player's level at record time. */
        private final int levelAtRecord;

        private PendingShare(int totalExperienceAtRecord, int levelAtRecord) {
            this.totalExperienceAtRecord = totalExperienceAtRecord;
            this.levelAtRecord = levelAtRecord;
        }

        /**
         * Compute how much experience the player <em>actually</em> received.
         *
         * <p>Normally this equals the amount reported by the event; the total-experience delta is used
         * here to confirm it, mainly to handle 「the event was cancelled by another mod」 — in that case
         * the player's real total experience does not change, so 0 is returned and no XP is invented for
         * the Pokémon.</p>
         *
         * <p><b>Why the delta's reliability must be limited</b>: on a vanilla level-up the game rebuilds
         * the total experience baseline as
         * {@code totalExperience = getXpNeededForNextLevel() * level}.
         * If the player happens to sit exactly on a 「level-up point」 (progress 0), the rebuilt baseline
         * is a chunk smaller than the true cumulative value, which makes the delta look larger than the
         * experience really gained. So when the delta is significantly larger than the reported amount,
         * the baseline is assumed to have been rebuilt and the reported value is used instead.</p>
         */
        private int resolveActuallyGained(ServerPlayer player) {
            int reported = Math.max(0, this.amount);

            // If the player is no longer at the recorded level, a level-up happened — the baseline is
            // untrustworthy, so use the reported value directly
            if (player.experienceLevel != this.levelAtRecord) {
                return reported;
            }

            int delta = player.totalExperience - this.totalExperienceAtRecord;
            if (delta <= 0) {
                // Total experience did not change (event cancelled, or rewritten by another mod): send nothing
                return 0;
            }

            // The delta far exceeds the reported amount → the baseline was rebuilt, trust the more
            // conservative reported value
            if (delta > (long) reported * 4 + 16) {
                return reported;
            }
            return delta;
        }
    }
}

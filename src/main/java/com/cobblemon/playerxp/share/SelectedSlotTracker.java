package com.cobblemon.playerxp.share;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks each player's currently 「selected for battle」 party slot.
 *
 * <p>The slot is reported by the client (see {@link SelectedSlotPayload}), because in
 * Cobblemon the R key + wheel selection lives on the client only
 * ({@code CobblemonClient.storage.selectedSlot}); the server has no such concept.</p>
 *
 * <p>This class only 「remembers the slot number」; whether the Pokémon in that slot
 * is actually eligible for XP is decided centrally by {@code LevelShareHandler}
 * from the current master option / sub option.</p>
 */
public final class SelectedSlotTracker {

    private static final Map<UUID, Integer> SELECTED = new HashMap<>();

    private SelectedSlotTracker() {
    }

    /** Called when the client reports its selection. */
    public static void update(UUID playerId, int slot) {
        if (playerId == null) {
            return;
        }
        SELECTED.put(playerId, slot);
    }

    /** @return the selected slot; {@link SelectedSlotPayload#NONE} when nothing is selected. */
    public static int get(UUID playerId) {
        return SELECTED.getOrDefault(playerId, SelectedSlotPayload.NONE);
    }

    public static void clear(UUID playerId) {
        SELECTED.remove(playerId);
    }

    public static void clearAll() {
        SELECTED.clear();
    }
}

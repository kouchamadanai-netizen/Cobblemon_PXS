package com.cobblemon.playerxp.share;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: reports the party slot the player currently has 「selected for battle」.
 *
 * <p>Cobblemon keeps the slot picked with the R key + mouse wheel on the client only
 * ({@code CobblemonClient.storage.selectedSlot}); the server knows nothing about it.
 * This mod uses that slot to decide which Pokémon receives XP, so the selection has
 * to be synced over.</p>
 *
 * <p>{@code -1} means 「no Pokémon is currently selected」.</p>
 */
public record SelectedSlotPayload(int slot) implements CustomPacketPayload {

    public static final int NONE = -1;

    public static final CustomPacketPayload.Type<SelectedSlotPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    ResourceLocation.fromNamespaceAndPath(LevelShareMod.MOD_ID, "selected_slot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SelectedSlotPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT,
                    SelectedSlotPayload::slot,
                    SelectedSlotPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

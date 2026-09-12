package com.cobblemon.playerxp.share;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cobblemon Player XP Share
 *
 * <p>Shares experience the player gains from any source (killing mobs, mining,
 * smelting, experience bottles, commands, ...) with the Pokémon in their party,
 * according to a configurable ratio.</p>
 *
 * <p>Implementation notes:</p>
 * <ul>
 *   <li>Listens to NeoForge's {@code PlayerXpEvent.XpChange}, which covers every
 *       call path of vanilla {@code Player#giveExperiencePoints(int)}, i.e. all
 *       sources of "the player gains experience".</li>
 *   <li>The share happens on the next server tick after the event, so the
 *       player's own experience has really been credited, and modifications made
 *       by other mods are reflected correctly.</li>
 *   <li>Experience is granted through Cobblemon's public API
 *       {@code Pokemon#addExperienceWithPlayer}; level-ups, move learning,
 *       friendship and client sync are all handled by Cobblemon itself.</li>
 * </ul>
 */
@Mod(LevelShareMod.MOD_ID)
public final class LevelShareMod {

    public static final String MOD_ID = "cobblemon_player_xp_share";
    public static final Logger LOGGER = LoggerFactory.getLogger("Cobblemon Player XP Share");

    public LevelShareMod(IEventBus modBus, ModContainer container) {
        // Config file: config/cobblemon_player_xp_share-common.toml
        container.registerConfig(ModConfig.Type.COMMON, LevelShareConfig.SPEC);

        // Network payload: the client reports its selected party slot.
        modBus.addListener(LevelShareMod::onRegisterPayloadHandlers);

        // Game events (player XP, server tick, player logout) go on the NeoForge event bus.
        NeoForge.EVENT_BUS.register(LevelShareHandler.class);
        // Only used to log the effective configuration once after startup.
        NeoForge.EVENT_BUS.register(LevelShareMod.class);

        // The config screen only exists on a physical client. This dist check is
        // required: LevelShareClient references net.minecraft.client.*, which does
        // not exist on a dedicated server, so loading it there would throw
        // NoClassDefFoundError.
        if (FMLEnvironment.dist.isClient()) {
            try {
                ClientBootstrap.init(container, modBus);
            } catch (Throwable t) {
                LOGGER.warn("Failed to initialise the config screen; the rest of the mod is unaffected", t);
            }
        }
    }

    /**
     * Registers the client-to-server "selected party slot" payload.
     *
     * <p>It is only handled on the server side: Cobblemon's R key + mouse wheel
     * selection is pure client state ({@code CobblemonClient.storage.selectedSlot}),
     * so the server needs this packet to know which Pokémon the player selected.</p>
     */
    private static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
        event.registrar("1").playToServer(
                SelectedSlotPayload.TYPE,
                SelectedSlotPayload.STREAM_CODEC,
                LevelShareHandler::onSelectedSlotPacket);
    }

    /**
     * Config values can only be read safely once the config has loaded, which is
     * why this happens after the server starts. Reading them in the constructor
     * would throw IllegalStateException.
     */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        LevelShareConfig config = LevelShareConfig.INSTANCE;
        if (!config.isEnabled()) {
            LOGGER.info("Cobblemon Player XP Share loaded, but experience sharing is disabled (enabled = false).");
            return;
        }
        String scope;
        if (config.isBenchedShare()) {
            scope = config.isWholeTeam()
                    ? "whole team while benched (every Pokémon obtains it)"
                    : "only the selected one (benched Pokémon included)";
        } else {
            scope = config.isWholeTeam()
                    ? "every sent-out Pokémon"
                    : "only the selected sent-out Pokémon";
        }
        double ratio = config.getShareRatio();
        LOGGER.info("Cobblemon Player XP Share loaded: per 100 player XP each target Pokémon gains {} XP (ratio {}%, scope: {})",
                (int) Math.floor(100 * ratio),
                String.format("%.0f", ratio * 100),
                scope);
    }

    /**
     * Indirection layer that defers references to client classes until runtime.
     *
     * <p>Calling {@code LevelShareClient.initialize(...)} directly from the
     * constructor would make this class's method body reference client classes,
     * which some JVM implementations may resolve eagerly. An extra bootstrap class
     * guarantees the server only touches client code when
     * {@link FMLEnvironment#dist}{@code .isClient()} is true.</p>
     */
    private static final class ClientBootstrap {
        private static void init(ModContainer container, IEventBus modBus) {
            com.cobblemon.playerxp.share.client.LevelShareClient.initialize(container, modBus);
        }
    }
}

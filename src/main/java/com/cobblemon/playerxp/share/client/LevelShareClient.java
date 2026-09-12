package com.cobblemon.playerxp.share.client;

import com.cobblemon.playerxp.share.LevelShareConfig;
import com.cobblemon.playerxp.share.SelectedSlotPayload;
import com.cobblemon.mod.common.client.CobblemonClient;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;
import org.lwjgl.glfw.GLFW;

/**
 * Client-only initialization: registers the visual config screen and the key binding.
 *
 * <p><b>Note: this class may only be loaded on the physical client.</b> It references
 * {@code net.minecraft.client.*}, which does not exist on a vanilla server. So
 * {@code LevelShareMod} guards it with {@code FMLEnvironment.dist.isClient()}, ensuring the
 * dedicated server never loads it (JVM class loading is lazy).</p>
 *
 * <p>Two entry points:</p>
 * <ol>
 *   <li>this mod's 「Config」 button in the mod list (through {@link IConfigScreenFactory});</li>
 *   <li>the key binding 「Open Cobblemon XP Share Config」 (unbound by default, the player sets it).</li>
 * </ol>
 */
public final class LevelShareClient {

    /** Key category, shown under 「Options → Key Binds」. */
    public static final String KEY_CATEGORY = "key.categories.cobblemon_player_xp_share";

    public static final KeyMapping OPEN_CONFIG_KEY = new KeyMapping(
            "key.cobblemon_player_xp_share.open_config",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN,   // unbound by default, to avoid clashing with other mods
            KEY_CATEGORY);

    /**
     * The last 「selected battle slot」 reported to the server.
     *
     * <p>The initial value is deliberately {@link SelectedSlotPayload#NONE}, so that even if the player
     * already has a Pokémon selected when joining, the first tick still syncs the real value.</p>
     */
    private static int lastReportedSlot = SelectedSlotPayload.NONE;

    private LevelShareClient() {
    }

    /** Called by {@code LevelShareMod} once it has confirmed we are on the client. */
    public static void initialize(ModContainer container, IEventBus modBus) {
        // 1) Mod list -> Config button
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (IConfigScreenFactory) (modContainer, parent) -> new LevelShareConfigScreen(parent));

        // 2) Key binding registration (belongs to the mod event bus)
        modBus.addListener(LevelShareClient::onRegisterKeyMappings);

        // 3) Check the key every tick (belongs to the game event bus)
        NeoForge.EVENT_BUS.addListener(LevelShareClient::onClientTick);
    }

    private static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_CONFIG_KEY);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();

        // 1) Report changes of the 「selected battle slot」
        reportSelectedSlot(minecraft);

        // 2) Handle the shortcut that opens the config screen
        while (OPEN_CONFIG_KEY.consumeClick()) {
            if (minecraft.screen != null || minecraft.player == null) {
                // Another screen is open, or the world is not entered yet: ignore
                continue;
            }
            minecraft.setScreen(new LevelShareConfigScreen(null));
        }
    }

    /**
     * Sync Cobblemon's 「selected battle slot」 to the server.
     *
     * <p>The slot switched with Cobblemon's R key + mouse wheel is held in pure client state,
     * {@code CobblemonClient.storage.selectedSlot}, which the server knows nothing about.
     * This mod uses that slot to decide which Pokémon receives XP, so a packet is sent here
     * whenever the value changes.</p>
     *
     * <p>The packet is sent only on an actual change (sending every tick would waste bandwidth).</p>
     */
    private static void reportSelectedSlot(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.getConnection() == null) {
            // Not in a world / not connected: also reset the local record, so returning to a single-player
            // save is not misread as “no change”
            lastReportedSlot = SelectedSlotPayload.NONE;
            return;
        }

        int current;
        try {
            current = CobblemonClient.INSTANCE.getStorage().getSelectedSlot();
        } catch (Throwable t) {
            // The party data may not be available yet before Cobblemon sends it; skip quietly
            return;
        }

        if (current == lastReportedSlot) {
            return;
        }
        lastReportedSlot = current;
        minecraft.getConnection().send(new SelectedSlotPayload(current));
    }

    /** One-line hint for the UI (the reminder shown after the config is saved); the argument is the ratio (1.0 = 100%). */
    public static Component savedMessage(double ratio) {
        return Component.translatable("cobblemon_player_xp_share.config.saved",
                String.format("%.0f%%", ratio * 100.0D));
    }
}

package net.gravijet.lobby.listener;

import net.gravijet.lobby.Main;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class ProtocolCheckListener implements Listener {

    private static final int EXPECTED_PROTOCOL = 47;
    private final Main plugin;

    public ProtocolCheckListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        int protocol = getProtocolVersion(player);
        if (protocol != -1 && protocol != EXPECTED_PROTOCOL) {
            // Delay 8 ticks (0.4s) so the message appears after the join message
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                plugin.getMessages().send(player, "protocol.not-47"), 8L);
        }
    }

    /**
     * Gets the player's Minecraft protocol version via ViaVersion (reflection-based
     * to avoid a hard dependency). Tries ViaVersion 4.x, then 3.x/2.x, then 1.x
     * packages. Returns -1 if ViaVersion is not installed or the version cannot be
     * determined.
     */
    private int getProtocolVersion(Player player) {
        // ViaVersion 4.x — com.viaversion.viaversion.api.Via
        try {
            Class<?> viaClass = Class.forName("com.viaversion.viaversion.api.Via");
            Object api = viaClass.getMethod("getAPI").invoke(null);
            return (int) api.getClass().getMethod("getPlayerVersion", Object.class).invoke(api, player);
        } catch (Exception ignored) {}

        // ViaVersion 2.x / 3.x — us.myles.viaversion.api.Via
        try {
            Class<?> viaClass = Class.forName("us.myles.viaversion.api.Via");
            Object api = viaClass.getMethod("getAPI").invoke(null);
            return (int) api.getClass().getMethod("getPlayerVersion", Object.class).invoke(api, player);
        } catch (Exception ignored) {}

        // ViaVersion 1.x — us.myles.ViaVersion.api.Via
        try {
            Class<?> viaClass = Class.forName("us.myles.ViaVersion.api.Via");
            Object api = viaClass.getMethod("getAPI").invoke(null);
            return (int) api.getClass().getMethod("getPlayerVersion", Object.class).invoke(api, player);
        } catch (Exception ignored) {}

        return -1; // ViaVersion not available
    }
}

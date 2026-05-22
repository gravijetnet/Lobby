package net.gravijet.lobby.listener;

import net.gravijet.lobby.Main;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.lang.reflect.Method;
import java.util.UUID;

public class ProtocolCheckListener implements Listener {

    private static final int EXPECTED_PROTOCOL = 47;
    private final Main plugin;

    // Cached reflection state — resolved once at construction time.
    private final Object viaApi;       // com.viaversion…Via.getAPI() result, or null
    private final Method getVersion;   // api.getPlayerVersion(Object), or null

    public ProtocolCheckListener(Main plugin) {
        this.plugin = plugin;

        Object api = null;
        Method method = null;

        String[] classNames = {
            "com.viaversion.viaversion.api.Via",  // ViaVersion 4.x
            "us.myles.viaversion.api.Via",         // ViaVersion 2.x / 3.x
            "us.myles.ViaVersion.api.Via"          // ViaVersion 1.x
        };
        for (String className : classNames) {
            try {
                Class<?> viaClass = Class.forName(className);
                api = viaClass.getMethod("getAPI").invoke(null);
                method = api.getClass().getMethod("getPlayerVersion", Object.class);
                break;
            } catch (ReflectiveOperationException e) {
                // This package not present — try the next one
            }
        }

        if (api != null && method == null) {
            plugin.getLogger().warning("ViaVersion found but getPlayerVersion method is missing — protocol check disabled.");
        }

        this.viaApi = api;
        this.getVersion = method;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        int protocol = getProtocolVersion(player);
        if (protocol != -1 && protocol != EXPECTED_PROTOCOL) {
            UUID uuid = player.getUniqueId();
            // Delay 8 ticks (0.4s) so the message appears after the join message
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null) plugin.getMessages().send(p, "protocol.not-47");
            }, 8L);
        }
    }

    /**
     * Gets the player's Minecraft protocol version via the cached ViaVersion API.
     * Returns -1 if ViaVersion is not installed or the version cannot be determined.
     */
    private int getProtocolVersion(Player player) {
        if (viaApi == null || getVersion == null) return -1;
        try {
            Object result = getVersion.invoke(viaApi, player);
            if (result instanceof Number) {
                return ((Number) result).intValue();
            }
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().warning("ViaVersion getPlayerVersion failed: " + e.getMessage());
        }
        return -1;
    }
}

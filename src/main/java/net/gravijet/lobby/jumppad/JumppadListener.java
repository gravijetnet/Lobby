package net.gravijet.lobby.jumppad;

import net.gravijet.lobby.Main;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class JumppadListener implements Listener {

    private final Main            plugin;
    private final JumppadManager  jumppadManager;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    private static final long COOLDOWN_MS = 500L;

    public JumppadListener(Main plugin, JumppadManager jumppadManager) {
        this.plugin         = plugin;
        this.jumppadManager = jumppadManager;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (plugin.isInBuildMode(player)) return;

        Location loc   = player.getLocation();
        Location below = new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());

        Jumppad pad = jumppadManager.getJumppadAt(below);
        if (pad == null) return;

        long now  = System.currentTimeMillis();
        Long last = cooldowns.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) return;
        cooldowns.put(player.getUniqueId(), now);

        player.setVelocity(new Vector(pad.getVelX(), pad.getVelY(), pad.getVelZ()));
    }
}

package net.gravijet.lobby.jumppad;

import net.gravijet.lobby.Main;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class JumppadListener implements Listener {

    private final Main plugin;
    private final JumppadManager jumppadManager;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Integer> flyingPlayers = new HashMap<>();

    private static final long COOLDOWN_MS = 600L;

    public JumppadListener(Main plugin, JumppadManager jumppadManager) {
        this.plugin = plugin;
        this.jumppadManager = jumppadManager;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (plugin.isInBuildMode(player) || flyingPlayers.containsKey(player.getUniqueId())) return;

        Location to = event.getTo();
        if (to == null) return;

        Location below = to.clone().subtract(0, 1, 0);
        Jumppad pad = jumppadManager.getJumppadAt(below.getBlock().getLocation());
        if (pad == null) return;

        long now = System.currentTimeMillis();
        Long last = cooldowns.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) return;
        cooldowns.put(player.getUniqueId(), now);

        launchPlayer(player, pad);
    }

    private void launchPlayer(Player player, Jumppad pad) {
        player.setAllowFlight(false);
        player.setFlying(false);

        Vector launchVector = JumppadManager.calculateLaunchVector(pad.getBaseStrength(), pad.getHeightMultiplier());
        player.setVelocity(launchVector);

        int taskId = new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || player.isOnGround()) {
                    flyingPlayers.remove(player.getUniqueId());
                    plugin.restoreFlightState(player);
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin, 0L, 1L).getTaskId();
        flyingPlayers.put(player.getUniqueId(), taskId);
    }
}

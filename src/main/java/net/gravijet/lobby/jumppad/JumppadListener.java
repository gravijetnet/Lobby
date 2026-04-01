package net.gravijet.lobby.jumppad;

import net.gravijet.lobby.Main;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class JumppadListener implements Listener {

    private final Main            plugin;
    private final JumppadManager  jumppadManager;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    private static final long COOLDOWN_MS = 600L;
    /**
     * Maximum number of trajectory ticks to simulate / execute.
     * At 20 TPS this is 15 seconds — more than enough for any lobby jumppad.
     */
    private static final int MAX_TICKS = 300;

    public JumppadListener(Main plugin, JumppadManager jumppadManager) {
        this.plugin        = plugin;
        this.jumppadManager = jumppadManager;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (plugin.isInBuildMode(player)) return;
        if (plugin.isInFlight(player)) return; // already on a trajectory

        Location loc   = player.getLocation();
        Location below = new Location(loc.getWorld(), loc.getBlockX(),
                loc.getBlockY() - 1, loc.getBlockZ());

        Jumppad pad = jumppadManager.getJumppadAt(below);
        if (pad == null) return;

        long now  = System.currentTimeMillis();
        Long last = cooldowns.get(player.getUniqueId());
        if (last != null && now - last < COOLDOWN_MS) return;
        cooldowns.put(player.getUniqueId(), now);

        launchPlayer(player, pad);
    }

    // ── Launch logic ──────────────────────────────────────────────────────────

    /**
     * Calculates the launch velocity (using target if set) and starts a
     * per-tick teleportation task that moves the player along the full arc.
     * This bypasses Minecraft's client-side velocity cap entirely.
     */
    private void launchPlayer(Player player, Jumppad pad) {
        double vx, vy, vz;

        if (pad.hasTarget()) {
            World targetWorld = Bukkit.getWorld(pad.getTargetWorld());
            if (targetWorld != null) {
                Vector vel = JumppadManager.calculateVelocityToTarget(
                        player.getLocation(), pad.getVelY(),
                        pad.getTargetX(), pad.getTargetY(), pad.getTargetZ());
                vx = vel.getX();
                vy = vel.getY();
                vz = vel.getZ();
            } else {
                vx = pad.getVelX(); vy = pad.getVelY(); vz = pad.getVelZ();
            }
        } else {
            vx = pad.getVelX(); vy = pad.getVelY(); vz = pad.getVelZ();
        }

        List<Location> trajectory = JumppadManager.calculateTrajectory(
                player.getLocation(), vx, vy, vz, MAX_TICKS);

        if (trajectory.isEmpty()) return;

        plugin.setInFlight(player.getUniqueId(), true);
        UUID playerId = player.getUniqueId();

        new BukkitRunnable() {
            int index = 0;

            @Override
            public void run() {
                Player p = Bukkit.getPlayer(playerId);
                if (p == null || !p.isOnline()) {
                    plugin.setInFlight(playerId, false);
                    cancel();
                    return;
                }
                if (index >= trajectory.size()) {
                    plugin.setInFlight(playerId, false);
                    // Restore fly state now that the trajectory is done
                    plugin.restoreFlightState(p);
                    cancel();
                    return;
                }
                p.teleport(trajectory.get(index++));
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }
}

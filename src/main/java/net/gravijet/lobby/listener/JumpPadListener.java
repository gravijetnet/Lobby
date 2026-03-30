package net.gravijet.lobby.listener;

import net.gravijet.lobby.Main;
import net.gravijet.lobby.zone.JumpPad;
import net.gravijet.lobby.zone.JumpPadManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Listens for player movement and applies jump‑pad launches.
 */
public final class JumpPadListener implements Listener {

    private final JumpPadManager jumpPadManager;

    public JumpPadListener(JumpPadManager jumpPadManager) {
        this.jumpPadManager = jumpPadManager;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Only check if the player actually moved a block
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        JumpPad pad = jumpPadManager.tryLaunch(player);
        if (pad != null) {
            // Optional: play a sound or effect
            // player.playSound(player.getLocation(), Sound.ENDERDRAGON_WING, 1.0f, 1.0f);
            // player.spawnParticle(Particle.CLOUD, player.getLocation(), 10);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Clean up cooldown map to prevent memory leak
        jumpPadManager.clearCooldowns(event.getPlayer().getUniqueId());
    }
}
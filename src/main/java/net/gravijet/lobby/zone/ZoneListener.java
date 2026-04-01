package net.gravijet.lobby.zone;

import net.gravijet.lobby.Main;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class ZoneListener implements Listener {

    private final Main plugin;
    private final ZoneManager zoneManager;
    private final Map<UUID, Long> denyMessageCooldown = new HashMap<>();
    private static final long DENY_MESSAGE_COOLDOWN_MS = 1500L;

    public ZoneListener(Main plugin, ZoneManager zoneManager) {
        this.plugin = plugin;
        this.zoneManager = zoneManager;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onWandInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (!ZoneManager.isWand(item)) return;
        if (!player.hasPermission("lobby.zone")) return;

        event.setCancelled(true);

        Action action = event.getAction();

        if (action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR) {
            ZoneSelectionSession session = zoneManager.getOrCreateSession(player.getUniqueId());
            if (session.removeLastCorner()) {
                player.sendMessage("§cRemoved last corner. §7(" + session.size() + " remaining)");
            } else {
                player.sendMessage("§cNo corners to remove.");
            }
            return;
        }

        if (action == Action.RIGHT_CLICK_BLOCK) {
            if (player.isSneaking()) {
                ZoneSelectionSession session = zoneManager.getOrCreateSession(player.getUniqueId());
                for (String line : session.buildSummary()) {
                    player.sendMessage(line);
                }
                return;
            }

            Location cornerLoc = event.getClickedBlock().getLocation();
            ZoneSelectionSession session = zoneManager.getOrCreateSession(player.getUniqueId());

            if (!session.isEmpty() && session.getWorldName() != null) {
                if (!session.getWorldName().equals(cornerLoc.getWorld().getName())) {
                    player.sendMessage("§cAll corners must be in the same world!");
                    return;
                }
            }

            int index = session.addCorner(cornerLoc);
            player.sendMessage(String.format("§aCorner §f#%d §aadded at §f(%d, %d)§a. §7[%d total%s]", index, cornerLoc.getBlockX(), cornerLoc.getBlockZ(), session.size(), session.isComplete() ? " — §apolygon ready§7" : " — need " + (3 - session.size()) + " more"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.isCancelled() || event.getTo() == null) return;

        Player player = event.getPlayer();
        Location to = event.getTo();
        Location from = event.getFrom();

        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        Zone deniedTo = zoneManager.getDeniedZoneAt(player, to);
        if (deniedTo != null) {
            event.setCancelled(true);
            Vector knockback = ZoneManager.calculateKnockbackVector(player, deniedTo);
            player.setVelocity(knockback);
            sendDenyMessage(player, deniedTo);
        }
    }


    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        zoneManager.clearSession(event.getPlayer().getUniqueId());
        denyMessageCooldown.remove(event.getPlayer().getUniqueId());
    }

    private void sendDenyMessage(Player player, Zone zone) {
        long now = System.currentTimeMillis();
        Long last = denyMessageCooldown.get(player.getUniqueId());
        if (last != null && now - last < DENY_MESSAGE_COOLDOWN_MS) return;
        denyMessageCooldown.put(player.getUniqueId(), now);
        player.sendMessage("§cYou need permission §e" + zone.getRequiredPermission() + "§c to enter this area!");
    }
}

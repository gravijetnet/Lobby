package net.gravijet.lobby.zone;

import net.gravijet.lobby.Main;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
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
            Zone deniedFrom = zoneManager.getDeniedZoneAt(player, from);
            // If player is already inside a denied zone (i.e., moving within it)
            if (deniedFrom != null && deniedFrom.equals(deniedTo)) {
                // Player is inside the zone, check if near edge
                Location nearestBoundary = ZoneManager.findNearestBoundaryPoint(player.getLocation(), deniedTo);
                double distanceToEdge = nearestBoundary != null ? nearestBoundary.distance(player.getLocation()) : Double.MAX_VALUE;
                // If near edge (within 2 blocks), knockback out; otherwise teleport to spawn
                if (distanceToEdge <= 2.0) {
                    Vector knockback = ZoneManager.calculateKnockbackVector(player, deniedTo);
                    player.setVelocity(knockback);
                    sendDenyMessage(player, deniedTo);
                } else {
                    Location spawn = plugin.getSpawnLocation();
                    if (spawn != null && zoneManager.getDeniedZoneAt(player, spawn) == null) {
                        player.teleport(spawn);
                        player.sendMessage("§cYou are not allowed to be in this area! Teleported to spawn.");
                    } else {
                        // No spawn set or spawn is also in a denied zone, fallback to knockback
                        Vector knockback = ZoneManager.calculateKnockbackVector(player, deniedTo);
                        player.setVelocity(knockback);
                        sendDenyMessage(player, deniedTo);
                    }
                }
            } else {
                // Player is trying to enter the zone from outside
                Vector knockback = ZoneManager.calculateKnockbackVector(player, deniedTo);
                player.setVelocity(knockback);
                sendDenyMessage(player, deniedTo);
            }
        }
    }


    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Location loc = player.getLocation();
        Zone deniedZone = zoneManager.getDeniedZoneAt(player, loc);
        if (deniedZone != null) {
            // Player logged inside a denied zone, teleport to spawn if safe
            Location spawn = plugin.getSpawnLocation();
            if (spawn != null && zoneManager.getDeniedZoneAt(player, spawn) == null) {
                player.teleport(spawn);
                player.sendMessage("§cYou were in a restricted area! Teleported to spawn.");
            } else {
                // No safe spawn, try to knockback out of zone
                Location nearestBoundary = ZoneManager.findNearestBoundaryPoint(loc, deniedZone);
                double distanceToEdge = nearestBoundary != null ? nearestBoundary.distance(loc) : Double.MAX_VALUE;
                if (distanceToEdge <= 2.0) {
                    Vector knockback = ZoneManager.calculateKnockbackVector(player, deniedZone);
                    player.setVelocity(knockback);
                    sendDenyMessage(player, deniedZone);
                } else {
                    // Cannot knockback, just warn
                    player.sendMessage("§cYou are in a restricted area! Leave immediately.");
                }
            }
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
        String message = zone.getDenyMessage();
        if (message.contains("{permission}")) {
            message = message.replace("{permission}", zone.getRequiredPermission());
        }
        player.sendMessage(message);
    }
}

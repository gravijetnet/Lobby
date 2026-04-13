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

import java.util.HashMap;
import java.util.List;
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
        if (deniedTo == null) return;

        event.setCancelled(true);
        sendDenyMessage(player, deniedTo);

        Zone deniedFrom = zoneManager.getDeniedZoneAt(player, from);
        if (deniedFrom != null && deniedFrom.equals(deniedTo)) {
            // Player is already inside the denied zone — teleport to safe position outside
            Location safePos = calcSafeOutsidePoint(from, deniedFrom);
            if (safePos != null) {
                player.teleport(safePos);
            } else {
                Location spawn = plugin.getSpawnLocation();
                if (spawn != null && zoneManager.getDeniedZoneAt(player, spawn) == null) {
                    player.teleport(spawn);
                }
            }
        }
        // If entering from outside: cancelling the event keeps player at `from` (outside the zone)
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Location loc = player.getLocation();
        Zone deniedZone = zoneManager.getDeniedZoneAt(player, loc);
        if (deniedZone != null) {
            Location spawn = plugin.getSpawnLocation();
            if (spawn != null && zoneManager.getDeniedZoneAt(player, spawn) == null) {
                player.teleport(spawn);
                player.sendMessage("§cYou were in a restricted area! Teleported to spawn.");
            } else {
                Location safePos = calcSafeOutsidePoint(loc, deniedZone);
                if (safePos != null) {
                    player.teleport(safePos);
                    sendDenyMessage(player, deniedZone);
                } else {
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

    /**
     * Calculates a safe position just outside the zone boundary nearest to playerLoc.
     * Uses the zone centroid to determine the outward direction from the boundary edge.
     */
    private Location calcSafeOutsidePoint(Location playerLoc, Zone zone) {
        Location boundary = ZoneManager.findNearestBoundaryPoint(playerLoc, zone);
        if (boundary == null) return null;

        // Calculate zone centroid
        List<int[]> corners = zone.getCorners();
        double cx = 0, cz = 0;
        for (int[] corner : corners) {
            cx += corner[0] + 0.5;
            cz += corner[1] + 0.5;
        }
        cx /= corners.size();
        cz /= corners.size();

        // Direction from centroid outward through boundary point
        double dx = boundary.getX() - cx;
        double dz = boundary.getZ() - cz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) return null;

        // Place player 1.5 blocks beyond the boundary in the outward direction
        return new Location(
            playerLoc.getWorld(),
            boundary.getX() + (dx / len) * 1.5,
            playerLoc.getY(),
            boundary.getZ() + (dz / len) * 1.5,
            playerLoc.getYaw(),
            playerLoc.getPitch()
        );
    }
}

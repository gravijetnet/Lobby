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
    private final Map<UUID, Long> messageCooldowns = new HashMap<>();
    private static final long MESSAGE_COOLDOWN_MS = 1500L;

    public ZoneListener(Main plugin, ZoneManager zoneManager) {
        this.plugin = plugin;
        this.zoneManager = zoneManager;
    }

    // ── Zone Wand ─────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL)
    public void onWandInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        if (!ZoneManager.isWand(item)) return;
        if (!player.hasPermission("lobby.zone")) return;

        event.setCancelled(true);

        Action action = event.getAction();

        // Left-click: remove last corner
        if (action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR) {
            ZoneSelectionSession session = zoneManager.getOrCreateSession(player.getUniqueId());
            if (session.removeLastCorner()) {
                player.sendMessage("§cRemoved last corner. §7(" + session.size() + " remaining)");
            } else {
                player.sendMessage("§cNo corners to remove.");
            }
            return;
        }

        // Shift + right-click: show summary
        if ((action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR) && player.isSneaking()) {
            ZoneSelectionSession session = zoneManager.getOrCreateSession(player.getUniqueId());
            for (String line : session.buildSummary()) {
                player.sendMessage(line);
            }
            return;
        }

        // Right-click block: add corner
        if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            Location cornerLoc = event.getClickedBlock().getLocation();
            ZoneSelectionSession session = zoneManager.getOrCreateSession(player.getUniqueId());

            if (!session.isEmpty() && session.getWorldName() != null
                    && !session.getWorldName().equals(cornerLoc.getWorld().getName())) {
                player.sendMessage("§cAll corners must be in the same world!");
                return;
            }

            int index = session.addCorner(cornerLoc);
            player.sendMessage(String.format(
                "§aCorner §f#%d §aadded at §f(%d, %d)§a. §7[%d total%s]",
                index,
                cornerLoc.getBlockX(), cornerLoc.getBlockZ(),
                session.size(),
                session.isComplete() ? " — §apolygon ready§7" : " — need " + (3 - session.size()) + " more"
            ));
        }
    }

    // ── Zone Entry / Movement ─────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.isCancelled() || event.getTo() == null) return;

        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();

        // Skip pure head rotation (no block change)
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        Zone denied = zoneManager.getDeniedZoneAt(player, to);
        if (denied == null) return;

        event.setCancelled(true);
        sendDenyMessage(player, denied);

        // If already inside the denied zone, push player to safety
        Zone deniedFrom = zoneManager.getDeniedZoneAt(player, from);
        if (deniedFrom != null && deniedFrom.getName().equals(denied.getName())) {
            Location safe = calcSafePosition(from, denied);
            if (safe != null) {
                player.teleport(safe);
            } else {
                Location spawn = plugin.getSpawnLocation();
                if (spawn != null && zoneManager.getDeniedZoneAt(player, spawn) == null) {
                    player.teleport(spawn);
                }
            }
        }
        // If entering from outside: cancelling event keeps player at `from`
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Zone denied = zoneManager.getDeniedZoneAt(player, player.getLocation());
        if (denied == null) return;

        Location spawn = plugin.getSpawnLocation();
        if (spawn != null && zoneManager.getDeniedZoneAt(player, spawn) == null) {
            player.teleport(spawn);
            player.sendMessage("§cYou were in a restricted area! Teleported to spawn.");
        } else {
            Location safe = calcSafePosition(player.getLocation(), denied);
            if (safe != null) {
                player.teleport(safe);
                sendDenyMessage(player, denied);
            } else {
                player.sendMessage("§cYou are in a restricted area! Leave immediately.");
            }
        }
    }

    // ── Cleanup ───────────────────────────────────────────────────────────────

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        zoneManager.clearSession(uuid);
        messageCooldowns.remove(uuid);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void sendDenyMessage(Player player, Zone zone) {
        long now = System.currentTimeMillis();
        Long last = messageCooldowns.get(player.getUniqueId());
        if (last != null && now - last < MESSAGE_COOLDOWN_MS) return;
        messageCooldowns.put(player.getUniqueId(), now);

        String msg = zone.getDenyMessage();
        if (msg.contains("{permission}")) {
            msg = msg.replace("{permission}", zone.getRequiredPermission());
        }
        player.sendMessage(msg);
    }

    private Location calcSafePosition(Location playerLoc, Zone zone) {
        Location boundary = ZoneManager.findNearestBoundaryPoint(playerLoc, zone);
        if (boundary == null) return null;

        List<int[]> corners = zone.getCorners();
        double cx = 0, cz = 0;
        for (int[] c : corners) {
            cx += c[0] + 0.5;
            cz += c[1] + 0.5;
        }
        cx /= corners.size();
        cz /= corners.size();

        double dx = boundary.getX() - cx;
        double dz = boundary.getZ() - cz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) return null;

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

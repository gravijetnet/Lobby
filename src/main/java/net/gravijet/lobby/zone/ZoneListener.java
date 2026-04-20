package net.gravijet.lobby.zone;

import net.gravijet.lobby.Main;
import org.bukkit.Bukkit;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ZoneListener implements Listener {

    private final Main plugin;
    private final ZoneManager zoneManager;
    private final Map<UUID, Long> messageCooldowns = new HashMap<>();
    private static final long MESSAGE_COOLDOWN_MS = 1500L;
    // Tracks how many ticks a player has been stuck in a denied zone
    private final Map<UUID, Integer> stuckTicks = new HashMap<>();

    public ZoneListener(Main plugin, ZoneManager zoneManager) {
        this.plugin = plugin;
        this.zoneManager = zoneManager;
        startStuckCheck();
    }

    private void startStuckCheck() {
        // Runs every 10 ticks (~0.5s). After 3 fires (1.5s) stuck → force teleport.
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                Zone denied = zoneManager.getDeniedZoneAt(player, player.getLocation());
                if (denied == null) {
                    stuckTicks.remove(player.getUniqueId());
                    continue;
                }

                int fires = stuckTicks.merge(player.getUniqueId(), 1, Integer::sum);

                if (fires >= 3) {
                    stuckTicks.put(player.getUniqueId(), 0);
                    ejectToSpawn(player, denied);
                    continue;
                }

                Location boundary = ZoneManager.findNearestBoundaryPoint(player.getLocation(), denied);
                double depth = 0;
                if (boundary != null) {
                    double dx = player.getLocation().getX() - boundary.getX();
                    double dz = player.getLocation().getZ() - boundary.getZ();
                    depth = Math.sqrt(dx * dx + dz * dz);
                }
                double strength = Math.max(0.6, Math.min(0.6 + depth * 0.8 + fires * 0.3, 5.0));
                applyKnockback(player, denied, strength);
            }
        }, 10L, 10L);
    }

    private void ejectToSpawn(Player player, Zone denied) {
        Location spawn = plugin.getSpawnLocation();
        if (spawn != null && zoneManager.getDeniedZoneAt(player, spawn) == null) {
            player.teleport(spawn);
            return;
        }
        // Spawn not available — scan outward from boundary
        Location safe = findSafeOutsideLocation(player.getLocation(), denied);
        if (safe != null) {
            player.teleport(safe);
        }
    }

    /** Walks outward from the nearest boundary in steps until clear of all denied zones. */
    private Location findSafeOutsideLocation(Location from, Zone zone) {
        Location boundary = ZoneManager.findNearestBoundaryPoint(from, zone);
        if (boundary == null) return null;

        List<int[]> corners = zone.getCorners();
        double cx = 0, cz = 0;
        for (int[] c : corners) { cx += c[0] + 0.5; cz += c[1] + 0.5; }
        cx /= corners.size(); cz /= corners.size();

        double dx = boundary.getX() - cx;
        double dz = boundary.getZ() - cz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) return null;
        double ux = dx / len, uz = dz / len;

        for (double dist = 1.5; dist <= 10.0; dist += 0.5) {
            Location candidate = new Location(
                from.getWorld(),
                boundary.getX() + ux * dist,
                from.getY(),
                boundary.getZ() + uz * dist,
                from.getYaw(), from.getPitch()
            );
            if (zoneManager.getZoneAt(candidate) == null) {
                return candidate;
            }
        }
        return null;
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

        Zone deniedFrom = zoneManager.getDeniedZoneAt(player, from);
        boolean alreadyInside = deniedFrom != null && deniedFrom.getName().equals(denied.getName());

        if (alreadyInside) {
            // Player is already inside — apply knockback to push them out
            Location boundary = ZoneManager.findNearestBoundaryPoint(from, denied);
            double depth = 0;
            if (boundary != null) {
                double dx = from.getX() - boundary.getX();
                double dz = from.getZ() - boundary.getZ();
                depth = Math.sqrt(dx * dx + dz * dz);
            }
            final double strength = Math.max(0.4, Math.min(0.4 + depth * 0.6, 3.5));
            final Zone finalDenied = denied;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                applyKnockback(player, finalDenied, strength);
            });
        } else {
            // Entering from outside: teleport player 2 blocks back so they cannot get stuck
            final Location pushFrom = from.clone();
            final Location pushTo = to.clone();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                Vector dir = pushFrom.toVector().subtract(pushTo.toVector());
                Location dest = pushFrom.clone();
                if (dir.lengthSquared() > 0.001) {
                    dest = pushFrom.clone().add(dir.normalize().multiply(2.0));
                }
                dest.setYaw(player.getLocation().getYaw());
                dest.setPitch(player.getLocation().getPitch());
                player.teleport(dest);
            });
        }
    }

    private void applyKnockback(Player player, Zone zone, double strength) {
        Location loc = player.getLocation();
        Location boundary = ZoneManager.findNearestBoundaryPoint(loc, zone);
        if (boundary == null) {
            player.setVelocity(new Vector(0, 0.3, 0));
            return;
        }
        boolean inside = zone.contains(loc);
        double dx, dz;
        if (inside) {
            // Push toward nearest boundary to exit
            dx = boundary.getX() - loc.getX();
            dz = boundary.getZ() - loc.getZ();
        } else {
            // Push away from boundary
            dx = loc.getX() - boundary.getX();
            dz = loc.getZ() - boundary.getZ();
        }
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 0.01) {
            player.setVelocity(new Vector(dx / len * strength, 0.3, dz / len * strength));
        } else {
            player.setVelocity(new Vector(0, 0.5, 0));
        }
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
        stuckTicks.remove(uuid);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Location calcSafePosition(Location loc, Zone denied) {
        double[] offsets = {2, 4, 6, 8};
        double[] angles = {0, 45, 90, 135, 180, 225, 270, 315};
        for (double r : offsets) {
            for (double a : angles) {
                double rad = Math.toRadians(a);
                Location candidate = loc.clone().add(Math.cos(rad) * r, 0, Math.sin(rad) * r);
                if (!denied.contains(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

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

}

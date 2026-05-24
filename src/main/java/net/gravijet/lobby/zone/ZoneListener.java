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
    private int stuckCheckTaskId = -1;

    public ZoneListener(Main plugin, ZoneManager zoneManager) {
        this.plugin = plugin;
        this.zoneManager = zoneManager;
        startStuckCheck();
    }

    public void stopTasks() {
        if (stuckCheckTaskId != -1) {
            Bukkit.getScheduler().cancelTask(stuckCheckTaskId);
            stuckCheckTaskId = -1;
        }
    }

    private void startStuckCheck() {
        // Runs every 10 ticks (~0.5s). After 3 fires (1.5s) stuck → force teleport.
        stuckCheckTaskId = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                Zone denied = zoneManager.getDeniedZoneAt(player, player.getLocation());
                if (denied == null) {
                    stuckTicks.remove(player.getUniqueId());
                    continue;
                }

                int fires = stuckTicks.merge(player.getUniqueId(), 1, Integer::sum);

                if (fires >= 3) {
                    stuckTicks.remove(player.getUniqueId());
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
        }, 10L, 10L).getTaskId();
    }

    private void ejectToSpawn(Player player, Zone denied) {
        Location spawn = plugin.getSpawnLocation();
        if (spawn != null && zoneManager.getDeniedZoneAt(player, spawn) == null) {
            player.teleport(spawn);
            return;
        }
        // Spawn not available — scan outward from boundary
        Location safe = findSafeOutsideLocation(player, player.getLocation(), denied);
        if (safe != null) {
            player.teleport(safe);
        }
    }

    /** Walks outward from the nearest boundary in steps until clear of all denied zones for this player. */
    private Location findSafeOutsideLocation(Player player, Location from, Zone zone) {
        Location boundary = ZoneManager.findNearestBoundaryPoint(from, zone);
        if (boundary == null) return null;

        List<int[]> corners = zone.getCorners();
        if (corners.isEmpty()) return null;
        double cx = 0, cz = 0;
        for (int[] c : corners) { cx += c[0] + 0.5; cz += c[1] + 0.5; }
        cx /= corners.size(); cz /= corners.size();

        double dx = boundary.getX() - cx;
        double dz = boundary.getZ() - cz;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 0.01) return null;
        double ux = dx / len, uz = dz / len;

        for (double dist = 1.5; dist <= 10.0; dist += 0.5) {
            double cx2 = boundary.getX() + ux * dist;
            double cz2 = boundary.getZ() + uz * dist;
            // Scan downward from the player's Y to find actual solid ground at this XZ
            Location grounded = findGroundAt(from.getWorld(), cx2, from.getY(), cz2, from.getYaw(), from.getPitch());
            if (grounded == null) continue;
            if (zoneManager.getDeniedZoneAt(player, grounded) != null) continue;
            return grounded;
        }
        return null;
    }

    private Location findGroundAt(org.bukkit.World world, double x, double startY, double z, float yaw, float pitch) {
        if (world == null) return null;
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int scanY = (int) Math.floor(startY);
        // Scan up to 8 blocks down from the player's Y to find solid ground
        for (int dy = 0; dy <= 8; dy++) {
            int checkY = scanY - dy;
            if (checkY < 0) break;
            org.bukkit.block.Block below = world.getBlockAt(bx, checkY - 1, bz);
            org.bukkit.block.Block at = world.getBlockAt(bx, checkY, bz);
            org.bukkit.block.Block above = world.getBlockAt(bx, checkY + 1, bz);
            if (below.getType().isSolid() && !at.getType().isSolid() && !above.getType().isSolid()) {
                return new Location(world, x, checkY, z, yaw, pitch);
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
                plugin.getMessages().send(player, "zone.wand.corner-removed",
                        "count", String.valueOf(session.size()));
            } else {
                plugin.getMessages().send(player, "zone.wand.no-corners");
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

        // Right-click block: add corner (skip when sneaking — sneak+right-click shows summary above)
        if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null && !player.isSneaking()) {
            Location cornerLoc = event.getClickedBlock().getLocation();
            ZoneSelectionSession session = zoneManager.getOrCreateSession(player.getUniqueId());

            if (cornerLoc.getWorld() == null) return;

            if (!session.isEmpty() && session.getWorldName() != null
                    && !session.getWorldName().equals(cornerLoc.getWorld().getName())) {
                plugin.getMessages().send(player, "zone.wand.wrong-world");
                return;
            }

            int index = session.addCorner(cornerLoc);
            String suffix = session.isComplete()
                    ? plugin.getMessages().get("zone.wand.complete-suffix")
                    : plugin.getMessages().format("zone.wand.incomplete-suffix",
                            "remaining", String.valueOf(3 - session.size()));
            player.sendMessage(plugin.getMessages().format("zone.wand.corner-added",
                    "index", String.valueOf(index),
                    "x", String.valueOf(cornerLoc.getBlockX()),
                    "z", String.valueOf(cornerLoc.getBlockZ()),
                    "total", String.valueOf(session.size()),
                    "suffix", suffix));
        }
    }

    // ── Zone Entry / Movement ─────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
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

        // A player is "already inside" if their 'from' location is also in a denied zone
        // (any denied zone, not necessarily the same one — covers overlapping zones).
        Zone deniedFrom = zoneManager.getDeniedZoneAt(player, from);
        boolean alreadyInside = deniedFrom != null;

        final UUID uuid = player.getUniqueId();

        if (alreadyInside) {
            // Player is already inside — apply knockback to push them out.
            // Use the zone they're actually in (deniedFrom) for the boundary calculation,
            // not the target zone (denied), which may be a different overlapping zone.
            Location boundary = ZoneManager.findNearestBoundaryPoint(from, deniedFrom);
            double depth = 0;
            if (boundary != null) {
                double dx = from.getX() - boundary.getX();
                double dz = from.getZ() - boundary.getZ();
                depth = Math.sqrt(dx * dx + dz * dz);
            }
            final double strength = Math.max(0.45, Math.min(0.45 + depth * 0.4, 1.2));
            final Zone finalDenied = deniedFrom;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) return;
                applyKnockback(p, finalDenied, strength);
            });
        } else {
            // Entering from outside: arc-bounce like a slime block
            final Location pushFrom = from.clone();
            final Location pushTo = to.clone();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p == null || !p.isOnline()) return;
                Vector dir = pushFrom.toVector().subtract(pushTo.toVector());
                Vector vel;
                if (dir.lengthSquared() > 0.001) {
                    vel = dir.setY(0).normalize().multiply(0.55);
                } else {
                    vel = new Vector(0, 0, 0);
                }
                vel.setY(0.45);
                p.setVelocity(vel);
            });
        }
    }

    private void applyKnockback(Player player, Zone zone, double strength) {
        Location loc = player.getLocation();
        Location boundary = ZoneManager.findNearestBoundaryPoint(loc, zone);
        if (boundary == null) {
            // No boundary found — push away using player's look direction (horizontal component)
            Vector look = player.getLocation().getDirection();
            look.setY(0);
            if (look.lengthSquared() > 0.001) {
                look = look.normalize().multiply(0.3);
            } else {
                look = new Vector(0.3, 0, 0);
            }
            player.setVelocity(new Vector(look.getX(), 0.3, look.getZ()));
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
            player.setVelocity(new Vector(dx / len * strength, 0.45, dz / len * strength));
        } else {
            player.setVelocity(new Vector(0, 0.5, 0));
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Defer by 2 ticks so this runs after LobbyListener.setupPlayer's 1-tick-delayed
        // spawn teleport; otherwise setupPlayer would overwrite our zone-eject destination.
        final UUID uuid = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player player = Bukkit.getPlayer(uuid);
            if (player == null || !player.isOnline()) return;
            Zone denied = zoneManager.getDeniedZoneAt(player, player.getLocation());
            if (denied == null) return;

            Location spawn = plugin.getSpawnLocation();
            if (spawn != null && zoneManager.getDeniedZoneAt(player, spawn) == null) {
                player.teleport(spawn);
                plugin.getMessages().send(player, "zone.restricted-teleported");
            } else {
                Location safe = calcSafePosition(player, player.getLocation(), denied);
                if (safe != null) {
                    player.teleport(safe);
                    sendDenyMessage(player, denied);
                } else {
                    plugin.getMessages().send(player, "zone.restricted-leave");
                }
            }
        }, 2L);
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

    private Location calcSafePosition(Player player, Location loc, Zone denied) {
        double[] offsets = {2, 4, 6, 8};
        double[] angles = {0, 45, 90, 135, 180, 225, 270, 315};
        for (double r : offsets) {
            for (double a : angles) {
                double rad = Math.toRadians(a);
                Location candidate = loc.clone().add(Math.cos(rad) * r, 0, Math.sin(rad) * r);
                if (zoneManager.getDeniedZoneAt(player, candidate) != null) continue;
                if (!hasSolidGround(candidate)) continue;
                return candidate;
            }
        }
        return null;
    }

    private boolean hasSolidGround(Location loc) {
        if (loc == null || loc.getWorld() == null) return false;
        org.bukkit.block.Block below = loc.getWorld().getBlockAt(loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());
        org.bukkit.block.Block at = loc.getWorld().getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        org.bukkit.block.Block above = loc.getWorld().getBlockAt(loc.getBlockX(), loc.getBlockY() + 1, loc.getBlockZ());
        return below.getType().isSolid() && !at.getType().isSolid() && !above.getType().isSolid();
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
        // Split on \n so admins can use \n in /zone setmessage to produce multi-line messages.
        // sendMessage(String) on Spigot 1.8.8 does not handle embedded newlines.
        for (String line : msg.split("\n", -1)) {
            player.sendMessage(line);
        }
    }

}

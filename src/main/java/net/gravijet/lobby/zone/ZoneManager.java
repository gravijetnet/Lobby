package net.gravijet.lobby.zone;

import net.gravijet.lobby.Main;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ZoneManager {

    private static final String WAND_NAME = "§6Zone Wand";
    private static final double EDGE_SPACING = 0.65;
    private static final double PILLAR_HEIGHT = 3.0;

    private final Main plugin;
    private final Map<String, Zone> zones = new LinkedHashMap<>();
    private final Map<UUID, ZoneSelectionSession> sessions = new HashMap<>();
    private ZoneConfigManager configManager;
    private int particleTaskId = -1;

    public ZoneManager(Main plugin) {
        this.plugin = plugin;
        this.configManager = new ZoneConfigManager(plugin);
    }

    public void loadZones() {
        zones.clear();
        configManager.migrateFromMainConfig();
        configManager.loadZones(this);
    }

    public void saveAll() {
        configManager.saveZonesToConfig();
    }

    // Helper method to get zones map for ZoneConfigManager
    public Map<String, Zone> getZonesMap() {
        return zones;
    }

    public void startParticleTask() {
        if (particleTaskId != -1) Bukkit.getScheduler().cancelTask(particleTaskId);
        particleTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::tickParticles, 0L, 5L).getTaskId();
    }

    public void stopParticleTask() {
        if (particleTaskId != -1) {
            Bukkit.getScheduler().cancelTask(particleTaskId);
            particleTaskId = -1;
        }
    }

    public Zone saveZone(String name, ZoneSelectionSession session, int minY, int maxY) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("Zone name must not be empty.");
        if (!session.isComplete()) throw new IllegalArgumentException("Selection needs at least 3 corners.");
        if (minY > maxY) throw new IllegalArgumentException("minY must be <= maxY.");

        String worldName = session.getWorldName();
        if (worldName == null) throw new IllegalArgumentException("Cannot determine world from session corners.");

        List<int[]> cornerList = new ArrayList<>();
        for (Location loc : session.getCorners()) {
            cornerList.add(new int[]{loc.getBlockX(), loc.getBlockZ()});
        }

        Zone zone = new Zone(name, worldName, minY, maxY, cornerList);
        zones.put(name.toLowerCase(), zone);
        saveAll();
        return zone;
    }

    public boolean deleteZone(String name) {
        if (zones.remove(name.toLowerCase()) != null) {
            saveAll();
            return true;
        }
        return false;
    }

    public Collection<Zone> getAllZones() {
        return zones.values();
    }

    public Zone getZone(String name) {
        return zones.get(name.toLowerCase());
    }

    public boolean setZonePermission(String zoneName, String permission) {
        Zone zone = zones.get(zoneName.toLowerCase());
        if (zone == null) return false;
        zone.setRequiredPermission(permission);
        saveAll();
        return true;
    }

    public boolean setZoneDenyMessage(String zoneName, String message) {
        Zone zone = zones.get(zoneName.toLowerCase());
        if (zone == null) return false;
        // Translate color codes but do NOT replace \n here — the message is stored as-is
        // and serialized with \n→\\n. Replacing here then having deserialize replace again
        // would cause double-substitution on the next reload.
        zone.setDenyMessage(ChatColor.translateAlternateColorCodes('&', message.replace("\\n", "\n")));
        saveAll();
        return true;
    }

    public boolean setZoneAllowBlockPlacement(String zoneName, boolean allow) {
        Zone zone = zones.get(zoneName.toLowerCase());
        if (zone == null) return false;
        zone.setAllowBlockPlacement(allow);
        saveAll();
        return true;
    }

    public boolean setZoneAllowFlight(String zoneName, boolean allow) {
        Zone zone = zones.get(zoneName.toLowerCase());
        if (zone == null) return false;
        zone.setAllowFlight(allow);
        saveAll();
        return true;
    }

    public Zone getDeniedZoneAt(Player player, Location loc) {
        if (player.hasPermission("lobby.zone.bypass")) return null;
        for (Zone zone : zones.values()) {
            if (zone.contains(loc) && !zone.canEnter(player)) {
                return zone;
            }
        }
        return null;
    }

    public Zone getZoneAt(Location loc) {
        for (Zone zone : zones.values()) {
            if (zone.contains(loc)) return zone;
        }
        return null;
    }

    public boolean isFlightAllowedAt(Location loc) {
        Zone zone = getZoneAt(loc);
        return zone == null || zone.isAllowFlight();
    }

    public ZoneSelectionSession getOrCreateSession(UUID playerUUID) {
        return sessions.computeIfAbsent(playerUUID, ZoneSelectionSession::new);
    }

    public ZoneSelectionSession getSession(UUID playerUUID) {
        return sessions.get(playerUUID);
    }

    public void clearSession(UUID playerUUID) {
        sessions.remove(playerUUID);
    }

    public static ItemStack createWand() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(WAND_NAME);
            meta.setLore(Arrays.asList("§7Right-click §8» §fAdd corner", "§7Left-click  §8» §fRemove last corner", "§7Shift+Right §8» §fShow selection summary"));
            item.setItemMeta(meta);
        }
        return item;
    }

    public static boolean isWand(ItemStack item) {
        return item != null && item.getType() == Material.BLAZE_ROD && item.hasItemMeta() && WAND_NAME.equals(item.getItemMeta().getDisplayName());
    }

    private void tickParticles() {
        for (Map.Entry<UUID, ZoneSelectionSession> entry : sessions.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;

            ZoneSelectionSession session = entry.getValue();
            if (session.isEmpty()) continue;

            List<Location> corners = session.getCorners();
            double eyeY = player.getEyeLocation().getY();

            for (Location corner : corners) {
                if (!sameWorld(corner, player)) continue;
                double cx = corner.getBlockX() + 0.5;
                double cz = corner.getBlockZ() + 0.5;
                for (double dy = 0; dy <= PILLAR_HEIGHT; dy += 0.5) {
                    spawnDust(player, cx, corner.getY() + dy, cz, 1.0f, 0.5f, 0.0f);
                }
            }

            int n = corners.size();
            for (int i = 0; i < n; i++) {
                if (i == n - 1 && n < 3) continue;
                Location a = corners.get(i);
                Location b = corners.get((i + 1) % n);
                if (!sameWorld(a, player) || !sameWorld(b, player)) continue;

                double ax = a.getBlockX() + 0.5, az = a.getBlockZ() + 0.5;
                double bx = b.getBlockX() + 0.5, bz = b.getBlockZ() + 0.5;
                double dx = bx - ax, dz = bz - az;
                double len = Math.sqrt(dx * dx + dz * dz);
                if (len == 0) continue;
                double ux = dx / len, uz = dz / len;
                for (double t = 0; t <= len; t += EDGE_SPACING) {
                    spawnDust(player, ax + ux * t, eyeY, az + uz * t, 1.0f, 1.0f, 0.0f);
                }
            }
        }
    }

    private static void spawnDust(Player viewer, double x, double y, double z, float r, float g, float b) {
        Location loc = new Location(viewer.getWorld(), x, y, z);
        viewer.getWorld().spigot().playEffect(loc, Effect.COLOURED_DUST, 0, 1, r, g, b, 1, 0, 64);
    }

    private static boolean sameWorld(Location loc, Player player) {
        return loc.getWorld() != null && loc.getWorld().equals(player.getWorld());
    }

    public ZoneConfigManager getConfigManager() {
        return configManager;
    }

    public static Location findNearestBoundaryPoint(Location playerLoc, Zone zone) {
        List<int[]> corners = zone.getCorners();
        if (corners.size() < 2) return null;

        double minDistance = Double.MAX_VALUE;
        Location nearest = null;

        for (int i = 0; i < corners.size(); i++) {
            int[] a = corners.get(i);
            int[] b = corners.get((i + 1) % corners.size());

            Location locA = new Location(playerLoc.getWorld(), a[0] + 0.5, playerLoc.getY(), a[1] + 0.5);
            Location locB = new Location(playerLoc.getWorld(), b[0] + 0.5, playerLoc.getY(), b[1] + 0.5);

            Location closest = closestPointOnSegment(locA, locB, playerLoc);
            double distance = closest.distance(playerLoc);

            if (distance < minDistance) {
                minDistance = distance;
                nearest = closest;
            }
        }
        return nearest;
    }

    public static Location closestPointOnSegment(Location a, Location b, Location p) {
        Vector ab = b.toVector().subtract(a.toVector());
        double lenSq = ab.lengthSquared();
        if (lenSq < 1e-10) return a; // A and B are the same point
        Vector ap = p.toVector().subtract(a.toVector());
        double t = ap.dot(ab) / lenSq;
        if (t < 0.0) return a;
        if (t > 1.0) return b;
        return a.clone().add(ab.multiply(t));
    }

}
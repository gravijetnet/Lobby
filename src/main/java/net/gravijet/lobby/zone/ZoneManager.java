package net.gravijet.lobby.zone;

import net.gravijet.lobby.Main;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ZoneManager {

    private static final String WAND_NAME     = "§6Zone Wand";
    private static final double EDGE_SPACING  = 0.65;
    private static final double PILLAR_HEIGHT = 3.0;

    private final Main                            plugin;
    private final Map<String, Zone>               zones    = new LinkedHashMap<>();
    private final Map<UUID, ZoneSelectionSession> sessions = new HashMap<>();
    private int particleTaskId = -1;

    public ZoneManager(Main plugin) {
        this.plugin = plugin;
    }

    public void loadZones() {
        zones.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("zones");
        if (root == null) return;
        for (String name : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(name);
            if (sec == null) continue;
            Zone zone = deserializeZone(name, sec);
            if (zone != null) zones.put(name.toLowerCase(), zone);
        }
    }

    public void saveAll() {
        plugin.getConfig().set("zones", null);
        for (Zone zone : zones.values()) serializeZone(zone);
        plugin.saveConfig();
    }

    public void startParticleTask() {
        if (particleTaskId != -1) Bukkit.getScheduler().cancelTask(particleTaskId);
        particleTaskId = Bukkit.getScheduler()
                .runTaskTimer(plugin, this::tickParticles, 0L, 5L)
                .getTaskId();
    }

    public void stopParticleTask() {
        if (particleTaskId != -1) {
            Bukkit.getScheduler().cancelTask(particleTaskId);
            particleTaskId = -1;
        }
    }

    public Zone saveZone(String name, ZoneSelectionSession session, int minY, int maxY) {
        if (name == null || name.trim().isEmpty())
            throw new IllegalArgumentException("Zone name must not be empty.");
        if (!session.isComplete())
            throw new IllegalArgumentException("Selection needs at least 3 corners.");
        if (minY > maxY)
            throw new IllegalArgumentException("minY must be <= maxY.");

        String worldName = session.getWorldName();
        if (worldName == null)
            throw new IllegalArgumentException("Cannot determine world from session corners.");

        for (Location loc : session.getCorners()) {
            if (loc.getWorld() == null || !loc.getWorld().getName().equals(worldName))
                throw new IllegalArgumentException(
                        "All corners must be in the same world (" + worldName + ").");
        }

        List<Location> sc = session.getCorners();
        double centroidX = 0, centroidZ = 0;
        for (Location loc : sc) { centroidX += loc.getBlockX(); centroidZ += loc.getBlockZ(); }
        centroidX /= sc.size();
        centroidZ /= sc.size();

        List<int[]> cornerList = new ArrayList<>();
        for (Location loc : sc) {
            int bx = loc.getBlockX();
            int bz = loc.getBlockZ();
            cornerList.add(new int[]{
                    bx + (bx >= centroidX ? 1 : 0),
                    bz + (bz >= centroidZ ? 1 : 0)
            });
        }

        Zone zone = new Zone(name, worldName, minY, maxY, cornerList);
        zones.put(name.toLowerCase(), zone);
        saveAll();
        return zone;
    }

    public boolean deleteZone(String name) {
        if (zones.remove(name.toLowerCase()) != null) { saveAll(); return true; }
        return false;
    }

    public Collection<Zone> getAllZones()        { return zones.values(); }
    public Zone             getZone(String name) { return zones.get(name.toLowerCase()); }

    public boolean setZonePermission(String zoneName, String permission) {
        Zone zone = zones.get(zoneName.toLowerCase());
        if (zone == null) return false;
        if (permission != null) {
            permission = permission.trim();
            if (permission.isEmpty()) permission = null;
        }
        zone.setRequiredPermission(permission);
        saveAll();
        return true;
    }

    public boolean setZoneDenyMessage(String zoneName, String message) {
        Zone zone = zones.get(zoneName.toLowerCase());
        if (zone == null) return false;
        message = message.replace("\\n", "\n");
        zone.setDenyMessage(ChatColor.translateAlternateColorCodes('&', message));
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

    public boolean isInsideAnyZone(Location loc) {
        for (Zone zone : zones.values()) {
            if (zone.contains(loc)) return true;
        }
        return false;
    }

    public Zone getDeniedZoneAt(Player player, Location loc) {
        if (plugin.isInBuildMode(player))       return null;
        if (player.hasPermission("lobby.zone")) return null;

        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : null;
        if (worldName == null) return null;

        for (Zone zone : zones.values()) {
            if (!zone.isRestricted()) continue;
            if (!zone.containsPoint(worldName, loc.getX(), loc.getY(), loc.getZ())) continue;
            if (!zone.canEnter(player)) return zone;
        }
        return null;
    }

    public Zone getZoneAt(Location loc) {
        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : null;
        if (worldName == null) return null;
        for (Zone zone : zones.values()) {
            if (zone.containsPoint(worldName, loc.getX(), loc.getY(), loc.getZ())) return zone;
        }
        return null;
    }

    /**
     * Returns false if the player is inside any zone that disallows flight.
     */
    public boolean isFlightAllowedAt(Location loc) {
        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : null;
        if (worldName == null) return true;
        for (Zone zone : zones.values()) {
            if (!zone.isAllowFlight()
                    && zone.containsPoint(worldName, loc.getX(), loc.getY(), loc.getZ())) {
                return false;
            }
        }
        return true;
    }

    public ZoneSelectionSession getOrCreateSession(UUID playerUUID) {
        return sessions.computeIfAbsent(playerUUID, ZoneSelectionSession::new);
    }

    public ZoneSelectionSession getSession(UUID playerUUID) { return sessions.get(playerUUID); }
    public boolean hasSession(UUID playerUUID)              { return sessions.containsKey(playerUUID); }
    public void    clearSession(UUID playerUUID)            { sessions.remove(playerUUID); }

    public static ItemStack createWand() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta  meta = item.getItemMeta();
        meta.setDisplayName(WAND_NAME);
        meta.setLore(Arrays.asList(
                "§7Right-click §8» §fAdd corner",
                "§7Left-click  §8» §fRemove last corner",
                "§7Shift+Right §8» §fShow selection summary"
        ));
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isWand(ItemStack item) {
        return item != null
                && item.getType() == Material.BLAZE_ROD
                && item.hasItemMeta()
                && WAND_NAME.equals(item.getItemMeta().getDisplayName());
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
        try {
            viewer.spigot().playEffect(loc, Effect.COLOURED_DUST, 0, 0, r, g, b, 1.0f, 0, 48);
        } catch (Exception ignored) {
            viewer.getWorld().playEffect(loc, Effect.SMOKE, 0, 32);
        }
    }

    private static boolean sameWorld(Location loc, Player player) {
        return loc.getWorld() != null
                && loc.getWorld().getName().equals(player.getWorld().getName());
    }

    private Zone deserializeZone(String name, ConfigurationSection sec) {
        String world = sec.getString("world");
        if (world == null || world.isEmpty()) {
            plugin.getLogger().warning("Zone '" + name + "' has no world — skipping.");
            return null;
        }

        int minY = sec.getInt("minY", 0);
        int maxY = sec.getInt("maxY", 256);

        List<String> rawCorners = sec.getStringList("corners");
        if (rawCorners.size() < 3) {
            plugin.getLogger().warning("Zone '" + name + "' has fewer than 3 corners — skipping.");
            return null;
        }

        List<int[]> corners = new ArrayList<>();
        for (String raw : rawCorners) {
            String[] parts = raw.split(",");
            if (parts.length != 2) {
                plugin.getLogger().warning("Zone '" + name + "': invalid corner '" + raw + "' — skipping.");
                return null;
            }
            try {
                corners.add(new int[]{ Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()) });
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("Zone '" + name + "': non-integer corner '" + raw + "' — skipping.");
                return null;
            }
        }

        Zone zone = new Zone(name, world, minY, maxY, corners);

        String perm = sec.getString("required-permission", "").trim();
        if (!perm.isEmpty()) zone.setRequiredPermission(perm);

        String msg = sec.getString("deny-message", "");
        if (!msg.isEmpty()) {
            zone.setDenyMessage(ChatColor.translateAlternateColorCodes('&', msg.replace("\\n", "\n")));
        }

        zone.setAllowBlockPlacement(sec.getBoolean("allow-block-placement", false));
        zone.setAllowFlight(sec.getBoolean("allow-flight", true));
        return zone;
    }

    private void serializeZone(Zone zone) {
        String path = "zones." + zone.getName();
        plugin.getConfig().set(path + ".world", zone.getWorldName());
        plugin.getConfig().set(path + ".minY",  zone.getMinY());
        plugin.getConfig().set(path + ".maxY",  zone.getMaxY());

        List<String> cs = new ArrayList<>();
        for (int[] c : zone.getCorners()) cs.add(c[0] + "," + c[1]);
        plugin.getConfig().set(path + ".corners", cs);

        if (zone.getRequiredPermission() != null) {
            plugin.getConfig().set(path + ".required-permission", zone.getRequiredPermission());
        }
        plugin.getConfig().set(path + ".deny-message", zone.getDenyMessage().replace("\n", "\\n"));
        plugin.getConfig().set(path + ".allow-block-placement", zone.isAllowBlockPlacement());
        plugin.getConfig().set(path + ".allow-flight", zone.isAllowFlight());
    }
}

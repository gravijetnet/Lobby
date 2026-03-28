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

/**
 * Central coordinator for the zone system.
 *
 * <h3>Config format</h3>
 * <pre>
 * zones:
 *   spawn_area:
 *     world: "world"
 *     minY: 0
 *     maxY: 256
 *     corners:
 *       - "120,45"
 *       - "120,80"
 *       - "155,80"
 *     required-permission: "lobby.vip"          # optional
 *     deny-message: "&cVIP only!"               # optional, supports &-colours
 * </pre>
 */
public final class ZoneManager {

    private static final String WAND_NAME         = "§6Zone Wand";
    private static final double EDGE_SPACING      = 0.65;
    private static final double PILLAR_HEIGHT     = 3.0;

    private final Main                            plugin;
    private final Map<String, Zone>               zones    = new LinkedHashMap<>();
    private final Map<UUID, ZoneSelectionSession> sessions = new HashMap<>();
    private int particleTaskId = -1;

    public ZoneManager(Main plugin) {
        this.plugin = plugin;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    public void loadZones() {
        zones.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("zones");
        if (root == null) {
            plugin.getLogger().info("No zones section in config — starting empty.");
            return;
        }
        for (String name : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(name);
            if (sec == null) continue;
            Zone zone = deserializeZone(name, sec);
            if (zone != null) zones.put(name.toLowerCase(), zone);
        }
        plugin.getLogger().info("Loaded " + zones.size() + " zone(s).");
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

    // =========================================================================
    // Zone CRUD
    // =========================================================================

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

        // Compute centroid so we know which direction is "outward" for each corner.
        List<Location> sc = session.getCorners();
        double centroidX = 0, centroidZ = 0;
        for (Location loc : sc) { centroidX += loc.getBlockX(); centroidZ += loc.getBlockZ(); }
        centroidX /= sc.size();
        centroidZ /= sc.size();

        // Expand each corner to the outer face of the selected block so that the
        // block-centre test (bx+0.5, bz+0.5) falls inside the polygon on all sides.
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

    public Collection<Zone> getAllZones()  { return zones.values(); }
    public Zone             getZone(String name) { return zones.get(name.toLowerCase()); }

    // =========================================================================
    // Access-control CRUD
    // =========================================================================

    /**
     * Sets (or clears) the required permission for the named zone and persists.
     *
     * @param zoneName   zone name (case-insensitive)
     * @param permission permission node, or {@code null} / empty to remove restriction
     * @return {@code true} if the zone was found and updated
     */
    public boolean setZonePermission(String zoneName, String permission) {
        Zone zone = zones.get(zoneName.toLowerCase());
        if (zone == null) return false;
        zone.setRequiredPermission(permission);
        saveAll();
        return true;
    }

    /**
     * Sets the deny-message for the named zone and persists.
     * Supports {@code &}-colour codes which are translated on save.
     *
     * @param zoneName zone name (case-insensitive)
     * @param message  raw message with optional §-codes or &-codes
     * @return {@code true} if the zone was found and updated
     */
    public boolean setZoneDenyMessage(String zoneName, String message) {
        Zone zone = zones.get(zoneName.toLowerCase());
        if (zone == null) return false;
        zone.setDenyMessage(ChatColor.translateAlternateColorCodes('&', message));
        saveAll();
        return true;
    }

    // =========================================================================
    // Containment & access checks
    // =========================================================================

    /**
     * Returns {@code true} if the given block location falls inside any zone
     * (regardless of access control). Used by {@code BlockPlaceEvent}.
     */
    public boolean isInsideAnyZone(Location loc) {
        for (Zone zone : zones.values()) {
            if (zone.contains(loc)) return true;
        }
        return false;
    }

    /**
     * Returns the first zone at {@code loc} that the player is NOT allowed to
     * enter, or {@code null} if the player may be there.
     *
     * <p>Build-mode players and players with {@code lobby.zone} permission bypass
     * all restrictions so admins can inspect and set up zones freely.</p>
     */
    public Zone getRestrictedZoneAt(Player player, Location loc) {
        // Admins are never blocked.
        if (plugin.isInBuildMode(player))           return null;
        if (player.hasPermission("lobby.zone"))     return null;

        String worldName = loc.getWorld() != null ? loc.getWorld().getName() : null;
        if (worldName == null) return null;

        for (Zone zone : zones.values()) {
            if (!zone.isRestricted()) continue;
            if (zone.containsPoint(worldName, loc.getX(), loc.getY(), loc.getZ())
                    && zone.isRestrictedFor(player)) {
                return zone;
            }
        }
        return null;
    }

    // =========================================================================
    // Session management
    // =========================================================================

    public ZoneSelectionSession getOrCreateSession(UUID playerUUID) {
        return sessions.computeIfAbsent(playerUUID, ZoneSelectionSession::new);
    }

    public ZoneSelectionSession getSession(UUID playerUUID) { return sessions.get(playerUUID); }
    public boolean hasSession(UUID playerUUID)              { return sessions.containsKey(playerUUID); }
    public void    clearSession(UUID playerUUID)            { sessions.remove(playerUUID); }

    // =========================================================================
    // Zone Wand
    // =========================================================================

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

    // =========================================================================
    // Particle rendering (in-progress selections only)
    // =========================================================================

    private void tickParticles() {
        for (Map.Entry<UUID, ZoneSelectionSession> entry : sessions.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;

            ZoneSelectionSession session = entry.getValue();
            if (session.isEmpty()) continue;

            List<Location> corners = session.getCorners();
            double eyeY = player.getEyeLocation().getY();

            // Corner pillars (orange) — drawn inline to avoid allocating per-tick lists
            for (Location corner : corners) {
                if (!sameWorld(corner, player)) continue;
                double cx = corner.getBlockX() + 0.5;
                double cz = corner.getBlockZ() + 0.5;
                for (double dy = 0; dy <= PILLAR_HEIGHT; dy += 0.5) {
                    spawnDust(player, cx, corner.getY() + dy, cz, 1.0f, 0.5f, 0.0f);
                }
            }

            // Edges (yellow), close polygon when >= 3 corners
            int n = corners.size();
            for (int i = 0; i < n; i++) {
                if (i == n - 1 && n < 3) continue; // don't close until valid
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

    /**
     * Sends a single COLOURED_DUST particle to the viewer.
     * In Spigot 1.8, colour is encoded as offsetX/Y/Z = r/g/b (0.0-1.0),
     * amount=0 (required for colouring), speed=particle size.
     */
    private static void spawnDust(Player viewer, double x, double y, double z,
                                   float r, float g, float b) {
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

    // =========================================================================
    // Config serialisation
    // =========================================================================

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
                corners.add(new int[]{ Integer.parseInt(parts[0].trim()),
                                       Integer.parseInt(parts[1].trim()) });
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("Zone '" + name + "': non-integer corner '" + raw + "' — skipping.");
                return null;
            }
        }

        Zone zone = new Zone(name, world, minY, maxY, corners);

        // Access-control fields (optional)
        String perm = sec.getString("required-permission", "");
        if (!perm.isEmpty()) zone.setRequiredPermission(perm);

        String msg = sec.getString("deny-message", "");
        if (!msg.isEmpty()) zone.setDenyMessage(ChatColor.translateAlternateColorCodes('&', msg));

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

        // Access-control — only write when set so the config stays tidy
        if (zone.getRequiredPermission() != null) {
            plugin.getConfig().set(path + ".required-permission", zone.getRequiredPermission());
        }
        plugin.getConfig().set(path + ".deny-message", zone.getDenyMessage());
    }
}

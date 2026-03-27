package net.gravijet.lobby.zone;

import net.gravijet.lobby.Main;
import org.bukkit.Bukkit;
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
 * Responsibilities:
 * <ul>
 *   <li>Load and persist zones to/from {@code config.yml} under the {@code zones} key.</li>
 *   <li>Manage per-player selection sessions while they wield the Zone Wand.</li>
 *   <li>Provide {@link #isInsideAnyZone(Location)} for use in BlockPlaceEvent.</li>
 *   <li>Run a repeating particle task that visualises in-progress selections and
 *       saved zone boundaries to authorised players.</li>
 * </ul>
 *
 * Config format:
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
 * </pre>
 */
public final class ZoneManager {

    /** Display name that identifies the Zone Wand item. */
    private static final String WAND_NAME = "§6Zone Wand";

    /** Block-unit spacing between consecutive edge particles. */
    private static final double EDGE_SPACING_ACTIVE = 0.65;

    /** Height of the corner-pillar decoration in blocks. */
    private static final double PILLAR_HEIGHT = 3.0;

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

    /** Loads all zones from the {@code zones} section of {@code config.yml}. */
    public void loadZones() {
        zones.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("zones");
        if (root == null) {
            plugin.getLogger().info("No zones section in config — starting with empty zone list.");
            return;
        }

        for (String name : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(name);
            if (sec == null) continue;

            Zone zone = deserializeZone(name, sec);
            if (zone != null) {
                zones.put(name.toLowerCase(), zone);
            }
        }
        plugin.getLogger().info("Loaded " + zones.size() + " zone(s).");
    }

    /**
     * Serialises all zones to {@code config.yml} and saves the file.
     * Called automatically by {@link #saveZone} and {@link #deleteZone}.
     */
    public void saveAll() {
        plugin.getConfig().set("zones", null); // clear existing data
        for (Zone zone : zones.values()) {
            serializeZone(zone);
        }
        plugin.saveConfig();
    }

    /**
     * Starts the repeating particle-render task (every 5 ticks = 0.25 s).
     * Safe to call multiple times — cancels any previously running task first.
     */
    public void startParticleTask() {
        if (particleTaskId != -1) {
            Bukkit.getScheduler().cancelTask(particleTaskId);
        }
        particleTaskId = Bukkit.getScheduler()
                .runTaskTimer(plugin, this::tickParticles, 0L, 5L)
                .getTaskId();
    }

    /** Cancels the particle task. Called from {@code Main.onDisable()}. */
    public void stopParticleTask() {
        if (particleTaskId != -1) {
            Bukkit.getScheduler().cancelTask(particleTaskId);
            particleTaskId = -1;
        }
    }

    // =========================================================================
    // Zone CRUD
    // =========================================================================

    /**
     * Converts the given session's corners into an immutable {@link Zone} and
     * stores it in the registry. Persists immediately to config.
     *
     * @param name    zone name; used as the config key (lowercased for lookup)
     * @param session must have at least 3 corners
     * @param minY    vertical lower bound (inclusive)
     * @param maxY    vertical upper bound (inclusive); must be &ge; minY
     * @return the newly created zone
     * @throws IllegalArgumentException if constraints are violated
     */
    public Zone saveZone(String name, ZoneSelectionSession session, int minY, int maxY) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Zone name must not be empty.");
        }
        if (!session.isComplete()) {
            throw new IllegalArgumentException("Selection needs at least 3 corners.");
        }
        if (minY > maxY) {
            throw new IllegalArgumentException("minY must be <= maxY.");
        }

        String worldName = session.getWorldName();
        if (worldName == null) {
            throw new IllegalArgumentException("Cannot determine world from session corners.");
        }

        // Validate that all corners are in the same world.
        for (Location loc : session.getCorners()) {
            if (loc.getWorld() == null || !loc.getWorld().getName().equals(worldName)) {
                throw new IllegalArgumentException(
                        "All corners must be in the same world (" + worldName + ").");
            }
        }

        // Compute the centroid of the selected block positions so we can determine
        // which direction is "outward" for each corner.
        List<Location> sessionCorners = session.getCorners();
        double centroidX = 0, centroidZ = 0;
        for (Location loc : sessionCorners) {
            centroidX += loc.getBlockX();
            centroidZ += loc.getBlockZ();
        }
        centroidX /= sessionCorners.size();
        centroidZ /= sessionCorners.size();

        // Expand each corner to the outer face of the selected block.
        // A selected block at (bx, bz) occupies the unit square bx→bx+1, bz→bz+1.
        // Corners on the "positive" side of the centroid must shift +1 so that the
        // block-centre test point (bx+0.5, bz+0.5) falls inside the polygon on all
        // four sides — including the North/South edges.
        List<int[]> cornerList = new ArrayList<>();
        for (Location loc : sessionCorners) {
            int bx = loc.getBlockX();
            int bz = loc.getBlockZ();
            int cx = bx + (bx >= centroidX ? 1 : 0);
            int cz = bz + (bz >= centroidZ ? 1 : 0);
            cornerList.add(new int[]{ cx, cz });
        }

        Zone zone = new Zone(name, worldName, minY, maxY, cornerList);
        zones.put(name.toLowerCase(), zone);
        saveAll();
        return zone;
    }

    /**
     * Removes the named zone from the registry and persists the change.
     *
     * @return {@code true} if the zone existed and was removed
     */
    public boolean deleteZone(String name) {
        if (zones.remove(name.toLowerCase()) != null) {
            saveAll();
            return true;
        }
        return false;
    }

    /** All registered zones. Iteration order matches insertion order. */
    public Collection<Zone> getAllZones() {
        return zones.values();
    }

    /**
     * Returns the zone with the given name (case-insensitive), or {@code null}.
     */
    public Zone getZone(String name) {
        return zones.get(name.toLowerCase());
    }

    // =========================================================================
    // Containment check — called from LobbyListener.onBlockPlace
    // =========================================================================

    /**
     * Returns {@code true} if the given block location falls inside any
     * registered zone. Uses {@link Zone#contains(Location)} for each zone.
     */
    public boolean isInsideAnyZone(Location loc) {
        for (Zone zone : zones.values()) {
            if (zone.contains(loc)) return true;
        }
        return false;
    }

    /**
     * Returns the first zone that contains the given location, or {@code null}.
     */
    public Zone getContainingZone(Location loc) {
        for (Zone zone : zones.values()) {
            if (zone.contains(loc)) return zone;
        }
        return null;
    }

    // =========================================================================
    // Session management
    // =========================================================================

    /**
     * Returns the existing session for the player, creating and storing a new
     * empty session if none exists yet.
     */
    public ZoneSelectionSession getOrCreateSession(UUID playerUUID) {
        return sessions.computeIfAbsent(playerUUID, ZoneSelectionSession::new);
    }

    /**
     * Returns the active session for the player, or {@code null} if the player
     * has no session.
     */
    public ZoneSelectionSession getSession(UUID playerUUID) {
        return sessions.get(playerUUID);
    }

    /** Returns {@code true} if the player currently has an active session. */
    public boolean hasSession(UUID playerUUID) {
        return sessions.containsKey(playerUUID);
    }

    /** Removes the session for the given player, discarding any unsaved corners. */
    public void clearSession(UUID playerUUID) {
        sessions.remove(playerUUID);
    }

    // =========================================================================
    // Zone Wand item
    // =========================================================================

    /**
     * Creates a new Zone Wand (BLAZE_ROD) with the identifying display name
     * and instructional lore.
     */
    public static ItemStack createWand() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(WAND_NAME);
        meta.setLore(Arrays.asList(
                "§7Right-click §8» §fAdd corner",
                "§7Left-click  §8» §fRemove last corner",
                "§7Shift+Right §8» §fShow selection summary"
        ));
        item.setItemMeta(meta);
        return item;
    }

    /**
     * Returns {@code true} if the item is a Zone Wand (BLAZE_ROD with the
     * exact display name).
     */
    public static boolean isWand(ItemStack item) {
        return item != null
                && item.getType() == Material.BLAZE_ROD
                && item.hasItemMeta()
                && WAND_NAME.equals(item.getItemMeta().getDisplayName());
    }

    // =========================================================================
    // Particle rendering
    // =========================================================================

    /**
     * Called every 5 ticks. Renders:
     * <ol>
     *   <li>Yellow edge outlines + orange corner pillars for players with an
     *       active selection session, showing their current polygon.</li>
     *   <li>Green edge outlines of every saved zone to admin players (those
     *       with {@code lobby.zone}) who are within {@value VISIBILITY_RADIUS}
     *       blocks of the zone.</li>
     * </ol>
     */
    private void tickParticles() {
        // ── 1. In-progress selections ─────────────────────────────────────────
        for (Map.Entry<UUID, ZoneSelectionSession> entry : sessions.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;

            ZoneSelectionSession session = entry.getValue();
            if (session.isEmpty()) continue;

            List<Location> corners = session.getCorners();
            double eyeY = player.getEyeLocation().getY();

            // Draw corner pillars (orange)
            for (Location corner : corners) {
                if (!sameWorld(corner, player)) continue;
                List<double[]> pillar = buildPillarPoints(
                        corner.getBlockX() + 0.5,
                        corner.getY(),
                        corner.getBlockZ() + 0.5,
                        PILLAR_HEIGHT);
                drawParticles(player, pillar, 1.0f, 0.5f, 0.0f);
            }

            // Draw edges between consecutive corners (yellow), closing if >= 3
            int n = corners.size();
            for (int i = 0; i < n; i++) {
                Location a = corners.get(i);
                // Only close the polygon visually when we have a valid polygon
                if (i == n - 1 && n < 3) continue;
                Location b = corners.get((i + 1) % n);

                if (!sameWorld(a, player) || !sameWorld(b, player)) continue;
                List<double[]> edgePts = interpolateEdge(
                        a.getBlockX() + 0.5, eyeY, a.getBlockZ() + 0.5,
                        b.getBlockX() + 0.5, eyeY, b.getBlockZ() + 0.5,
                        EDGE_SPACING_ACTIVE);
                drawParticles(player, edgePts, 1.0f, 1.0f, 0.0f);
            }
        }

    }

    /**
     * Sends {@link Effect#COLOURED_DUST} particles at each point to the
     * given viewer.
     *
     * In Spigot 1.8, COLOURED_DUST colour is encoded as:
     * <ul>
     *   <li>offsetX = red   (0.0–1.0)</li>
     *   <li>offsetY = green (0.0–1.0)</li>
     *   <li>offsetZ = blue  (0.0–1.0)</li>
     *   <li>amount  = 0     (required for the colour to apply)</li>
     *   <li>speed   = particle size</li>
     * </ul>
     */
    private void drawParticles(Player viewer, List<double[]> points,
                                float r, float g, float b) {
        for (double[] pt : points) {
            Location loc = new Location(viewer.getWorld(), pt[0], pt[1], pt[2]);
            try {
                viewer.spigot().playEffect(loc,
                        Effect.COLOURED_DUST,
                        0, 0,
                        r, g, b,
                        1.0f,
                        0,
                        48);
            } catch (Exception ignored) {
                // Fallback for environments where COLOURED_DUST is unavailable.
                viewer.getWorld().playEffect(loc, Effect.SMOKE, 0, 32);
            }
        }
    }

    // =========================================================================
    // Geometry helpers
    // =========================================================================

    /** Evenly-spaced interpolation between two 3-D points. */
    private static List<double[]> interpolateEdge(
            double ax, double ay, double az,
            double bx, double by, double bz,
            double spacing) {
        List<double[]> out = new ArrayList<>();
        double dx  = bx - ax;
        double dy  = by - ay;
        double dz  = bz - az;
        double len = Math.sqrt(dx * dx + dz * dz); // horizontal length drives step count
        if (len == 0) return out;
        double ux = dx / len, uy = dy / len, uz = dz / len;
        for (double t = 0; t <= len; t += spacing) {
            out.add(new double[]{ ax + ux * t, ay + uy * t, az + uz * t });
        }
        return out;
    }

    /** Vertical pillar points at (cx, cz) from baseY up by height in 0.5 steps. */
    private static List<double[]> buildPillarPoints(double cx, double baseY, double cz,
                                                     double height) {
        List<double[]> out = new ArrayList<>();
        for (double dy = 0; dy <= height; dy += 0.5) {
            out.add(new double[]{ cx, baseY + dy, cz });
        }
        return out;
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
                plugin.getLogger().warning(
                        "Zone '" + name + "': invalid corner '" + raw + "' — skipping zone.");
                return null;
            }
            try {
                int x = Integer.parseInt(parts[0].trim());
                int z = Integer.parseInt(parts[1].trim());
                corners.add(new int[]{ x, z });
            } catch (NumberFormatException e) {
                plugin.getLogger().warning(
                        "Zone '" + name + "': non-integer corner '" + raw + "' — skipping zone.");
                return null;
            }
        }

        return new Zone(name, world, minY, maxY, corners);
    }

    private void serializeZone(Zone zone) {
        String path = "zones." + zone.getName();
        plugin.getConfig().set(path + ".world", zone.getWorldName());
        plugin.getConfig().set(path + ".minY",  zone.getMinY());
        plugin.getConfig().set(path + ".maxY",  zone.getMaxY());

        List<String> cornerStrings = new ArrayList<>();
        for (int[] c : zone.getCorners()) {
            cornerStrings.add(c[0] + "," + c[1]);
        }
        plugin.getConfig().set(path + ".corners", cornerStrings);
    }
}

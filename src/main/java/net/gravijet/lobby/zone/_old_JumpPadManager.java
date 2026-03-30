package net.gravijet.lobby.zone;

import net.gravijet.lobby.Main;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Manages all jump pads in the lobby.
 * Config format:
 * <pre>
 * jump-pads:
 *   launch_pad:
 *     world: "world"
 *     minY: 0
 *     maxY: 256
 *     corners:
 *       - "120,45"
 *       - "120,80"
 *       - "155,80"
 *     strength: 1.5
 *     directionX: 0.0
 *     directionY: 1.0
 *     directionZ: 0.0
 *     cooldown: 1000   (optional, default 1000 ms)
 * </pre>
 */
public final class JumpPadManager {

    private final Main plugin;
    private final Map<String, JumpPad> jumpPads = new LinkedHashMap<>();
    private final Map<UUID, Map<String, Long>> playerCooldowns = new HashMap<>();

    public JumpPadManager(Main plugin) {
        this.plugin = plugin;
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    public void loadJumpPads() {
        jumpPads.clear();
        ConfigurationSection root = plugin.getConfig().getConfigurationSection("jump-pads");
        if (root == null) {
            plugin.getLogger().info("No jump-pads section in config — starting empty.");
            return;
        }
        for (String name : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(name);
            if (sec == null) continue;
            JumpPad pad = deserializeJumpPad(name, sec);
            if (pad != null) jumpPads.put(name.toLowerCase(), pad);
        }
        plugin.getLogger().info("Loaded " + jumpPads.size() + " jump pad(s).");
    }

    public void saveAll() {
        plugin.getConfig().set("jump-pads", null);
        for (JumpPad pad : jumpPads.values()) serializeJumpPad(pad);
        plugin.saveConfig();
    }

    // =========================================================================
    // CRUD operations
    // =========================================================================

    /**
     * Creates a jump pad from a zone selection session.
     *
     * @param name      unique name for the pad
     * @param session   the player's current corner selection
     * @param minY      minimum Y bound (inclusive)
     * @param maxY      maximum Y bound (inclusive)
     * @param strength  launch strength multiplier
     * @param dirX      direction vector X component (will be normalised)
     * @param dirY      direction vector Y component (will be normalised)
     * @param dirZ      direction vector Z component (will be normalised)
     * @param cooldown  cooldown in milliseconds (optional, default 1000)
     * @return the created JumpPad
     * @throws IllegalArgumentException if the session is invalid or the name is taken
     */
    public JumpPad saveJumpPad(String name, ZoneSelectionSession session,
                               int minY, int maxY,
                               double strength, double dirX, double dirY, double dirZ,
                               long cooldown) {
        if (name == null || name.trim().isEmpty())
            throw new IllegalArgumentException("Jump pad name must not be empty.");
        if (!session.isComplete())
            throw new IllegalArgumentException("Selection needs at least 3 corners.");
        if (minY > maxY)
            throw new IllegalArgumentException("minY must be <= maxY.");
        if (strength <= 0)
            throw new IllegalArgumentException("Strength must be positive.");
        if (jumpPads.containsKey(name.toLowerCase()))
            throw new IllegalArgumentException("A jump pad with that name already exists.");

        String worldName = session.getWorldName();
        if (worldName == null)
            throw new IllegalArgumentException("Cannot determine world from session corners.");

        for (Location loc : session.getCorners()) {
            if (loc.getWorld() == null || !loc.getWorld().getName().equals(worldName))
                throw new IllegalArgumentException(
                        "All corners must be in the same world (" + worldName + ").");
        }

        // Compute centroid for outward expansion (same as Zone)
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
        JumpPad pad = new JumpPad(zone, strength, dirX, dirY, dirZ, cooldown);
        jumpPads.put(name.toLowerCase(), pad);
        saveAll();
        return pad;
    }

    /**
     * Deletes a jump pad by name.
     *
     * @param name the jump pad's name (case‑insensitive)
     * @return true if a pad was removed
     */
    public boolean deleteJumpPad(String name) {
        if (jumpPads.remove(name.toLowerCase()) != null) {
            saveAll();
            return true;
        }
        return false;
    }

    public Collection<JumpPad> getAllJumpPads()  { return jumpPads.values(); }
    public JumpPad             getJumpPad(String name) { return jumpPads.get(name.toLowerCase()); }

    // =========================================================================
    // In‑game checks
    // =========================================================================

    /**
     * Returns the first jump pad that contains the given location,
     * or {@code null} if none.
     */
    public JumpPad getPadAt(Location loc) {
        for (JumpPad pad : jumpPads.values()) {
            if (pad.contains(loc)) return pad;
        }
        return null;
    }

    /**
     * Attempts to launch the player if they are standing on a jump pad
     * and the cooldown has expired.
     *
     * @param player the player to check
     * @return the jump pad that launched the player, or {@code null} if none
     */
    public JumpPad tryLaunch(Player player) {
        JumpPad pad = getPadAt(player.getLocation());
        if (pad == null) return null;

        UUID uuid = player.getUniqueId();
        String padName = pad.getName().toLowerCase();
        long now = System.currentTimeMillis();

        Map<String, Long> cooldownMap = playerCooldowns.computeIfAbsent(uuid, k -> new HashMap<>());
        Long lastLaunch = cooldownMap.get(padName);
        if (lastLaunch != null && now - lastLaunch < pad.getCooldownMillis()) {
            return null; // still on cooldown
        }

        pad.apply(player);
        cooldownMap.put(padName, now);
        return pad;
    }

    // =========================================================================
    // Cooldown management (optional, e.g., for clearing on quit)
    // =========================================================================

    public void clearCooldowns(UUID playerUUID) {
        playerCooldowns.remove(playerUUID);
    }

    // =========================================================================
    // Config serialisation
    // =========================================================================

    private JumpPad deserializeJumpPad(String name, ConfigurationSection sec) {
        String world = sec.getString("world");
        if (world == null || world.isEmpty()) {
            plugin.getLogger().warning("Jump pad '" + name + "' has no world — skipping.");
            return null;
        }

        int minY = sec.getInt("minY", 0);
        int maxY = sec.getInt("maxY", 256);

        List<String> rawCorners = sec.getStringList("corners");
        if (rawCorners.size() < 3) {
            plugin.getLogger().warning("Jump pad '" + name + "' has fewer than 3 corners — skipping.");
            return null;
        }

        List<int[]> corners = new ArrayList<>();
        for (String raw : rawCorners) {
            String[] parts = raw.split(",");
            if (parts.length != 2) {
                plugin.getLogger().warning("Jump pad '" + name + "': invalid corner '" + raw + "' — skipping.");
                return null;
            }
            try {
                corners.add(new int[]{ Integer.parseInt(parts[0].trim()),
                                       Integer.parseInt(parts[1].trim()) });
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("Jump pad '" + name + "': non-integer corner '" + raw + "' — skipping.");
                return null;
            }
        }

        double strength = sec.getDouble("strength", 1.5);
        double dirX = sec.getDouble("directionX", 0.0);
        double dirY = sec.getDouble("directionY", 1.0);
        double dirZ = sec.getDouble("directionZ", 0.0);
        long cooldown = sec.getLong("cooldown", 1000L);

        Zone zone = new Zone(name, world, minY, maxY, corners);
        return new JumpPad(zone, strength, dirX, dirY, dirZ, cooldown);
    }

    private void serializeJumpPad(JumpPad pad) {
        String path = "jump-pads." + pad.getName();
        plugin.getConfig().set(path + ".world", pad.getWorldName());
        plugin.getConfig().set(path + ".minY",  pad.getMinY());
        plugin.getConfig().set(path + ".maxY",  pad.getMaxY());

        List<String> cs = new ArrayList<>();
        for (int[] c : pad.getCorners()) cs.add(c[0] + "," + c[1]);
        plugin.getConfig().set(path + ".corners", cs);

        plugin.getConfig().set(path + ".strength", pad.getStrength());
        plugin.getConfig().set(path + ".directionX", pad.getDirectionX());
        plugin.getConfig().set(path + ".directionY", pad.getDirectionY());
        plugin.getConfig().set(path + ".directionZ", pad.getDirectionZ());
        plugin.getConfig().set(path + ".cooldown", pad.getCooldownMillis());
    }
}
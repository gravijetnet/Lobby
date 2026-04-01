package net.gravijet.lobby.jumppad;

import net.gravijet.lobby.Main;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JumppadManager {

    private final Main                plugin;
    private final Map<String, Jumppad> jumppads   = new LinkedHashMap<>();
    private final Map<String, String>  blockIndex = new HashMap<>();

    // Minecraft in-air physics constants
    // Horizontal velocity is multiplied by 0.91 every tick.
    // Vertical: vy = (vy - 0.08) * 0.98 every tick.
    private static final double H_DRAG = 0.91;
    private static final double V_DRAG = 0.98;
    private static final double GRAVITY = 0.08;

    public JumppadManager(Main plugin) {
        this.plugin = plugin;
    }

    // ── Block / jumppad management ────────────────────────────────────────────

    private String blockKey(Location loc) {
        return loc.getWorld().getName() + ":"
                + loc.getBlockX() + ":"
                + loc.getBlockY() + ":"
                + loc.getBlockZ();
    }

    public boolean addBlock(String name, Location loc) {
        String key = blockKey(loc);
        if (blockIndex.containsKey(key)) return false;
        Jumppad pad = jumppads.computeIfAbsent(name.toLowerCase(), k -> new Jumppad(name, 0, 1.5, 0));
        pad.addBlockKey(key);
        blockIndex.put(key, name.toLowerCase());
        saveJumppads();
        return true;
    }

    public String removeBlock(Location loc) {
        String key    = blockKey(loc);
        String padKey = blockIndex.remove(key);
        if (padKey == null) return null;
        Jumppad pad = jumppads.get(padKey);
        if (pad != null) {
            pad.removeBlockKey(key);
            if (pad.isEmpty()) jumppads.remove(padKey);
        }
        saveJumppads();
        return padKey;
    }

    public boolean deleteJumppad(String name) {
        Jumppad pad = jumppads.remove(name.toLowerCase());
        if (pad == null) return false;
        pad.getBlockKeys().forEach(blockIndex::remove);
        saveJumppads();
        return true;
    }

    public boolean setVelocity(String name, double x, double y, double z) {
        Jumppad pad = jumppads.get(name.toLowerCase());
        if (pad == null) return false;
        pad.setVelocity(x, y, z);
        saveJumppads();
        return true;
    }

    public boolean setStrength(String name, double strength) {
        Jumppad pad = jumppads.get(name.toLowerCase());
        if (pad == null) return false;
        pad.setStrength(strength);
        saveJumppads();
        return true;
    }

    public boolean setTarget(String name, Location loc) {
        Jumppad pad = jumppads.get(name.toLowerCase());
        if (pad == null) return false;
        if (loc.getWorld() == null) return false;
        pad.setTarget(loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ());
        saveJumppads();
        return true;
    }

    public boolean clearTarget(String name) {
        Jumppad pad = jumppads.get(name.toLowerCase());
        if (pad == null) return false;
        pad.clearTarget();
        saveJumppads();
        return true;
    }

    public Jumppad getJumppadAt(Location loc) {
        String padKey = blockIndex.get(blockKey(loc));
        return padKey == null ? null : jumppads.get(padKey);
    }

    public Jumppad getJumppad(String name) {
        return jumppads.get(name.toLowerCase());
    }

    public Collection<Jumppad> getAll() {
        return jumppads.values();
    }

    // ── Physics helpers ───────────────────────────────────────────────────────

    /**
     * Pre-calculates the full parabolic trajectory from {@code start} using
     * Minecraft in-air physics (horizontal drag 0.91/tick, gravity 0.08/tick,
     * vertical drag 0.98/tick).  Returns one Location per tick.
     *
     * The trajectory ends when the player has returned to (or below) their
     * starting Y level after the initial ascent, or after {@code maxTicks}.
     */
    public static List<Location> calculateTrajectory(Location start,
                                                     double vx, double vy, double vz,
                                                     int maxTicks) {
        World  world = start.getWorld();
        float  yaw   = start.getYaw();
        float  pitch = start.getPitch();

        List<Location> path = new ArrayList<>();
        double x = start.getX(), y = start.getY(), z = start.getZ();
        double cvx = vx, cvy = vy, cvz = vz;
        boolean peaked = false;

        for (int t = 0; t < maxTicks; t++) {
            x += cvx;
            y += cvy;
            z += cvz;
            cvx *= H_DRAG;
            cvz *= H_DRAG;
            double newVy = (cvy - GRAVITY) * V_DRAG;
            if (cvy >= 0 && newVy < 0) peaked = true;
            cvy = newVy;

            path.add(new Location(world, x, y, z, yaw, pitch));

            // Stop once we descend back to start level (after the peak)
            if (peaked && y <= start.getY()) break;
            if (y < -64) break;
        }
        return path;
    }

    /**
     * Calculates the initial velocity needed to reach (targetX, targetY, targetZ)
     * from the player's current location, using {@code velY} as the vertical component.
     *
     * Uses correct Minecraft air physics:
     *   horizontal: v *= 0.91 per tick  → sum = vx0 * (1 - 0.91^n) / 0.09
     *   vertical:   vy = (vy - 0.08) * 0.98 per tick
     */
    public static Vector calculateVelocityToTarget(Location from, double velY,
                                                   double targetX, double targetY, double targetZ) {
        double targetDy = targetY - from.getY();
        double vy  = velY;
        double y   = 0.0;
        int    ticks = 1;

        // Simulate vertical arc to find the tick when we reach the target height on the way down
        for (int t = 1; t <= 300; t++) {
            y  += vy;
            vy  = (vy - GRAVITY) * V_DRAG;
            ticks = t;
            // Stop once we've peaked and come back down to target height
            if (vy < 0 && y <= targetDy) break;
        }

        // Horizontal: x = vx0 * (1 - H_DRAG^n) / (1 - H_DRAG) = vx0 * (1 - 0.91^n) / 0.09
        double factor = (1.0 - Math.pow(H_DRAG, ticks)) / (1.0 - H_DRAG);
        if (factor < 0.01) factor = 0.01;

        double dx = targetX - from.getX();
        double dz = targetZ - from.getZ();

        return new Vector(dx / factor, velY, dz / factor);
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    public void loadJumppads() {
        jumppads.clear();
        blockIndex.clear();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("jumppads");
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            ConfigurationSection entry = sec.getConfigurationSection(name);
            if (entry == null) continue;
            double vx = entry.getDouble("vel-x", 0);
            double vy = entry.getDouble("vel-y", 1.5);
            double vz = entry.getDouble("vel-z", 0);
            Jumppad pad = new Jumppad(name, vx, vy, vz);
            for (String key : entry.getStringList("blocks")) {
                pad.addBlockKey(key);
                blockIndex.put(key, name.toLowerCase());
            }
            if (entry.contains("target.world")) {
                String tw = entry.getString("target.world");
                if (tw != null && !tw.isEmpty()) {
                    pad.setTarget(tw,
                            entry.getDouble("target.x"),
                            entry.getDouble("target.y"),
                            entry.getDouble("target.z"));
                }
            }
            jumppads.put(name.toLowerCase(), pad);
        }
    }

    public void saveJumppads() {
        plugin.getConfig().set("jumppads", null);
        for (Jumppad pad : jumppads.values()) {
            String path = "jumppads." + pad.getName();
            plugin.getConfig().set(path + ".vel-x", pad.getVelX());
            plugin.getConfig().set(path + ".vel-y", pad.getVelY());
            plugin.getConfig().set(path + ".vel-z", pad.getVelZ());
            plugin.getConfig().set(path + ".blocks", new ArrayList<>(pad.getBlockKeys()));
            if (pad.hasTarget()) {
                plugin.getConfig().set(path + ".target.world", pad.getTargetWorld());
                plugin.getConfig().set(path + ".target.x",     pad.getTargetX());
                plugin.getConfig().set(path + ".target.y",     pad.getTargetY());
                plugin.getConfig().set(path + ".target.z",     pad.getTargetZ());
            }
        }
        plugin.saveConfig();
    }
}

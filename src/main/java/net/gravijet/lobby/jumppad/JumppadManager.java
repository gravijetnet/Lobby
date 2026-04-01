package net.gravijet.lobby.jumppad;

import net.gravijet.lobby.Main;
import org.bukkit.Location;
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

    public JumppadManager(Main plugin) {
        this.plugin = plugin;
    }

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

    /**
     * Calculates the launch velocity required to reach the target coordinates from
     * the player's current position, using Minecraft physics simulation.
     *
     * Uses the pad's velY as the vertical launch speed. The horizontal components are
     * derived by computing the time of flight to the target's Y level and back-calculating
     * the required X/Z velocities accounting for Minecraft's 0.98 drag factor.
     */
    public static Vector calculateVelocityToTarget(Location from, double velY,
                                                   double targetX, double targetY, double targetZ) {
        double targetDy = targetY - from.getY();
        double vy = velY;
        double y  = 0.0;
        int ticks = 1;

        // Simulate vertical arc; stop when descending and at or past target Y
        for (int t = 1; t <= 200; t++) {
            y  += vy;
            vy  = (vy - 0.08) * 0.98;
            ticks = t;
            if (vy < 0 && y <= targetDy) break;
        }

        // Horizontal displacement sum with drag: x = vx0 * (1 - 0.98^n) / 0.02
        // => vx0 = dx * 0.02 / (1 - 0.98^n)
        double drag   = Math.pow(0.98, ticks);
        double factor = (1.0 - drag) / 0.02;
        if (factor < 0.01) factor = 0.01; // guard against near-zero

        double dx = targetX - from.getX();
        double dz = targetZ - from.getZ();

        return new Vector(dx / factor, velY, dz / factor);
    }

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
            // Load optional target location
            if (entry.contains("target.world")) {
                String tw = entry.getString("target.world");
                if (tw != null && !tw.isEmpty()) {
                    double tx = entry.getDouble("target.x");
                    double ty = entry.getDouble("target.y");
                    double tz = entry.getDouble("target.z");
                    pad.setTarget(tw, tx, ty, tz);
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

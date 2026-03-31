package net.gravijet.lobby.jumppad;

import net.gravijet.lobby.Main;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;

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
        }
        plugin.saveConfig();
    }
}

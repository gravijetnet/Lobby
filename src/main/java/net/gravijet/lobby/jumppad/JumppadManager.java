package net.gravijet.lobby.jumppad;

import net.gravijet.lobby.Main;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class JumppadManager {

    private final Main plugin;
    private final Map<String, Jumppad> jumppads = new LinkedHashMap<>();
    private final Map<String, String> blockIndex = new HashMap<>();

    public JumppadManager(Main plugin) {
        this.plugin = plugin;
    }

    private String blockKey(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    public boolean addBlock(String name, Location loc) {
        String key = blockKey(loc);
        if (blockIndex.containsKey(key)) return false;
        Jumppad pad = jumppads.computeIfAbsent(name.toLowerCase(), k -> new Jumppad(name, 1.2, 1.0));
        pad.addBlockKey(key);
        blockIndex.put(key, name.toLowerCase());
        saveJumppads();
        return true;
    }

    public String removeBlock(Location loc) {
        String key = blockKey(loc);
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

    public boolean setStrength(String name, double strength) {
        Jumppad pad = jumppads.get(name.toLowerCase());
        if (pad == null) return false;
        pad.setBaseStrength(strength);
        saveJumppads();
        return true;
    }

    public boolean setHeightMultiplier(String name, double height) {
        Jumppad pad = jumppads.get(name.toLowerCase());
        if (pad == null) return false;
        pad.setHeightMultiplier(height);
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

    public static Vector calculateLaunchVector(double baseStrength, double heightMultiplier) {
        // East is negative X direction.
        double forwardX = -1.0;
        double forwardZ = 0.0;

        double horizontalStrength = baseStrength * 0.8;
        double verticalStrength = baseStrength * heightMultiplier;

        return new Vector(forwardX * horizontalStrength, verticalStrength, forwardZ * horizontalStrength);
    }

    public void loadJumppads() {
        jumppads.clear();
        blockIndex.clear();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("jumppads");
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            ConfigurationSection entry = sec.getConfigurationSection(name);
            if (entry == null) continue;
            double strength = entry.getDouble("strength", 1.2);
            double height = entry.getDouble("height", 1.0);
            Jumppad pad = new Jumppad(name, strength, height);
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
            plugin.getConfig().set(path + ".strength", pad.getBaseStrength());
            plugin.getConfig().set(path + ".height", pad.getHeightMultiplier());
            plugin.getConfig().set(path + ".blocks", new ArrayList<>(pad.getBlockKeys()));
        }
        plugin.saveConfig();
    }
}

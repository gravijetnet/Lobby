package net.gravijet.lobby.jumppad;

import net.gravijet.lobby.Main;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public class JumppadManager {

    private final Main plugin;
    private final Map<String, Location> jumppads = new HashMap<>();

    public JumppadManager(Main plugin) {
        this.plugin = plugin;
    }

    private String key(Location loc) {
        return loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    public void loadJumppads() {
        jumppads.clear();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("jumppads");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            ConfigurationSection entry = sec.getConfigurationSection(k);
            if (entry == null) continue;
            World world = Bukkit.getWorld(entry.getString("world", ""));
            if (world == null) continue;
            int x = entry.getInt("x");
            int y = entry.getInt("y");
            int z = entry.getInt("z");
            Location loc = new Location(world, x, y, z);
            jumppads.put(key(loc), loc);
        }
    }

    public void saveJumppads() {
        plugin.getConfig().set("jumppads", null);
        int i = 0;
        for (Location loc : jumppads.values()) {
            String path = "jumppads." + i;
            plugin.getConfig().set(path + ".world", loc.getWorld().getName());
            plugin.getConfig().set(path + ".x", loc.getBlockX());
            plugin.getConfig().set(path + ".y", loc.getBlockY());
            plugin.getConfig().set(path + ".z", loc.getBlockZ());
            i++;
        }
        plugin.saveConfig();
    }

    public boolean addJumppad(Location loc) {
        String k = key(loc);
        if (jumppads.containsKey(k)) return false;
        jumppads.put(k, new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
        saveJumppads();
        return true;
    }

    public boolean removeJumppad(Location loc) {
        if (jumppads.remove(key(loc)) == null) return false;
        saveJumppads();
        return true;
    }

    public boolean isJumppad(Location loc) {
        return jumppads.containsKey(key(loc));
    }

    public Collection<Location> getJumppads() {
        return jumppads.values();
    }
}

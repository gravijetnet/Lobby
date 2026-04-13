package net.gravijet.lobby;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public class LobbyBlockManager {
    private final Main plugin;
    private File lobbyBlocksFile;
    private FileConfiguration lobbyBlocksConfig;
    private final Set<String> lobbyBlockKeys = new HashSet<>();

    public LobbyBlockManager(Main plugin) {
        this.plugin = plugin;
        loadLobbyBlocksConfig();
        removeAllLobbyBlocks();
    }

    public void loadLobbyBlocksConfig() {
        if (lobbyBlocksFile == null) {
            lobbyBlocksFile = new File(plugin.getDataFolder(), "lobbyblocks.yml");
        }
        
        if (!lobbyBlocksFile.exists()) {
            plugin.saveResource("lobbyblocks.yml", false);
        }
        
        lobbyBlocksConfig = YamlConfiguration.loadConfiguration(lobbyBlocksFile);
        
        // Load existing block keys
        lobbyBlockKeys.clear();
        List<String> blocks = lobbyBlocksConfig.getStringList("blocks");
        lobbyBlockKeys.addAll(blocks);
    }

    public void saveLobbyBlocksConfig() {
        if (lobbyBlocksConfig != null && lobbyBlocksFile != null) {
            try {
                lobbyBlocksConfig.save(lobbyBlocksFile);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save lobbyblocks.yml", e);
            }
        }
    }

    public void addLobbyBlock(Location location) {
        String key = location.getWorld().getName() + ":" + 
                     location.getBlockX() + ":" + 
                     location.getBlockY() + ":" + 
                     location.getBlockZ();
        
        lobbyBlockKeys.add(key);
        updateConfig();
    }

    public void removeLobbyBlock(Location location) {
        String key = location.getWorld().getName() + ":" + 
                     location.getBlockX() + ":" + 
                     location.getBlockY() + ":" + 
                     location.getBlockZ();
        
        lobbyBlockKeys.remove(key);
        updateConfig();
    }

    public boolean isLobbyBlock(Location location) {
        String key = location.getWorld().getName() + ":" + 
                     location.getBlockX() + ":" + 
                     location.getBlockY() + ":" + 
                     location.getBlockZ();
        return lobbyBlockKeys.contains(key);
    }

    private void updateConfig() {
        lobbyBlocksConfig.set("blocks", new ArrayList<>(lobbyBlockKeys));
        saveLobbyBlocksConfig();
    }

    public void removeAllLobbyBlocks() {
        plugin.getLogger().info("Removing all lobby blocks...");
        int removed = 0;
        
        for (String key : new HashSet<>(lobbyBlockKeys)) {
            String[] parts = key.split(":");
            if (parts.length != 4) continue;
            
            String worldName = parts[0];
            try {
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);
                
                World world = Bukkit.getWorld(worldName);
                if (world != null) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.DIAMOND_BLOCK || block.getType() == Material.SANDSTONE) {
                        block.setType(Material.AIR);
                        removed++;
                    }
                }
            } catch (NumberFormatException e) {
                // Invalid coordinates, skip
            }
        }
        
        lobbyBlockKeys.clear();
        updateConfig();
        plugin.getLogger().info("Removed " + removed + " lobby blocks.");
    }

    public void cleanupExpiredBlocks() {
        // Remove any lobby blocks that are no longer SANDSTONE or REDSTONE_BLOCK
        Set<String> toRemove = new HashSet<>();
        
        for (String key : lobbyBlockKeys) {
            String[] parts = key.split(":");
            if (parts.length != 4) continue;
            
            String worldName = parts[0];
            try {
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);
                
                World world = Bukkit.getWorld(worldName);
                if (world != null) {
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (type != Material.DIAMOND_BLOCK && type != Material.SANDSTONE) {
                        toRemove.add(key);
                    }
                } else {
                    // World not loaded, remove from list
                    toRemove.add(key);
                }
            } catch (NumberFormatException e) {
                toRemove.add(key);
            }
        }
        
        if (!toRemove.isEmpty()) {
            lobbyBlockKeys.removeAll(toRemove);
            updateConfig();
        }
    }

    public void reloadLobbyBlocksConfig() {
        loadLobbyBlocksConfig();
        plugin.getLogger().info("Lobby blocks configuration reloaded!");
    }
}
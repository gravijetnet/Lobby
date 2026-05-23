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
            if (plugin.getResource("lobbyblocks.yml") != null) {
                plugin.saveResource("lobbyblocks.yml", false);
            } else {
                try {
                    lobbyBlocksFile.getParentFile().mkdirs();
                    lobbyBlocksFile.createNewFile();
                } catch (IOException e) {
                    plugin.getLogger().log(Level.SEVERE, "Could not create lobbyblocks.yml", e);
                }
            }
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

    private static String toKey(Location location) {
        if (location.getWorld() == null) {
            throw new IllegalArgumentException("Location world is null");
        }
        // Escape colons in world name so the key always has exactly 4 parts
        String worldName = location.getWorld().getName().replace(":", "_");
        return worldName + ":" + location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ();
    }

    /**
     * Parses a stored key back into its components.
     * Returns an array of [worldNameEnd, x, y, z] as indices/values, or null if invalid.
     * Specifically returns a String[4]: [worldName, xStr, yStr, zStr].
     */
    private static String[] parseKey(String key) {
        int last = key.lastIndexOf(':');
        if (last < 0) return null;
        int mid = key.lastIndexOf(':', last - 1);
        if (mid < 0) return null;
        int first = key.lastIndexOf(':', mid - 1);
        if (first < 0) return null;
        return new String[]{
            key.substring(0, first),
            key.substring(first + 1, mid),
            key.substring(mid + 1, last),
            key.substring(last + 1)
        };
    }

    public void addLobbyBlock(Location location) {
        if (location.getWorld() == null) return;
        lobbyBlockKeys.add(toKey(location));
        scheduleSave();
    }

    public void removeLobbyBlock(Location location) {
        if (location.getWorld() == null) return;
        lobbyBlockKeys.remove(toKey(location));
        scheduleSave();
    }

    public boolean isLobbyBlock(Location location) {
        if (location.getWorld() == null) return false;
        return lobbyBlockKeys.contains(toKey(location));
    }

    private int pendingSaveTaskId = -1;

    private void scheduleSave() {
        // Debounce: only write to disk once per tick at most, avoiding per-block I/O spikes
        if (pendingSaveTaskId != -1) return;
        pendingSaveTaskId = Bukkit.getScheduler().runTask(plugin, () -> {
            pendingSaveTaskId = -1;
            lobbyBlocksConfig.set("blocks", new ArrayList<>(lobbyBlockKeys));
            saveLobbyBlocksConfig();
        }).getTaskId();
    }

    public void removeAllLobbyBlocks() {
        plugin.getLogger().info("Removing all lobby blocks...");
        int removed = 0;
        int skipped = 0;
        Set<String> toRetain = new HashSet<>();

        for (String key : new HashSet<>(lobbyBlockKeys)) {
            String[] parts = parseKey(key);
            if (parts == null) continue;
            try {
                String worldName = parts[0];
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);

                World world = Bukkit.getWorld(worldName);
                if (world != null) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.DIAMOND_BLOCK || block.getType() == Material.SANDSTONE
                            || block.getType() == Material.REDSTONE_BLOCK || block.getType() == Material.EMERALD_BLOCK) {
                        block.setType(Material.AIR);
                        removed++;
                    }
                } else {
                    // World not loaded — keep the key so these blocks can be cleaned up
                    // the next time the world loads rather than being silently orphaned.
                    toRetain.add(key);
                    skipped++;
                }
            } catch (NumberFormatException e) {
                // Invalid coordinates — discard
            }
        }

        lobbyBlockKeys.clear();
        lobbyBlockKeys.addAll(toRetain);
        lobbyBlocksConfig.set("blocks", new ArrayList<>(lobbyBlockKeys));
        saveLobbyBlocksConfig();
        plugin.getLogger().info("Removed " + removed + " lobby blocks."
                + (skipped > 0 ? " Skipped " + skipped + " in unloaded worlds." : ""));
    }

    public void cleanupExpiredBlocks() {
        Set<String> toRemove = new HashSet<>();

        for (String key : new HashSet<>(lobbyBlockKeys)) {
            String[] parts = parseKey(key);
            if (parts == null) { toRemove.add(key); continue; }
            try {
                String worldName = parts[0];
                int x = Integer.parseInt(parts[1]);
                int y = Integer.parseInt(parts[2]);
                int z = Integer.parseInt(parts[3]);

                World world = Bukkit.getWorld(worldName);
                if (world != null) {
                    Block block = world.getBlockAt(x, y, z);
                    Material type = block.getType();
                    if (type != Material.DIAMOND_BLOCK && type != Material.SANDSTONE
                            && type != Material.REDSTONE_BLOCK && type != Material.EMERALD_BLOCK) {
                        toRemove.add(key);
                    }
                } else {
                    toRemove.add(key);
                }
            } catch (NumberFormatException e) {
                toRemove.add(key);
            }
        }

        if (!toRemove.isEmpty()) {
            lobbyBlockKeys.removeAll(toRemove);
            scheduleSave();
        }
    }

    public void reloadLobbyBlocksConfig() {
        // Cancel any pending debounced save so it doesn't overwrite the freshly-loaded config.
        if (pendingSaveTaskId != -1) {
            Bukkit.getScheduler().cancelTask(pendingSaveTaskId);
            pendingSaveTaskId = -1;
        }
        loadLobbyBlocksConfig();
        plugin.getLogger().info("Lobby blocks configuration reloaded!");
    }

    /** Must be called once from Main.onEnable to periodically prune orphaned block keys. */
    public void startCleanupTask() {
        // Run every 5 minutes (6000 ticks)
        Bukkit.getScheduler().runTaskTimer(plugin, this::cleanupExpiredBlocks, 6000L, 6000L);
    }
}
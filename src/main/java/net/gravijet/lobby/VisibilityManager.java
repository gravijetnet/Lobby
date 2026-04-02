package net.gravijet.lobby;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.logging.Level;

public class VisibilityManager {
    private final Main plugin;
    private File visibilityFile;
    private FileConfiguration visibilityConfig;

    public VisibilityManager(Main plugin) {
        this.plugin = plugin;
        loadVisibilityConfig();
    }

    public void loadVisibilityConfig() {
        if (visibilityFile == null) {
            visibilityFile = new File(plugin.getDataFolder(), "visibility.yml");
        }
        
        if (!visibilityFile.exists()) {
            plugin.saveResource("visibility.yml", false);
        }
        
        visibilityConfig = YamlConfiguration.loadConfiguration(visibilityFile);
    }

    public void saveVisibilityConfig() {
        if (visibilityConfig != null && visibilityFile != null) {
            try {
                visibilityConfig.save(visibilityFile);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save visibility.yml", e);
            }
        }
    }

    public FileConfiguration getVisibilityConfig() {
        if (visibilityConfig == null) {
            loadVisibilityConfig();
        }
        return visibilityConfig;
    }

    public void reloadVisibilityConfig() {
        loadVisibilityConfig();
        plugin.getLogger().info("Visibility configuration reloaded!");
    }

    public String getPlayerVisibility(UUID playerId) {
        return getVisibilityConfig().getString("players." + playerId + ".visibility", "ALL");
    }

    public void setPlayerVisibility(UUID playerId, String visibility) {
        getVisibilityConfig().set("players." + playerId + ".visibility", visibility);
        saveVisibilityConfig();
    }

    public void savePlayerVisibility(Player player) {
        String visibility = plugin.getConfig().getString("players." + player.getUniqueId() + ".visibility", "ALL");
        setPlayerVisibility(player.getUniqueId(), visibility);
    }

    public void migrateFromMainConfig() {
        // Check if main config has visibility data and migrate it
        FileConfiguration mainConfig = plugin.getConfig();
        if (mainConfig.contains("players")) {
            for (String key : mainConfig.getConfigurationSection("players").getKeys(false)) {
                String visibility = mainConfig.getString("players." + key + ".visibility");
                if (visibility != null) {
                    try {
                        UUID playerId = UUID.fromString(key);
                        setPlayerVisibility(playerId, visibility);
                        // Remove from main config
                        mainConfig.set("players." + key + ".visibility", null);
                    } catch (IllegalArgumentException e) {
                        // Invalid UUID format, skip
                    }
                }
            }
            // Clean up empty player sections
            for (String key : mainConfig.getConfigurationSection("players").getKeys(false)) {
                if (mainConfig.getConfigurationSection("players." + key).getKeys(false).isEmpty()) {
                    mainConfig.set("players." + key, null);
                }
            }
            if (mainConfig.getConfigurationSection("players") != null && 
                mainConfig.getConfigurationSection("players").getKeys(false).isEmpty()) {
                mainConfig.set("players", null);
            }
            plugin.saveConfig();
        }
    }
}
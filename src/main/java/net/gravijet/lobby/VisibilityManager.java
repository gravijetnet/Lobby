package net.gravijet.lobby;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

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
            // saveResource throws if the file is not bundled in the JAR; create a blank file instead
            if (plugin.getResource("visibility.yml") != null) {
                plugin.saveResource("visibility.yml", false);
            } else {
                try {
                    visibilityFile.getParentFile().mkdirs();
                    visibilityFile.createNewFile();
                } catch (IOException e) {
                    plugin.getLogger().warning("Could not create visibility.yml: " + e.getMessage());
                }
            }
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

    public void migrateFromMainConfig() {
        FileConfiguration mainConfig = plugin.getConfig();
        org.bukkit.configuration.ConfigurationSection playersSection =
                mainConfig.getConfigurationSection("players");
        if (playersSection == null) return;

        for (String key : playersSection.getKeys(false)) {
            String visibility = mainConfig.getString("players." + key + ".visibility");
            if (visibility != null) {
                try {
                    UUID playerId = UUID.fromString(key);
                    setPlayerVisibility(playerId, visibility);
                    mainConfig.set("players." + key + ".visibility", null);
                } catch (IllegalArgumentException e) {
                    // Invalid UUID format, skip
                }
            }
        }

        // Re-fetch after possible mutations
        playersSection = mainConfig.getConfigurationSection("players");
        if (playersSection != null) {
            for (String key : playersSection.getKeys(false)) {
                org.bukkit.configuration.ConfigurationSection sub =
                        mainConfig.getConfigurationSection("players." + key);
                if (sub != null && sub.getKeys(false).isEmpty()) {
                    mainConfig.set("players." + key, null);
                }
            }
            playersSection = mainConfig.getConfigurationSection("players");
            if (playersSection != null && playersSection.getKeys(false).isEmpty()) {
                mainConfig.set("players", null);
            }
        }
        plugin.saveConfig();
    }
}
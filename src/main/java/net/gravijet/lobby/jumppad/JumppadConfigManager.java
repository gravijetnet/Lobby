package net.gravijet.lobby.jumppad;

import net.gravijet.lobby.Main;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.logging.Level;

public class JumppadConfigManager {
    private final Main plugin;
    private File jumppadsFile;
    private FileConfiguration jumppadsConfig;

    public JumppadConfigManager(Main plugin) {
        this.plugin = plugin;
        loadJumppadsConfig();
    }

    public void loadJumppadsConfig() {
        if (jumppadsFile == null) {
            jumppadsFile = new File(plugin.getDataFolder(), "jumppads.yml");
        }
        
        if (!jumppadsFile.exists()) {
            plugin.saveResource("jumppads.yml", false);
        }
        
        jumppadsConfig = YamlConfiguration.loadConfiguration(jumppadsFile);
    }

    public void saveJumppadsConfig() {
        if (jumppadsConfig != null && jumppadsFile != null) {
            try {
                jumppadsConfig.save(jumppadsFile);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save jumppads.yml", e);
            }
        }
    }

    public void reloadJumppadsConfig() {
        loadJumppadsConfig();
        plugin.getLogger().info("Jumppads configuration reloaded!");
    }

    public FileConfiguration getJumppadsConfig() {
        if (jumppadsConfig == null) {
            loadJumppadsConfig();
        }
        return jumppadsConfig;
    }

    public void migrateFromMainConfig() {
        // Check if main config has jumppad data and migrate it
        FileConfiguration mainConfig = plugin.getConfig();
        if (mainConfig.contains("jumppads")) {
            ConfigurationSection jumppadsSection = mainConfig.getConfigurationSection("jumppads");
            if (jumppadsSection != null) {
                for (String jumppadName : jumppadsSection.getKeys(false)) {
                    ConfigurationSection jumppadSection = jumppadsSection.getConfigurationSection(jumppadName);
                    if (jumppadSection != null) {
                        // Copy all jumppad data to new config
                        for (String key : jumppadSection.getKeys(true)) {
                            Object value = jumppadSection.get(key);
                            getJumppadsConfig().set("jumppads." + jumppadName + "." + key, value);
                        }
                    }
                }
                saveJumppadsConfig();
                // Remove from main config
                mainConfig.set("jumppads", null);
                plugin.saveConfig();
                plugin.getLogger().info("Migrated jumppads from main config to jumppads.yml");
            }
        }
    }

    public void saveJumppadsToConfig(JumppadManager jumppadManager) {
        // Clear existing jumppads in config
        getJumppadsConfig().set("jumppads", null);
        
        if (jumppadManager != null) {
            for (Jumppad jumppad : jumppadManager.getAll()) {
                serializeJumppad(jumppad);
            }
            saveJumppadsConfig();
        }
    }

    private void serializeJumppad(Jumppad jumppad) {
        String path = "jumppads." + jumppad.getName();
        getJumppadsConfig().set(path + ".strength", jumppad.getBaseStrength());
        getJumppadsConfig().set(path + ".height", jumppad.getHeightMultiplier());
        getJumppadsConfig().set(path + ".blocks", new ArrayList<>(jumppad.getBlockKeys()));
    }

    public void loadJumppads(JumppadManager jumppadManager) {
        ConfigurationSection sec = getJumppadsConfig().getConfigurationSection("jumppads");
        if (sec == null) return;
        
        jumppadManager.getJumppadsMap().clear();
        jumppadManager.getBlockIndex().clear();
        
        for (String name : sec.getKeys(false)) {
            ConfigurationSection entry = sec.getConfigurationSection(name);
            if (entry == null) continue;
            
            double strength = entry.getDouble("strength", 1.2);
            double height = entry.getDouble("height", 1.0);
            Jumppad pad = new Jumppad(name, strength, height);
            
            for (String key : entry.getStringList("blocks")) {
                pad.addBlockKey(key);
                jumppadManager.getBlockIndex().put(key, name.toLowerCase());
            }
            
            jumppadManager.getJumppadsMap().put(name.toLowerCase(), pad);
        }
    }
}
package net.gravijet.lobby.zone;

import net.gravijet.lobby.Main;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

public class ZoneConfigManager {
    private final Main plugin;
    private File zonesFile;
    private FileConfiguration zonesConfig;

    public ZoneConfigManager(Main plugin) {
        this.plugin = plugin;
        loadZonesConfig();
    }

    public void loadZonesConfig() {
        if (zonesFile == null) {
            zonesFile = new File(plugin.getDataFolder(), "zones.yml");
        }

        if (!zonesFile.exists()) {
            if (plugin.getResource("zones.yml") != null) {
                plugin.saveResource("zones.yml", false);
            } else {
                try {
                    zonesFile.getParentFile().mkdirs();
                    zonesFile.createNewFile();
                } catch (IOException e) {
                    plugin.getLogger().log(Level.SEVERE, "Could not create zones.yml", e);
                }
            }
        }

        zonesConfig = YamlConfiguration.loadConfiguration(zonesFile);
    }

    public void saveZonesConfig() {
        if (zonesConfig != null && zonesFile != null) {
            try {
                zonesConfig.save(zonesFile);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save zones.yml", e);
            }
        }
    }

    public void reloadZonesConfig() {
        loadZonesConfig();
        plugin.getLogger().info("Zones configuration reloaded!");
    }

    public FileConfiguration getZonesConfig() {
        if (zonesConfig == null) {
            loadZonesConfig();
        }
        return zonesConfig;
    }

    public void migrateFromMainConfig() {
        // Check if main config has zone data and migrate it
        FileConfiguration mainConfig = plugin.getConfig();
        if (mainConfig.contains("zones")) {
            ConfigurationSection zonesSection = mainConfig.getConfigurationSection("zones");
            if (zonesSection != null) {
                for (String zoneName : zonesSection.getKeys(false)) {
                    ConfigurationSection zoneSection = zonesSection.getConfigurationSection(zoneName);
                    if (zoneSection != null) {
                        // Copy all zone data to new config
                        for (String key : zoneSection.getKeys(true)) {
                            Object value = zoneSection.get(key);
                            getZonesConfig().set("zones." + zoneName + "." + key, value);
                        }
                    }
                }
                saveZonesConfig();
                // Remove from main config
                mainConfig.set("zones", null);
                plugin.saveConfig();
                plugin.getLogger().info("Migrated zones from main config to zones.yml");
            }
        }
    }

    public void saveZonesToConfig() {
        ZoneManager zoneManager = plugin.getZoneManager();
        if (zoneManager == null) return;

        // Build a fresh in-memory config so partial writes don't leave a corrupt file.
        FileConfiguration fresh = new YamlConfiguration();
        FileConfiguration previous = zonesConfig;
        // Assign fresh first so serializeZone calls getZonesConfig() on the new instance.
        zonesConfig = fresh;
        try {
            for (Zone zone : zoneManager.getAllZones()) {
                serializeZone(zone);
            }
            fresh.save(zonesFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save zones.yml", e);
            zonesConfig = previous; // restore on failure so in-memory state stays valid
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Unexpected error serializing zones", e);
            zonesConfig = previous;
        }
    }

    private void serializeZone(Zone zone) {
        // Use lowercase key so in-memory and on-disk names are consistent
        String path = "zones." + zone.getName().toLowerCase();
        getZonesConfig().set(path + ".world", zone.getWorldName());
        getZonesConfig().set(path + ".minY", zone.getMinY());
        getZonesConfig().set(path + ".maxY", zone.getMaxY());

        List<String> cs = new ArrayList<>();
        for (int[] c : zone.getCorners()) cs.add(c[0] + "," + c[1]);
        getZonesConfig().set(path + ".corners", cs);

        getZonesConfig().set(path + ".required-permission", zone.getRequiredPermission());
        // Convert translated § codes back to & codes so the YAML file stays human-editable
        // and so that loading via translateAlternateColorCodes on the next reload is correct.
        String rawMessage = zone.getDenyMessage()
                .replace("\n", "\\n")
                .replace('§', '&');
        getZonesConfig().set(path + ".deny-message", rawMessage);
        getZonesConfig().set(path + ".allow-block-placement", zone.isAllowBlockPlacement());
        getZonesConfig().set(path + ".allow-flight", zone.isAllowFlight());
    }

    public void loadZones(ZoneManager zoneManager) {
        ConfigurationSection root = getZonesConfig().getConfigurationSection("zones");
        if (root == null) return;
        
        for (String name : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(name);
            if (sec == null) continue;
            
            Zone zone = deserializeZone(name, sec);
            if (zone != null) {
                zoneManager.getZonesMap().put(name.toLowerCase(), zone);
            }
        }
    }

    private Zone deserializeZone(String name, ConfigurationSection sec) {
        String world = sec.getString("world");
        if (world == null || world.isEmpty()) {
            plugin.getLogger().warning("Zone '" + name + "' has no world — skipping.");
            return null;
        }

        int minY = sec.getInt("minY", 0);
        int maxY = sec.getInt("maxY", 256);

        List<String> rawCorners = sec.getStringList("corners");
        if (rawCorners.size() < 3) {
            plugin.getLogger().warning("Zone '" + name + "' has fewer than 3 corners — skipping.");
            return null;
        }

        List<int[]> corners = new ArrayList<>();
        for (String raw : rawCorners) {
            String[] parts = raw.split(",");
            if (parts.length != 2) {
                plugin.getLogger().warning("Zone '" + name + "': invalid corner '" + raw + "' — skipping.");
                return null;
            }
            try {
                corners.add(new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())});
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("Zone '" + name + "': non-integer corner '" + raw + "' — skipping.");
                return null;
            }
        }

        Zone zone = new Zone(name, world, minY, maxY, corners);

        String perm = sec.getString("required-permission");
        zone.setRequiredPermission(perm);

        String msg = sec.getString("deny-message", "");
        if (!msg.isEmpty()) {
            zone.setDenyMessage(ChatColor.translateAlternateColorCodes('&', msg.replace("\\n", "\n")));
        }

        zone.setAllowBlockPlacement(sec.getBoolean("allow-block-placement", false));
        zone.setAllowFlight(sec.getBoolean("allow-flight", true));
        return zone;
    }
}
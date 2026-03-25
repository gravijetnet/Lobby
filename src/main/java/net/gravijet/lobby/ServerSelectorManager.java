package net.gravijet.lobby;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class ServerSelectorManager {
    private final Main plugin;
    private FileConfiguration config;

    public ServerSelectorManager(Main plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        plugin.saveDefaultConfig();
        config = plugin.getConfig();
    }

    public void openServerSelector(Player player) {
        // Always use the default menu for all versions
        ConfigurationSection menuConfig = config.getConfigurationSection("server-selector.default");

        if (menuConfig == null) {
            player.sendMessage("§cServer selector is not configured.");
            return;
        }

        String title = ChatColor.translateAlternateColorCodes('&',
                menuConfig.getString("title", "Server Selector"));
        int size = menuConfig.getInt("size", 54);

        Inventory gui = Bukkit.createInventory(null, size, title);

    /*    // Fill all slots with gray glass panes
        ItemStack glassPane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta glassMeta = glassPane.getItemMeta();
        glassMeta.setDisplayName(" ");
        glassPane.setItemMeta(glassMeta);

        for (int i = 0; i < size; i++) {
            gui.setItem(i, glassPane);
        } */

        // Set configured items
        ConfigurationSection itemsSection = menuConfig.getConfigurationSection("items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                ConfigurationSection itemSection = itemsSection.getConfigurationSection(key);
                if (itemSection != null) {
                    int slot = itemSection.getInt("slot", 0);
                    if (slot >= 0 && slot < size) {
                        ItemStack menuItem = createMenuItem(itemSection);
                        if (menuItem != null) {
                            gui.setItem(slot, menuItem);
                        }
                    }
                }
            }
        }

        player.openInventory(gui);
        player.playSound(player.getLocation(), Sound.CHEST_OPEN, 1.0f, 1.0f);
    }

    private ItemStack createMenuItem(ConfigurationSection itemSection) {
        String materialName = itemSection.getString("material", "STONE");
        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Unknown material: " + materialName + " in server selector config. Using STONE.");
            material = Material.STONE;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta != null) {
            String displayName = itemSection.getString("name", "Item");
            if (displayName != null) {
                meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', displayName));
            }

            List<String> lore = new ArrayList<>();
            List<String> loreConfig = itemSection.getStringList("lore");
            for (String line : loreConfig) {
                lore.add(ChatColor.translateAlternateColorCodes('&', line));
            }
            meta.setLore(lore);

            // Hide all attributes and stats (HIDE_DYE does not exist in 1.8 — omitted)
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.addItemFlags(ItemFlag.HIDE_DESTROYS);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            meta.addItemFlags(ItemFlag.HIDE_PLACED_ON);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);

            item.setItemMeta(meta);
        }

        return item;
    }

    public void handleMenuClick(Player player, int slot) {
        ConfigurationSection menuConfig = config.getConfigurationSection("server-selector.default");

        if (menuConfig == null) return;

        ConfigurationSection itemsSection = menuConfig.getConfigurationSection("items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                ConfigurationSection itemSection = itemsSection.getConfigurationSection(key);
                if (itemSection != null && itemSection.getInt("slot", -1) == slot) {
                    executeActions(player, itemSection.getStringList("actions"));
                    return;
                }
            }
        }
    }

    private void executeActions(Player player, List<String> actions) {
        for (String action : actions) {
            if (action.startsWith("connect:")) {
                String server = action.substring(8);
                connectToServer(player, server);
            } else if (action.startsWith("command:")) {
                String command = action.substring(8).replace("%player%", player.getName());
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
            } else if (action.startsWith("playercommand:")) {
                String command = action.substring(14).replace("%player%", player.getName());
                player.performCommand(command);
            }
        }
    }

    private void connectToServer(Player player, String server) {
        try {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("Connect");
            out.writeUTF(server);
            player.sendPluginMessage(plugin, "BungeeCord", out.toByteArray());
        } catch (Exception e) {
            player.sendMessage("§cCould not connect to server: " + server);
        }
    }
}
package net.gravijet.lobby.selector;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import net.gravijet.lobby.Main;
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
        // Eagerly initialize so config is never null when openServerSelector/handleMenuClick are called.
        this.config = plugin.getConfig();
    }

    public void loadConfig() {
        plugin.saveDefaultConfig();
        config = plugin.getConfig();
    }

    public void openServerSelector(Player player) {
        ConfigurationSection menuConfig = config.getConfigurationSection("server-selector.default");

        if (menuConfig == null) {
            plugin.getMessages().send(player, "selector.not-configured");
            return;
        }

        String title = ChatColor.translateAlternateColorCodes('&',
                menuConfig.getString("title", "Server Selector"));
        int size = menuConfig.getInt("size", 54);
        // Inventory size must be a positive multiple of 9 and at most 54
        if (size <= 0 || size % 9 != 0 || size > 54) {
            plugin.getLogger().warning("Invalid server-selector size " + size + " — defaulting to 54.");
            size = 54;
        }

        Inventory gui = Bukkit.createInventory(null, size, title);

        ConfigurationSection itemsSection = menuConfig.getConfigurationSection("items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                ConfigurationSection itemSection = itemsSection.getConfigurationSection(key);
                if (itemSection != null) {
                    int slot = itemSection.getInt("slot", 0);
                    if (slot >= 0 && slot < size) {
                        ItemStack menuItem = createMenuItem(itemSection, player);
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

    private ItemStack createMenuItem(ConfigurationSection itemSection, Player player) {
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
                // Replace placeholders if PlaceholderAPI is available
                if (plugin.hasPlaceholderAPI()) {
                    line = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, line);
                }
                lore.add(ChatColor.translateAlternateColorCodes('&', line));
            }
            meta.setLore(lore);

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
        // For console commands, sanitize the name to prevent command injection via unusual characters.
        String safeName = player.getName().replaceAll("[^a-zA-Z0-9_.]", "");
        for (String action : actions) {
            if (action.startsWith("connect:")) {
                // Strip non-printable/control characters from the server name.
                String server = action.substring(8).replaceAll("[\\x00-\\x1F\\x7F]", "").trim();
                if (!server.isEmpty()) connectToServer(player, server);
            } else if (action.startsWith("command:")) {
                // Strip newlines and semicolons that could chain additional commands.
                String cmd = action.substring(8)
                        .replace("%player%", safeName)
                        .replaceAll("[;\n\r]", "");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } else if (action.startsWith("playercommand:")) {
                // Player runs the command as themselves; no injection risk from their own name.
                String cmd = action.substring(14)
                        .replace("%player%", player.getName())
                        .replaceAll("[;\n\r]", "");
                player.performCommand(cmd);
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
            plugin.getMessages().send(player, "selector.connect-failed", "server", server);
        }
    }
}
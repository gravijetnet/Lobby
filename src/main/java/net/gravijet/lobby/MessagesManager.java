package net.gravijet.lobby;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class MessagesManager {

    private final Main plugin;
    private FileConfiguration config;

    public MessagesManager(Main plugin) {
        this.plugin = plugin;
        load();
    }

    public void load() {
        plugin.saveResource("messages.yml", false);
        File file = new File(plugin.getDataFolder(), "messages.yml");
        config = YamlConfiguration.loadConfiguration(file);
        var stream = plugin.getResource("messages.yml");
        if (stream != null) {
            FileConfiguration defaults = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            config.setDefaults(defaults);
        }
    }

    /** Returns the translated message for the given key, or "" if not set. */
    public String get(String key) {
        String msg = config.getString(key);
        if (msg == null) msg = "";
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    /** Returns the message with {placeholder} values substituted. Pairs: name, value, name, value... */
    public String format(String key, String... pairs) {
        String msg = get(key);
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            msg = msg.replace("{" + pairs[i] + "}", pairs[i + 1]);
        }
        return msg;
    }

    /** Sends the message only if it is non-empty. */
    public void send(CommandSender sender, String key) {
        String msg = get(key);
        if (!msg.isEmpty()) sender.sendMessage(msg);
    }

    /** Sends the message with placeholders substituted, only if non-empty. */
    public void send(CommandSender sender, String key, String... pairs) {
        String msg = format(key, pairs);
        if (!msg.isEmpty()) sender.sendMessage(msg);
    }

    /** Sends each line of a list message, skipping empty lines. */
    public void sendList(CommandSender sender, String key) {
        List<String> lines = config.getStringList(key);
        for (String line : lines) {
            String msg = ChatColor.translateAlternateColorCodes('&', line);
            if (!msg.isEmpty()) sender.sendMessage(msg);
        }
    }
}

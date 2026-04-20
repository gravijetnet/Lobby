package net.gravijet.lobby.command;

import net.gravijet.lobby.Main;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class ReloadCommand implements CommandExecutor {
    private final Main plugin;

    public ReloadCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("lobby.reload")) {
            plugin.getMessages().send(sender, "general.no-permission");
            return true;
        }

        plugin.reloadPluginConfig();
        plugin.getMessages().send(sender, "reload.success");
        return true;
    }
}

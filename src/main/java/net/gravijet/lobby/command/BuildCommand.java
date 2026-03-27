package net.gravijet.lobby.command;

import net.gravijet.lobby.Main;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class BuildCommand implements CommandExecutor {
    private final Main plugin;

    public BuildCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command!");
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("lobby.build")) {
            player.sendMessage("§cNo permission!");
            return true;
        }

        if (plugin.isInBuildMode(player)) {
            plugin.setBuildMode(player, false);
            player.sendMessage("§cBuild mode disabled!");
        } else {
            plugin.setBuildMode(player, true);
            player.sendMessage("§aBuild mode enabled!");
        }

        return true;
    }
}

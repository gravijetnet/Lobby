package net.gravijet.lobby.command;

import net.gravijet.lobby.Main;
import org.bukkit.Bukkit;
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
        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cUsage: /build <player>");
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

        if (!sender.hasPermission("lobby.build.other")) {
            sender.sendMessage("§cYou don't have permission to toggle build mode for others!");
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage("§cPlayer not found!");
            return true;
        }

        boolean newState = !plugin.isInBuildMode(target);
        plugin.setBuildMode(target, newState);

        if (newState) {
            target.sendMessage("§aBuild mode enabled by " + sender.getName() + "!");
            sender.sendMessage("§aBuild mode enabled for " + target.getName() + "!");
        } else {
            target.sendMessage("§cBuild mode disabled by " + sender.getName() + "!");
            sender.sendMessage("§cBuild mode disabled for " + target.getName() + "!");
        }
        return true;
    }
}

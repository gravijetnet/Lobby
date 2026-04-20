package net.gravijet.lobby.command;

import net.gravijet.lobby.Main;
import net.gravijet.lobby.MessagesManager;
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
        MessagesManager msg = plugin.getMessages();

        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                msg.send(sender, "build.usage");
                return true;
            }
            Player player = (Player) sender;
            if (!player.hasPermission("lobby.build")) {
                msg.send(player, "general.no-permission");
                return true;
            }
            if (plugin.isInBuildMode(player)) {
                plugin.setBuildMode(player, false);
                msg.send(player, "build.disabled");
            } else {
                plugin.setBuildMode(player, true);
                msg.send(player, "build.enabled");
            }
            return true;
        }

        if (!sender.hasPermission("lobby.build.other")) {
            msg.send(sender, "build.no-permission-others");
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            msg.send(sender, "general.player-not-found");
            return true;
        }

        boolean newState = !plugin.isInBuildMode(target);
        plugin.setBuildMode(target, newState);

        if (newState) {
            msg.send(target, "build.enabled-by", "player", sender.getName());
            msg.send(sender, "build.enabled-for", "player", target.getName());
        } else {
            msg.send(target, "build.disabled-by", "player", sender.getName());
            msg.send(sender, "build.disabled-for", "player", target.getName());
        }
        return true;
    }
}

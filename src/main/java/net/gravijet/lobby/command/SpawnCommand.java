package net.gravijet.lobby.command;

import net.gravijet.lobby.Main;
import net.gravijet.lobby.MessagesManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class SpawnCommand implements CommandExecutor {
    private final Main plugin;

    public SpawnCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        MessagesManager msg = plugin.getMessages();
        Player target;

        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                msg.send(sender, "spawn.usage");
                return true;
            }
            target = (Player) sender;
            if (!target.hasPermission("lobby.spawn")) {
                msg.send(target, "general.no-permission");
                return true;
            }
        } else {
            if (!sender.hasPermission("lobby.spawn.other")) {
                msg.send(sender, "spawn.no-permission-others");
                return true;
            }
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                msg.send(sender, "general.player-not-found");
                return true;
            }
        }

        Location spawn = plugin.getSpawnLocation();
        if (spawn == null) {
            msg.send(sender, "spawn.not-set");
            return true;
        }

        target.teleport(spawn);
        if (!target.equals(sender)) {
            msg.send(target, "spawn.teleported-by", "player", sender.getName());
            msg.send(sender, "spawn.teleported-for", "player", target.getName());
        } else {
            msg.send(sender, "spawn.teleported");
        }
        return true;
    }
}

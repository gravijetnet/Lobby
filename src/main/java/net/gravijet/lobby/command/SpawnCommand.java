package net.gravijet.lobby.command;

import net.gravijet.lobby.Main;
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
        // Determine target player
        Player target;
        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cUsage: /spawn <player>");
                return true;
            }
            target = (Player) sender;
            if (!target.hasPermission("lobby.spawn")) {
                target.sendMessage("§cNo permission!");
                return true;
            }
        } else {
            if (!sender.hasPermission("lobby.spawn.other")) {
                sender.sendMessage("§cYou don't have permission to teleport others to spawn!");
                return true;
            }
            target = Bukkit.getPlayer(args[0]);
            if (target == null) {
                sender.sendMessage("§cPlayer not found!");
                return true;
            }
        }

        Location spawn = plugin.getSpawnLocation();
        if (spawn == null) {
            sender.sendMessage("§cSpawn location not set!");
            return true;
        }

        target.teleport(spawn);
        if (!target.equals(sender)) {
            target.sendMessage("§aTeleported to spawn by " + sender.getName() + "!");
            sender.sendMessage("§aTeleported " + target.getName() + " to spawn!");
        } else {
            sender.sendMessage("§aTeleported to spawn!");
        }
        return true;
    }
}

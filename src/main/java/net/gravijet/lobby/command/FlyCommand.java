package net.gravijet.lobby.command;

import net.gravijet.lobby.Main;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class FlyCommand implements CommandExecutor {
    private final Main plugin;

    public FlyCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cUsage: /fly <player>");
                return true;
            }
            Player player = (Player) sender;
            if (!player.hasPermission("lobby.fly")) {
                player.sendMessage("§cNo permission!");
                return true;
            }
            if (player.getAllowFlight()) {
                player.setAllowFlight(false);
                player.setFlying(false);
                plugin.setFlightPreference(player, false);
                player.sendMessage("§cFly disabled!");
            } else {
                plugin.setFlightPreference(player, true);
                plugin.restoreFlightState(player);
                player.sendMessage("§aFly enabled!");
            }
            return true;
        }

        if (!sender.hasPermission("lobby.fly.other")) {
            sender.sendMessage("§cYou don't have permission to toggle fly for others!");
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage("§cPlayer not found!");
            return true;
        }

        if (target.getAllowFlight()) {
            target.setAllowFlight(false);
            target.setFlying(false);
            plugin.setFlightPreference(target, false);
            target.sendMessage("§cFly disabled by " + sender.getName() + "!");
            sender.sendMessage("§cFly disabled for " + target.getName() + "!");
        } else {
            plugin.setFlightPreference(target, true);
            plugin.restoreFlightState(target);
            target.sendMessage("§aFly enabled by " + sender.getName() + "!");
            sender.sendMessage("§aFly enabled for " + target.getName() + "!");
        }
        return true;
    }
}

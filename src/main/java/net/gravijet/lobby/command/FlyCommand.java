package net.gravijet.lobby.command;

import net.gravijet.lobby.Main;
import net.gravijet.lobby.MessagesManager;
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
        MessagesManager msg = plugin.getMessages();

        if (args.length == 0) {
            if (!(sender instanceof Player)) {
                msg.send(sender, "fly.usage");
                return true;
            }
            Player player = (Player) sender;
            if (!player.hasPermission("lobby.fly")) {
                msg.send(player, "general.no-permission");
                return true;
            }
            if (!plugin.isFlightDisabledByUser(player)) {
                plugin.setFlightPreference(player, false);
                player.setAllowFlight(false);
                player.setFlying(false);
                msg.send(player, "fly.disabled");
            } else {
                plugin.setFlightPreference(player, true);
                plugin.restoreFlightState(player);
                msg.send(player, "fly.enabled");
            }
            return true;
        }

        if (!sender.hasPermission("lobby.fly.other")) {
            msg.send(sender, "fly.no-permission-others");
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            msg.send(sender, "general.player-not-found");
            return true;
        }

        if (!plugin.isFlightDisabledByUser(target)) {
            plugin.setFlightPreference(target, false);
            target.setAllowFlight(false);
            target.setFlying(false);
            msg.send(target, "fly.disabled-by", "player", sender.getName());
            msg.send(sender, "fly.disabled-for", "player", target.getName());
        } else {
            plugin.setFlightPreference(target, true);
            plugin.restoreFlightState(target);
            msg.send(target, "fly.enabled-by", "player", sender.getName());
            msg.send(sender, "fly.enabled-for", "player", target.getName());
        }
        return true;
    }
}

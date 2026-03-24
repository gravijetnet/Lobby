package net.gravijet.lobby;

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
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command!");
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
            player.sendMessage("§cFly disabled!");
        } else {
            player.setAllowFlight(true);
            player.setFlying(true);
            player.sendMessage("§aFly enabled!");
        }

        return true;
    }
}
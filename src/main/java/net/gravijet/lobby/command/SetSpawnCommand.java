package net.gravijet.lobby.command;

import net.gravijet.lobby.Main;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class SetSpawnCommand implements CommandExecutor {
    private final Main plugin;

    public SetSpawnCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command!");
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("lobby.setspawn")) {
            player.sendMessage("§cNo permission!");
            return true;
        }

        plugin.setSpawnLocation(player.getLocation());
        player.sendMessage("§aSpawn location set!");
        return true;
    }
}

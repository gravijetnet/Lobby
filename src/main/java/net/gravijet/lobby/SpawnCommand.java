package net.gravijet.lobby;

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
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command!");
            return true;
        }

        Player player = (Player) sender;
        Location spawn = plugin.getSpawnLocation();

        if (spawn == null) {
            player.sendMessage("§cSpawn location not set!");
            return true;
        }

        player.teleport(spawn);
        player.sendMessage("§aTeleported to spawn!");
        return true;
    }
}
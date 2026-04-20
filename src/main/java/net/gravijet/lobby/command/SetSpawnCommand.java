package net.gravijet.lobby.command;

import net.gravijet.lobby.Main;
import net.gravijet.lobby.MessagesManager;
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
        MessagesManager msg = plugin.getMessages();

        if (!(sender instanceof Player)) {
            msg.send(sender, "general.player-only");
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("lobby.setspawn")) {
            msg.send(player, "general.no-permission");
            return true;
        }

        plugin.setSpawnLocation(player.getLocation());
        msg.send(player, "setspawn.set");
        return true;
    }
}

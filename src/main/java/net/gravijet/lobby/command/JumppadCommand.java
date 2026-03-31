package net.gravijet.lobby.command;

import net.gravijet.lobby.Main;
import net.gravijet.lobby.jumppad.JumppadManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

public class JumppadCommand implements CommandExecutor {

    private static final Set<Material> ALLOWED = new HashSet<>(Arrays.asList(
            Material.SLIME_BLOCK,
            Material.WOOD_PLATE,
            Material.STONE_PLATE,
            Material.GOLD_PLATE,
            Material.IRON_PLATE
    ));

    private final Main plugin;
    private final JumppadManager jumppadManager;

    public JumppadCommand(Main plugin, JumppadManager jumppadManager) {
        this.plugin = plugin;
        this.jumppadManager = jumppadManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("lobby.jumppad")) {
            sender.sendMessage("§cKeine Berechtigung!");
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "add":    handleAdd(sender);    break;
            case "remove": handleRemove(sender); break;
            case "list":   handleList(sender);   break;
            default:       sendUsage(sender);    break;
        }
        return true;
    }

    private void handleAdd(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cNur für Spieler!");
            return;
        }
        Player player = (Player) sender;
        Location loc = player.getLocation();
        Location below = new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());

        if (!ALLOWED.contains(below.getBlock().getType())) {
            player.sendMessage("§cStehe auf einem Slimeblock oder einer Druckplatte!");
            return;
        }
        if (jumppadManager.addJumppad(below)) {
            player.sendMessage("§aJumppad hinzugefügt bei §f(" + below.getBlockX() + ", " + below.getBlockY() + ", " + below.getBlockZ() + ")§a!");
        } else {
            player.sendMessage("§cHier ist bereits ein Jumppad!");
        }
    }

    private void handleRemove(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cNur für Spieler!");
            return;
        }
        Player player = (Player) sender;
        Location loc = player.getLocation();
        Location below = new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());

        if (jumppadManager.removeJumppad(below)) {
            player.sendMessage("§aJumppad entfernt!");
        } else {
            player.sendMessage("§cKein Jumppad an dieser Stelle!");
        }
    }

    private void handleList(CommandSender sender) {
        Collection<Location> all = jumppadManager.getJumppads();
        if (all.isEmpty()) {
            sender.sendMessage("§7Keine Jumppads definiert.");
            return;
        }
        sender.sendMessage("§6Jumppads §7(" + all.size() + "):");
        int i = 1;
        for (Location loc : all) {
            sender.sendMessage("§7  " + i++ + ". §f" + loc.getWorld().getName()
                    + " §8(" + loc.getBlockX() + ", " + loc.getBlockY() + ", " + loc.getBlockZ() + ")");
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§c/jumppad add §8- §7Jumppad unter dir hinzufügen");
        sender.sendMessage("§c/jumppad remove §8- §7Jumppad unter dir entfernen");
        sender.sendMessage("§c/jumppad list §8- §7Alle Jumppads auflisten");
    }
}

package net.gravijet.lobby.command;

import net.gravijet.lobby.Main;
import net.gravijet.lobby.jumppad.Jumppad;
import net.gravijet.lobby.jumppad.JumppadManager;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;

public final class JumppadCommand implements CommandExecutor {

    private final Main           plugin;
    private final JumppadManager jumppadManager;

    public JumppadCommand(Main plugin, JumppadManager jumppadManager) {
        this.plugin         = plugin;
        this.jumppadManager = jumppadManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("lobby.jumppad")) {
            sender.sendMessage("§cYou don't have permission to use jumppad commands.");
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "help":   sendHelp(sender);           break;
            case "add":    handleAdd(sender, args);    break;
            case "remove": handleRemove(sender);       break;
            case "delete": handleDelete(sender, args); break;
            case "setvel": handleSetVel(sender, args); break;
            case "list":   handleList(sender);         break;
            case "info":   handleInfo(sender, args);   break;
            default:       sendHelp(sender);           break;
        }
        return true;
    }

    private void handleAdd(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage("§cUsage: §f/jumppad add <name>");
            return;
        }
        Player   player = (Player) sender;
        Location below  = blockBelow(player);
        if (jumppadManager.addBlock(args[1], below)) {
            sender.sendMessage("§aAdded block §f("
                    + below.getBlockX() + ", " + below.getBlockY() + ", " + below.getBlockZ()
                    + ")§a to jumppad §f" + args[1] + "§a.");
        } else {
            sender.sendMessage("§cThis block already belongs to a jumppad.");
        }
    }

    private void handleRemove(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return;
        }
        Player player  = (Player) sender;
        Location below = blockBelow(player);
        String removed = jumppadManager.removeBlock(below);
        if (removed != null) {
            sender.sendMessage("§aBlock removed from jumppad §f" + removed + "§a.");
        } else {
            sender.sendMessage("§cNo jumppad block found below you.");
        }
    }

    private void handleDelete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: §f/jumppad delete <name>");
            return;
        }
        if (jumppadManager.deleteJumppad(args[1])) {
            sender.sendMessage("§aJumppad §f" + args[1] + " §adeleted.");
        } else {
            sender.sendMessage("§cNo jumppad named §f" + args[1] + "§c found.");
        }
    }

    private void handleSetVel(CommandSender sender, String[] args) {
        if (args.length < 5) {
            sender.sendMessage("§cUsage: §f/jumppad setvel <name> <x> <y> <z>");
            return;
        }
        double x, y, z;
        try {
            x = Double.parseDouble(args[2]);
            y = Double.parseDouble(args[3]);
            z = Double.parseDouble(args[4]);
        } catch (NumberFormatException e) {
            sender.sendMessage("§cX, Y and Z must be valid numbers.");
            return;
        }
        if (jumppadManager.setVelocity(args[1], x, y, z)) {
            sender.sendMessage("§aVelocity of §f" + args[1]
                    + " §aset to §f(" + x + ", " + y + ", " + z + ")§a.");
        } else {
            sender.sendMessage("§cNo jumppad named §f" + args[1] + "§c found.");
        }
    }

    private void handleList(CommandSender sender) {
        Collection<Jumppad> all = jumppadManager.getAll();
        if (all.isEmpty()) {
            sender.sendMessage("§7No jumppads defined.");
            return;
        }
        sender.sendMessage("§6Jumppads §7(" + all.size() + "):");
        int i = 1;
        for (Jumppad pad : all) {
            sender.sendMessage(String.format("§7  %d. §f%s §8[%d block(s), vel: (%.1f, %.1f, %.1f)]",
                    i++, pad.getName(), pad.getBlockCount(),
                    pad.getVelX(), pad.getVelY(), pad.getVelZ()));
        }
    }

    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: §f/jumppad info <name>");
            return;
        }
        Jumppad pad = jumppadManager.getJumppad(args[1]);
        if (pad == null) {
            sender.sendMessage("§cNo jumppad named §f" + args[1] + "§c found.");
            return;
        }
        sender.sendMessage("§6§l--- Jumppad: " + pad.getName() + " ---");
        sender.sendMessage("§7Blocks:   §f" + pad.getBlockCount());
        sender.sendMessage("§7Velocity: §f(" + pad.getVelX() + ", " + pad.getVelY() + ", " + pad.getVelZ() + ")");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§c§lGraviJet §7» §f§lJumppad §8- §7Commands");
        sender.sendMessage("§4● §c/jumppad add <name> §7» §fAdd block below to jumppad");
        sender.sendMessage("§4● §c/jumppad remove §7» §fRemove block below from its jumppad");
        sender.sendMessage("§4● §c/jumppad delete <name> §7» §fDelete entire jumppad");
        sender.sendMessage("§4● §c/jumppad setvel <name> <x> <y> <z> §7» §fSet launch velocity");
        sender.sendMessage("§4● §c/jumppad list §7» §fList all jumppads");
        sender.sendMessage("§4● §c/jumppad info <name> §7» §fShow jumppad details");
    }

    private static Location blockBelow(Player player) {
        Location loc = player.getLocation();
        return new Location(loc.getWorld(), loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());
    }
}

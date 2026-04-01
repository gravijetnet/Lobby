package net.gravijet.lobby.command;

import net.gravijet.lobby.Main;
import net.gravijet.lobby.zone.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;

public final class ZoneCommand implements CommandExecutor {

    private final Main plugin;
    private final ZoneManager zoneManager;

    public ZoneCommand(Main plugin, ZoneManager zoneManager) {
        this.plugin = plugin;
        this.zoneManager = zoneManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("lobby.zone")) {
            sender.sendMessage("§cYou don't have permission to use zone commands.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("wand") || sub.equals("save") || sub.equals("clear")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cThis subcommand can only be used by players.");
                return true;
            }
            Player player = (Player) sender;
            switch (sub) {
                case "wand":
                    handleGive(player);
                    break;
                case "save":
                    handleSave(player, args);
                    break;
                case "clear":
                    handleClear(player);
                    break;
            }
            return true;
        }

        switch (sub) {
            case "delete":
                handleDelete(sender, args);
                break;
            case "list":
                handleList(sender);
                break;
            case "info":
                handleInfo(sender, args);
                break;
            case "setperm":
                handleSetPerm(sender, args);
                break;
            case "clearperm":
                handleClearPerm(sender, args);
                break;
            case "setmessage":
                handleSetMessage(sender, args);
                break;
            case "setblockplace":
                handleSetBlockPlace(sender, args);
                break;
            case "setflight":
                handleSetFlight(sender, args);
                break;
            default:
                sendUsage(sender);
                break;
        }
        return true;
    }

    private void handleGive(Player player) {
        player.getInventory().addItem(ZoneManager.createWand());
        player.sendMessage("§aZone Wand given!");
        player.sendMessage("§7Right-click to add corners, left-click to remove,");
        player.sendMessage("§7Shift+right-click to preview the selection.");
    }

    private void handleSave(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /zone save <name> [minY] [maxY]");
            return;
        }

        String name = args[1];
        if (name.contains(" ") || name.isEmpty()) {
            player.sendMessage("§cZone name must not contain spaces.");
            return;
        }

        ZoneSelectionSession session = zoneManager.getSession(player.getUniqueId());
        if (session == null || !session.isComplete()) {
            player.sendMessage("§cYou need at least 3 corners in your selection!");
            player.sendMessage("§7Use §e/zone wand §7to get the Zone Wand first.");
            return;
        }

        int minY = 0, maxY = 256;
        if (args.length >= 4) {
            try {
                minY = Integer.parseInt(args[2]);
                maxY = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                player.sendMessage("§cminY and maxY must be integers.");
                return;
            }
            if (minY > maxY) {
                player.sendMessage("§cminY (" + minY + ") must be <= maxY (" + maxY + ").");
                return;
            }
        } else if (args.length == 3) {
            player.sendMessage("§cProvide both minY and maxY, or neither.");
            return;
        }

        try {
            Zone zone = zoneManager.saveZone(name, session, minY, maxY);
            zoneManager.clearSession(player.getUniqueId());
            player.sendMessage("§aZone §f'" + zone.getName() + "' §asaved with §f" + zone.getCorners().size() + " §acorners" + (minY == 0 && maxY == 256 ? " (full height)." : " (Y: " + minY + "–" + maxY + ")."));
        } catch (IllegalArgumentException e) {
            player.sendMessage("§c" + e.getMessage());
        }
    }

    private void handleDelete(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /zone delete <name>");
            return;
        }
        if (zoneManager.deleteZone(args[1])) {
            sender.sendMessage("§aZone §f'" + args[1] + "' §adeleted.");
        } else {
            sender.sendMessage("§cNo zone named §f'" + args[1] + "' §cfound.");
        }
    }

    private void handleList(CommandSender sender) {
        Collection<Zone> all = zoneManager.getAllZones();
        if (all.isEmpty()) {
            sender.sendMessage("§7No zones are currently defined.");
            return;
        }
        sender.sendMessage("§6Zones §7(" + all.size() + "):");
        int i = 1;
        for (Zone zone : all) {
            String marker = zone.isRestricted() ? " §c[VIP: " + zone.getRequiredPermission() + "]" : "";
            sender.sendMessage(String.format("§7  %d. §f%s §8[%s, Y:%d–%d, %d corners]%s", i++, zone.getName(), zone.getWorldName(), zone.getMinY(), zone.getMaxY(), zone.getCorners().size(), marker));
        }
    }

    private void handleClear(Player player) {
        zoneManager.clearSession(player.getUniqueId());
        player.sendMessage("§aSelection cleared.");
    }

    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /zone info <name>");
            return;
        }
        Zone zone = zoneManager.getZone(args[1]);
        if (zone == null) {
            sender.sendMessage("§cNo zone named §f'" + args[1] + "' §cfound.");
            return;
        }
        sender.sendMessage("§6§l--- Zone: " + zone.getName() + " ---");
        sender.sendMessage("§7World:   §f" + zone.getWorldName());
        sender.sendMessage("§7Y-range: §f" + zone.getMinY() + " §8– §f" + zone.getMaxY());
        sender.sendMessage("§7Corners: §f" + zone.getCorners().size());
        int idx = 1;
        for (int[] c : zone.getCorners()) {
            sender.sendMessage(String.format("§7  #%d §8» §f(%d, %d)", idx++, c[0], c[1]));
        }
        if (zone.isRestricted()) {
            sender.sendMessage("§7Permission: §c" + zone.getRequiredPermission());
            sender.sendMessage("§7Deny msg:   §f" + zone.getDenyMessage());
        } else {
            sender.sendMessage("§7Permission: §anone (open to all)");
        }
        sender.sendMessage("§7Block placement: §f" + (zone.isAllowBlockPlacement() ? "§aallowed" : "§cdenied"));
        sender.sendMessage("§7Flight:          §f" + (zone.isAllowFlight() ? "§aallowed" : "§cdisabled"));
    }

    private void handleSetPerm(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /zone setperm <name> <permission>");
            return;
        }
        if (zoneManager.setZonePermission(args[1], args[2])) {
            sender.sendMessage("§aZone §f'" + args[1] + "' §anow requires permission §f" + args[2] + "§a.");
        } else {
            sender.sendMessage("§cNo zone named §f'" + args[1] + "' §cfound.");
        }
    }

    private void handleClearPerm(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /zone clearperm <name>");
            return;
        }
        if (zoneManager.setZonePermission(args[1], null)) {
            sender.sendMessage("§aPermission restriction removed from zone §f'" + args[1] + "' §a(now open to all).");
        } else {
            sender.sendMessage("§cNo zone named §f'" + args[1] + "' §cfound.");
        }
    }

    private void handleSetMessage(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /zone setmessage <name> <message...>");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i < args.length; i++) {
            if (i > 2) sb.append(' ');
            sb.append(args[i]);
        }
        if (zoneManager.setZoneDenyMessage(args[1], sb.toString().replace("\\n", "\n"))) {
            sender.sendMessage("§aDeny-message for zone §f'" + args[1] + "' §aset.");
        } else {
            sender.sendMessage("§cNo zone named §f'" + args[1] + "' §cfound.");
        }
    }

    private void handleSetFlight(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /zone setflight <name> <true|false>");
            return;
        }
        String val = args[2].toLowerCase();
        boolean allow;
        if (val.equals("true") || val.equals("yes") || val.equals("1")) {
            allow = true;
        } else if (val.equals("false") || val.equals("no") || val.equals("0")) {
            allow = false;
        } else {
            sender.sendMessage("§cValue must be true or false.");
            return;
        }
        if (zoneManager.setZoneAllowFlight(args[1], allow)) {
            sender.sendMessage("§aZone §f'" + args[1] + "' §aflight set to: §f" + (allow ? "allowed" : "disabled"));
        } else {
            sender.sendMessage("§cNo zone named §f'" + args[1] + "' §cfound.");
        }
    }

    private void handleSetBlockPlace(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage("§cUsage: /zone setblockplace <name> <true|false>");
            return;
        }
        String val = args[2].toLowerCase();
        boolean allow;
        if (val.equals("true") || val.equals("yes") || val.equals("1")) {
            allow = true;
        } else if (val.equals("false") || val.equals("no") || val.equals("0")) {
            allow = false;
        } else {
            sender.sendMessage("§cValue must be true or false.");
            return;
        }
        if (zoneManager.setZoneAllowBlockPlacement(args[1], allow)) {
            sender.sendMessage("§aZone §f'" + args[1] + "' §ablock placement set to: §f" + allow);
        } else {
            sender.sendMessage("§cNo zone named §f'" + args[1] + "' §cfound.");
        }
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§c§lGraviJet §7» §f§lZone §8- §7Commands");
        sender.sendMessage("§4● §c/zone wand §7» §fGet the Zone Wand");
        sender.sendMessage("§4● §c/zone save <name> [minY maxY] §7» §fSave selection as zone");
        sender.sendMessage("§4● §c/zone delete <name> §7» §fDelete a zone");
        sender.sendMessage("§4● §c/zone list §7» §fList all zones");
        sender.sendMessage("§4● §c/zone clear §7» §fClear your selection");
        sender.sendMessage("§4● §c/zone info <name> §7» §fShow zone details");
        sender.sendMessage("§4● §c/zone setperm <name> <permission> §7» §fRestrict zone");
        sender.sendMessage("§4● §c/zone clearperm <name> §7» §fRemove restriction");
        sender.sendMessage("§4● §c/zone setmessage <name> <msg...> §7» §fSet deny message");
        sender.sendMessage("§4● §c/zone setblockplace <name> <true|false> §7» §fBlock placement");
        sender.sendMessage("§4● §c/zone setflight <name> <true|false> §7» §fAllow/disable flight");
    }
}

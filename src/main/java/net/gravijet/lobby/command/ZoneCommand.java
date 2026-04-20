package net.gravijet.lobby.command;

import net.gravijet.lobby.Main;
import net.gravijet.lobby.MessagesManager;
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
        MessagesManager msg = plugin.getMessages();

        if (!sender.hasPermission("lobby.zone")) {
            msg.send(sender, "zone.no-permission");
            return true;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        String sub = args[0].toLowerCase();

        if (sub.equals("wand") || sub.equals("save") || sub.equals("clear")) {
            if (!(sender instanceof Player)) {
                msg.send(sender, "zone.player-only");
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
        MessagesManager msg = plugin.getMessages();
        player.getInventory().addItem(ZoneManager.createWand());
        msg.send(player, "zone.wand.given");
        msg.sendList(player, "zone.wand.instructions");
    }

    private void handleSave(Player player, String[] args) {
        MessagesManager msg = plugin.getMessages();

        if (args.length < 2) {
            msg.send(player, "zone.save.usage");
            return;
        }

        String name = args[1];
        if (name.contains(" ") || name.isEmpty()) {
            msg.send(player, "zone.save.invalid-name");
            return;
        }

        ZoneSelectionSession session = zoneManager.getSession(player.getUniqueId());
        if (session == null || !session.isComplete()) {
            msg.send(player, "zone.save.not-enough-corners");
            msg.send(player, "zone.save.use-wand");
            return;
        }

        int minY = 0, maxY = 256;
        if (args.length >= 4) {
            try {
                minY = Integer.parseInt(args[2]);
                maxY = Integer.parseInt(args[3]);
            } catch (NumberFormatException e) {
                msg.send(player, "zone.save.invalid-y");
                return;
            }
            if (minY > maxY) {
                msg.send(player, "zone.save.y-mismatch",
                        "miny", String.valueOf(minY),
                        "maxy", String.valueOf(maxY));
                return;
            }
        } else if (args.length == 3) {
            msg.send(player, "zone.save.incomplete-y");
            return;
        }

        try {
            Zone zone = zoneManager.saveZone(name, session, minY, maxY);
            zoneManager.clearSession(player.getUniqueId());
            if (minY == 0 && maxY == 256) {
                msg.send(player, "zone.save.saved-full",
                        "name", zone.getName(),
                        "corners", String.valueOf(zone.getCorners().size()));
            } else {
                msg.send(player, "zone.save.saved-y",
                        "name", zone.getName(),
                        "corners", String.valueOf(zone.getCorners().size()),
                        "miny", String.valueOf(minY),
                        "maxy", String.valueOf(maxY));
            }
        } catch (IllegalArgumentException e) {
            msg.send(player, "zone.save.error", "error", e.getMessage());
        }
    }

    private void handleDelete(CommandSender sender, String[] args) {
        MessagesManager msg = plugin.getMessages();
        if (args.length < 2) {
            msg.send(sender, "zone.delete.usage");
            return;
        }
        if (zoneManager.deleteZone(args[1])) {
            msg.send(sender, "zone.delete.deleted", "name", args[1]);
        } else {
            msg.send(sender, "zone.delete.not-found", "name", args[1]);
        }
    }

    private void handleList(CommandSender sender) {
        MessagesManager msg = plugin.getMessages();
        Collection<Zone> all = zoneManager.getAllZones();
        if (all.isEmpty()) {
            msg.send(sender, "zone.list.empty");
            return;
        }
        msg.send(sender, "zone.list.header", "count", String.valueOf(all.size()));
        int i = 1;
        for (Zone zone : all) {
            String marker = zone.isRestricted() ? " §c[VIP: " + zone.getRequiredPermission() + "]" : "";
            sender.sendMessage(msg.format("zone.list.entry",
                    "index", String.valueOf(i++),
                    "name", zone.getName(),
                    "world", zone.getWorldName(),
                    "miny", String.valueOf(zone.getMinY()),
                    "maxy", String.valueOf(zone.getMaxY()),
                    "corners", String.valueOf(zone.getCorners().size()),
                    "marker", marker));
        }
    }

    private void handleClear(Player player) {
        zoneManager.clearSession(player.getUniqueId());
        plugin.getMessages().send(player, "zone.clear.cleared");
    }

    private void handleInfo(CommandSender sender, String[] args) {
        MessagesManager msg = plugin.getMessages();
        if (args.length < 2) {
            msg.send(sender, "zone.info.usage");
            return;
        }
        Zone zone = zoneManager.getZone(args[1]);
        if (zone == null) {
            msg.send(sender, "zone.info.not-found", "name", args[1]);
            return;
        }
        msg.send(sender, "zone.info.header", "name", zone.getName());
        msg.send(sender, "zone.info.world", "world", zone.getWorldName());
        msg.send(sender, "zone.info.y-range",
                "miny", String.valueOf(zone.getMinY()),
                "maxy", String.valueOf(zone.getMaxY()));
        msg.send(sender, "zone.info.corners-count", "count", String.valueOf(zone.getCorners().size()));
        int idx = 1;
        for (int[] c : zone.getCorners()) {
            sender.sendMessage(msg.format("zone.info.corner-entry",
                    "index", String.valueOf(idx++),
                    "x", String.valueOf(c[0]),
                    "z", String.valueOf(c[1])));
        }
        if (zone.isRestricted()) {
            msg.send(sender, "zone.info.permission-required", "permission", zone.getRequiredPermission());
            msg.send(sender, "zone.info.deny-msg", "message", zone.getDenyMessage());
        } else {
            msg.send(sender, "zone.info.permission-open");
        }
        msg.send(sender, "zone.info.block-placement",
                "status", zone.isAllowBlockPlacement() ? "§aallowed" : "§cdenied");
        msg.send(sender, "zone.info.flight",
                "status", zone.isAllowFlight() ? "§aallowed" : "§cdisabled");
    }

    private void handleSetPerm(CommandSender sender, String[] args) {
        MessagesManager msg = plugin.getMessages();
        if (args.length < 3) {
            msg.send(sender, "zone.setperm.usage");
            return;
        }
        if (zoneManager.setZonePermission(args[1], args[2])) {
            msg.send(sender, "zone.setperm.set", "name", args[1], "permission", args[2]);
        } else {
            msg.send(sender, "zone.setperm.not-found", "name", args[1]);
        }
    }

    private void handleClearPerm(CommandSender sender, String[] args) {
        MessagesManager msg = plugin.getMessages();
        if (args.length < 2) {
            msg.send(sender, "zone.clearperm.usage");
            return;
        }
        if (zoneManager.setZonePermission(args[1], null)) {
            msg.send(sender, "zone.clearperm.cleared", "name", args[1]);
        } else {
            msg.send(sender, "zone.clearperm.not-found", "name", args[1]);
        }
    }

    private void handleSetMessage(CommandSender sender, String[] args) {
        MessagesManager msg = plugin.getMessages();
        if (args.length < 3) {
            msg.send(sender, "zone.setmessage.usage");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 2; i < args.length; i++) {
            if (i > 2) sb.append(' ');
            sb.append(args[i]);
        }
        if (zoneManager.setZoneDenyMessage(args[1], sb.toString().replace("\\n", "\n"))) {
            msg.send(sender, "zone.setmessage.set", "name", args[1]);
        } else {
            msg.send(sender, "zone.setmessage.not-found", "name", args[1]);
        }
    }

    private void handleSetFlight(CommandSender sender, String[] args) {
        MessagesManager msg = plugin.getMessages();
        if (args.length < 3) {
            msg.send(sender, "zone.setflight.usage");
            return;
        }
        String val = args[2].toLowerCase();
        boolean allow;
        if (val.equals("true") || val.equals("yes") || val.equals("1")) {
            allow = true;
        } else if (val.equals("false") || val.equals("no") || val.equals("0")) {
            allow = false;
        } else {
            msg.send(sender, "zone.setflight.invalid-value");
            return;
        }
        if (zoneManager.setZoneAllowFlight(args[1], allow)) {
            msg.send(sender, "zone.setflight.set",
                    "name", args[1],
                    "status", allow ? "allowed" : "disabled");
        } else {
            msg.send(sender, "zone.setflight.not-found", "name", args[1]);
        }
    }

    private void handleSetBlockPlace(CommandSender sender, String[] args) {
        MessagesManager msg = plugin.getMessages();
        if (args.length < 3) {
            msg.send(sender, "zone.setblockplace.usage");
            return;
        }
        String val = args[2].toLowerCase();
        boolean allow;
        if (val.equals("true") || val.equals("yes") || val.equals("1")) {
            allow = true;
        } else if (val.equals("false") || val.equals("no") || val.equals("0")) {
            allow = false;
        } else {
            msg.send(sender, "zone.setblockplace.invalid-value");
            return;
        }
        if (zoneManager.setZoneAllowBlockPlacement(args[1], allow)) {
            msg.send(sender, "zone.setblockplace.set",
                    "name", args[1],
                    "status", String.valueOf(allow));
        } else {
            msg.send(sender, "zone.setblockplace.not-found", "name", args[1]);
        }
    }

    private void sendUsage(CommandSender sender) {
        plugin.getMessages().sendList(sender, "zone.help");
    }
}

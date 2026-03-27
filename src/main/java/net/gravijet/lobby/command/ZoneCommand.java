package net.gravijet.lobby.command;

import net.gravijet.lobby.Main;
import net.gravijet.lobby.zone.Zone;
import net.gravijet.lobby.zone.ZoneManager;
import net.gravijet.lobby.zone.ZoneSelectionSession;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;

/**
 * Handles the {@code /zone} command and all its sub-commands.
 *
 * <pre>
 * /zone give                       – Give the Zone Wand to the sender
 * /zone save <name> [minY] [maxY]  – Save current selection as a named zone
 * /zone delete <name>              – Delete a saved zone
 * /zone list                       – List all saved zones
 * /zone clear                      – Discard the current selection
 * /zone info <name>                – Show corner details for a zone
 * </pre>
 *
 * Permission: {@code lobby.zone} required for all sub-commands.
 */
public final class ZoneCommand implements CommandExecutor {

    private final Main        plugin;
    private final ZoneManager zoneManager;

    public ZoneCommand(Main plugin, ZoneManager zoneManager) {
        this.plugin      = plugin;
        this.zoneManager = zoneManager;
    }

    // =========================================================================
    // Entry point
    // =========================================================================

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("lobby.zone")) {
            player.sendMessage("§cYou don't have permission to use zone commands.");
            return true;
        }

        if (args.length == 0) {
            sendUsage(player);
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "give":   handleGive(player);              break;
            case "save":   handleSave(player, args);        break;
            case "delete": handleDelete(player, args);      break;
            case "list":   handleList(player);              break;
            case "clear":  handleClear(player);             break;
            case "info":   handleInfo(player, args);        break;
            default:       sendUsage(player);               break;
        }
        return true;
    }

    // =========================================================================
    // Sub-command handlers
    // =========================================================================

    /**
     * /zone give
     * Gives the player a Zone Wand.
     */
    private void handleGive(Player player) {
        player.getInventory().addItem(ZoneManager.createWand());
        player.sendMessage("§aZone Wand given!");
        player.sendMessage("§7Right-click to add corners, left-click to remove,");
        player.sendMessage("§7Shift+right-click to preview the selection.");
    }

    /**
     * /zone save &lt;name&gt; [minY] [maxY]
     *
     * Converts the player's current session into a saved zone. Y-bounds
     * default to 0 / 256 (full-height column) when not specified.
     */
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
            player.sendMessage("§7Use §e/zone give §7to get the Zone Wand first.");
            return;
        }

        // Parse optional Y bounds
        int minY = 0;
        int maxY = 256;
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
            player.sendMessage("§aZone §f'" + zone.getName() + "' §asaved with §f"
                    + zone.getCorners().size() + " §acorners"
                    + (minY == 0 && maxY == 256 ? " (full height)." : " (Y: " + minY + "–" + maxY + ")."));
        } catch (IllegalArgumentException e) {
            player.sendMessage("§c" + e.getMessage());
        }
    }

    /**
     * /zone delete &lt;name&gt;
     */
    private void handleDelete(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /zone delete <name>");
            return;
        }

        String name = args[1];
        if (zoneManager.deleteZone(name)) {
            player.sendMessage("§aZone §f'" + name + "' §adeleted.");
        } else {
            player.sendMessage("§cNo zone named §f'" + name + "' §cfound.");
        }
    }

    /**
     * /zone list
     * Prints all zone names, one per line.
     */
    private void handleList(Player player) {
        Collection<Zone> all = zoneManager.getAllZones();
        if (all.isEmpty()) {
            player.sendMessage("§7No zones are currently defined.");
            return;
        }
        player.sendMessage("§6Zones §7(" + all.size() + "):");
        int i = 1;
        for (Zone zone : all) {
            player.sendMessage(String.format("§7  %d. §f%s §8[%s, Y:%d–%d, %d corners]",
                    i++,
                    zone.getName(),
                    zone.getWorldName(),
                    zone.getMinY(),
                    zone.getMaxY(),
                    zone.getCorners().size()));
        }
    }

    /**
     * /zone clear
     * Discards the player's current unsaved selection.
     */
    private void handleClear(Player player) {
        zoneManager.clearSession(player.getUniqueId());
        player.sendMessage("§aSelection cleared.");
    }

    /**
     * /zone info &lt;name&gt;
     * Prints corner coordinates, world, and Y-bounds of the named zone.
     */
    private void handleInfo(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /zone info <name>");
            return;
        }

        Zone zone = zoneManager.getZone(args[1]);
        if (zone == null) {
            player.sendMessage("§cNo zone named §f'" + args[1] + "' §cfound.");
            return;
        }

        player.sendMessage("§6§l--- Zone: " + zone.getName() + " ---");
        player.sendMessage("§7World:   §f" + zone.getWorldName());
        player.sendMessage("§7Y-range: §f" + zone.getMinY() + " §8– §f" + zone.getMaxY());
        player.sendMessage("§7Corners: §f" + zone.getCorners().size());
        int idx = 1;
        for (int[] c : zone.getCorners()) {
            player.sendMessage(String.format("§7  #%d §8» §f(%d, %d)", idx++, c[0], c[1]));
        }
    }

    // =========================================================================
    // Usage
    // =========================================================================

    private void sendUsage(CommandSender sender) {
        sender.sendMessage("§6Zone Commands:");
        sender.sendMessage("§e/zone give                    §7— Get the Zone Wand");
        sender.sendMessage("§e/zone save <name> [minY maxY] §7— Save selection as zone");
        sender.sendMessage("§e/zone delete <name>           §7— Delete a zone");
        sender.sendMessage("§e/zone list                    §7— List all zones");
        sender.sendMessage("§e/zone clear                   §7— Clear your selection");
        sender.sendMessage("§e/zone info <name>             §7— Show zone details");
    }
}

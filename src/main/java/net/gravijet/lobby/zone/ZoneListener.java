package net.gravijet.lobby.zone;

import net.gravijet.lobby.Main;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

public final class ZoneListener implements Listener {

    private final Main        plugin;
    private final ZoneManager zoneManager;

    public ZoneListener(Main plugin, ZoneManager zoneManager) {
        this.plugin      = plugin;
        this.zoneManager = zoneManager;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onWandInteract(PlayerInteractEvent event) {
        Player    player = event.getPlayer();
        ItemStack item   = event.getItem();

        if (!ZoneManager.isWand(item))          return;
        if (!player.hasPermission("lobby.zone")) return;

        event.setCancelled(true);

        Action action = event.getAction();

        if (action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR) {
            ZoneSelectionSession session = zoneManager.getOrCreateSession(player.getUniqueId());
            if (session.removeLastCorner()) {
                player.sendMessage("§cRemoved last corner. §7(" + session.size() + " remaining)");
            } else {
                player.sendMessage("§cNo corners to remove.");
            }
            return;
        }

        if (action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR) {
            if (player.isSneaking()) {
                ZoneSelectionSession session = zoneManager.getOrCreateSession(player.getUniqueId());
                for (String line : session.buildSummary()) {
                    player.sendMessage(line);
                }
                return;
            }

            Location cornerLoc = resolveCornerLocation(event);
            ZoneSelectionSession session = zoneManager.getOrCreateSession(player.getUniqueId());

            if (!session.isEmpty() && session.getWorldName() != null) {
                if (!session.getWorldName().equals(cornerLoc.getWorld().getName())) {
                    player.sendMessage("§cAll corners must be in the same world!");
                    return;
                }
            }

            int index = session.addCorner(cornerLoc);
            player.sendMessage(String.format(
                    "§aCorner §f#%d §aadded at §f(%d, %d, %d)§a. §7[%d total%s]",
                    index,
                    cornerLoc.getBlockX(),
                    cornerLoc.getBlockY(),
                    cornerLoc.getBlockZ(),
                    session.size(),
                    session.isComplete() ? " — §apolygon ready§7" : " — need " + (3 - session.size()) + " more"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        zoneManager.clearSession(event.getPlayer().getUniqueId());
    }

    private Location resolveCornerLocation(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        Player player = event.getPlayer();

        if (clicked != null && event.getBlockFace() != BlockFace.DOWN) {
            return new Location(clicked.getWorld(), clicked.getX() + 0.5, clicked.getY(), clicked.getZ() + 0.5);
        }

        Location feet = player.getLocation();
        return new Location(feet.getWorld(), feet.getBlockX() + 0.5, feet.getBlockY(), feet.getBlockZ() + 0.5);
    }
}

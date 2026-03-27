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

/**
 * Handles all Zone Wand (Blaze Rod) interactions.
 *
 * <ul>
 *   <li><b>Right-click</b> — adds a corner at the clicked block's top surface,
 *       or at the player's feet when clicking air.</li>
 *   <li><b>Left-click</b> — removes the last corner from the active session.</li>
 *   <li><b>Shift + Right-click</b> — prints the full selection summary to chat.</li>
 * </ul>
 *
 * Only players with the {@code lobby.zone} permission can use the wand.
 * All wand interactions cancel the event to suppress vanilla block-interaction
 * behaviour (so no right-clicking chests, etc.).
 */
public final class ZoneListener implements Listener {

    private final Main        plugin;
    private final ZoneManager zoneManager;

    public ZoneListener(Main plugin, ZoneManager zoneManager) {
        this.plugin      = plugin;
        this.zoneManager = zoneManager;
    }

    // =========================================================================
    // Zone Wand interaction
    // =========================================================================

    @EventHandler(priority = EventPriority.NORMAL)
    public void onWandInteract(PlayerInteractEvent event) {
        Player    player = event.getPlayer();
        ItemStack item   = event.getItem();

        // Only react when holding the Zone Wand with the right permission.
        if (!ZoneManager.isWand(item))                    return;
        if (!player.hasPermission("lobby.zone"))           return;

        // Always cancel to prevent vanilla behaviour (block place, chest open, etc.)
        event.setCancelled(true);

        Action action = event.getAction();

        // ── Left-click: remove last corner ────────────────────────────────────
        if (action == Action.LEFT_CLICK_BLOCK || action == Action.LEFT_CLICK_AIR) {
            ZoneSelectionSession session = zoneManager.getOrCreateSession(player.getUniqueId());
            if (session.removeLastCorner()) {
                player.sendMessage("§cRemoved last corner. §7(" + session.size() + " remaining)");
            } else {
                player.sendMessage("§cNo corners to remove.");
            }
            return;
        }

        // ── Right-click: add corner OR show summary (when sneaking) ───────────
        if (action == Action.RIGHT_CLICK_BLOCK || action == Action.RIGHT_CLICK_AIR) {
            if (player.isSneaking()) {
                // Shift + Right-click: print summary
                ZoneSelectionSession session = zoneManager.getOrCreateSession(player.getUniqueId());
                for (String line : session.buildSummary()) {
                    player.sendMessage(line);
                }
                return;
            }

            Location cornerLoc = resolveCornerLocation(event);
            ZoneSelectionSession session = zoneManager.getOrCreateSession(player.getUniqueId());

            // Ensure all corners are in the same world.
            if (!session.isEmpty() && session.getWorldName() != null) {
                if (!session.getWorldName().equals(cornerLoc.getWorld().getName())) {
                    player.sendMessage("§cAll corners must be in the same world!");
                    return;
                }
            }

            int index = session.addCorner(cornerLoc);
            player.sendMessage(String.format(
                    "§aCorner §f#%d §aadded at §f(%d, %d, %d)§a. "
                            + "§7[%d total%s]",
                    index,
                    cornerLoc.getBlockX(),
                    cornerLoc.getBlockY(),
                    cornerLoc.getBlockZ(),
                    session.size(),
                    session.isComplete() ? " — §apolygon ready§7" : " — need " + (3 - session.size()) + " more"));
        }
    }

    // =========================================================================
    // Cleanup on quit
    // =========================================================================

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Clear session to avoid memory leaks; unsaved selections are discarded.
        zoneManager.clearSession(event.getPlayer().getUniqueId());
    }

    // =========================================================================
    // Helper
    // =========================================================================

    /**
     * Determines the corner Location from the interaction event.
     *
     * <ul>
     *   <li>When a block is clicked: returns the top-surface of the clicked
     *       block (blockX, blockY + 1, blockZ) so the selected block is visually
     *       <em>inside</em> the zone boundary.</li>
     *   <li>When air is clicked: returns the player's feet block location
     *       (floor Y), centred on the block column.</li>
     * </ul>
     */
    private Location resolveCornerLocation(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        Player player = event.getPlayer();

        if (clicked != null && event.getBlockFace() != BlockFace.DOWN) {
            // Use the top surface of the clicked block as the corner.
            // Storing Y at the block itself (not +1) keeps the corner on the
            // face the player sees, which is intuitive for a lobby floor zone.
            return new Location(
                    clicked.getWorld(),
                    clicked.getX() + 0.5,
                    clicked.getY(),
                    clicked.getZ() + 0.5);
        }

        // Air click or bottom-face click: use player's feet position.
        Location feet = player.getLocation();
        return new Location(
                feet.getWorld(),
                feet.getBlockX() + 0.5,
                feet.getBlockY(),
                feet.getBlockZ() + 0.5);
    }
}

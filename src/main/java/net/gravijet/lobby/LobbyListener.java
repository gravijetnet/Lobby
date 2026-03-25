package net.gravijet.lobby;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class LobbyListener implements Listener {
    private final Main plugin;

    // Tracks active Ender Butt pearls: player UUID → pearl entity
    private final Map<UUID, EnderPearl> enderButtPearls = new HashMap<>();
    // Tracks the pearl's Y position from the previous tick for skip-through detection
    private final Map<UUID, Double> lastPearlY = new HashMap<>();

    public LobbyListener(Main plugin) {
        this.plugin = plugin;
        // Per-tick task: enforce Ender Butt block penetration checks
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickEnderButt, 1L, 1L);
        // Per-tick task: enforce Y=200 height limit for all non-build-mode players
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickHeightLimit, 1L, 1L);
    }

    private void tickHeightLimit() {
        Location spawn = plugin.getSpawnLocation();
        if (spawn == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getLocation().getY() > 200 && !plugin.isInBuildMode(player)) {
                EnderPearl pearl = enderButtPearls.remove(player.getUniqueId());
                lastPearlY.remove(player.getUniqueId());
                if (pearl != null && !pearl.isDead()) {
                    pearl.eject();
                    pearl.remove();
                }
                player.teleport(spawn);
            }
        }
    }

    private void tickEnderButt() {
        Iterator<Map.Entry<UUID, EnderPearl>> it = enderButtPearls.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, EnderPearl> entry = it.next();
            UUID uuid = entry.getKey();
            EnderPearl pearl = entry.getValue();

            if (pearl.isDead() || !pearl.isValid()) {
                it.remove();
                lastPearlY.remove(uuid);
                continue;
            }

            Location loc = pearl.getLocation();
            double currentY = loc.getY();
            double prevY = lastPearlY.getOrDefault(uuid, currentY);
            lastPearlY.put(uuid, currentY);
            Vector vel = pearl.getVelocity();

            // --- Hard height limit at Y=200 ---
            // A fast pearl can jump from Y=198 to Y=205 in a single tick, so we must
            // teleport it back AND kill the upward velocity rather than just setting velocity.
            if (currentY > 200) {
                Location capped = loc.clone();
                capped.setY(200.0);
                pearl.teleport(capped);
                pearl.setVelocity(new Vector(vel.getX(), Math.min(vel.getY(), 0.0), vel.getZ()));
                lastPearlY.put(uuid, 200.0);
                continue;
            }

            // --- Block penetration check (floors and ceilings) ---
            // In 1.8.8, fast projectiles can skip over horizontal block faces in a single tick.
            // We check every block-Y level the pearl crossed since last tick to catch skipped blocks.
            boolean blocked = false;

            // Direct check: pearl is currently inside a solid block
            if (loc.getBlock().getType().isSolid()) {
                blocked = true;
            }

            // Skip-through check: compare block-Y levels between previous and current position
            if (!blocked) {
                int minBlockY = (int) Math.floor(Math.min(prevY, currentY));
                int maxBlockY = (int) Math.floor(Math.max(prevY, currentY));
                if (minBlockY != maxBlockY) {
                    int bx = loc.getBlockX();
                    int bz = loc.getBlockZ();
                    for (int y = minBlockY; y <= maxBlockY && !blocked; y++) {
                        if (loc.getWorld().getBlockAt(bx, y, bz).getType().isSolid()) {
                            blocked = true;
                        }
                    }
                }
            }

            // Player body check: rider can clip into ceilings when the pearl is just below them.
            // Check feet, eye, AND the top of the hitbox (feetY+1.8) to catch the gap between
            // eye height (feetY+1.62) and the actual top of the player (feetY+1.8).
            if (!blocked) {
                Player rider = Bukkit.getPlayer(uuid);
                if (rider != null && rider.isInsideVehicle()) {
                    if (rider.getLocation().getBlock().getType().isSolid()
                            || rider.getEyeLocation().getBlock().getType().isSolid()
                            || rider.getLocation().clone().add(0, 1.8, 0).getBlock().getType().isSolid()) {
                        blocked = true;
                    }
                }
            }

            if (blocked) {
                it.remove();
                lastPearlY.remove(uuid);
                pearl.eject();
                pearl.remove();
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Clear build mode state without triggering a duplicate inventory setup;
        // setupPlayer handles everything afterwards (game mode, inventory, etc.)
        plugin.clearBuildMode(player);

        // Make all players visible on the tab list (1.8 API: no plugin parameter)
        for (Player online : Bukkit.getOnlinePlayers()) {
            event.getPlayer().showPlayer(online);
        }

        plugin.setupPlayer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Only clean up state — no inventory/game mode reset for a player who is already gone
        plugin.clearBuildMode(player);

        // Remove any active Ender Butt pearl when the player disconnects
        EnderPearl pearl = enderButtPearls.remove(player.getUniqueId());
        lastPearlY.remove(player.getUniqueId());
        if (pearl != null && !pearl.isDead()) {
            pearl.eject();
            pearl.remove();
        }

        // Reset scoreboard on leave
        event.getPlayer().setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        plugin.setupWorld(event.getPlayer().getWorld());
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.isInBuildMode(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!plugin.isInBuildMode(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        Action action = event.getAction();
        Block block = event.getClickedBlock();

        // Block all block interactions when not in build mode
        if (!plugin.isInBuildMode(player) &&
                (action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK) &&
                block != null) {
            event.setCancelled(true);
        }

        if (item == null) return;

        // Prevent lobby items from being consumed by vanilla mechanics
        if (isLobbyItem(item)) {
            event.setCancelled(true);

            // Only trigger actions on right-click
            if (action.toString().contains("RIGHT")) {

                // Server Selector
                if (item.getType() == Material.COMPASS
                        && item.getItemMeta().getDisplayName().equals("§cServer Selector")) {
                    plugin.getServerSelectorManager().openServerSelector(player);
                    return;
                }

                // Ender Butt: launch a pearl and make the player ride it
                // - Player moves with the pearl (riding), no teleport
                // - Height capped at Y=200 via tickEnderButt()
                // - Pearl collides with blocks normally, ejects player on hit
                // - Item stays in the hotbar (never consumed)
                if (item.getType() == Material.ENDER_PEARL
                        && item.getItemMeta().getDisplayName().equals("§cEnder Butt")) {
                    UUID uuid = player.getUniqueId();

                    // Remove previous pearl if the player clicks again while already riding
                    EnderPearl old = enderButtPearls.remove(uuid);
                    lastPearlY.remove(uuid);
                    if (old != null && !old.isDead()) {
                        old.eject();
                        old.remove();
                    }

                    player.playSound(player.getLocation(), Sound.ENDERMAN_TELEPORT, 1.0f, 1.0f);

                    EnderPearl pearl = player.launchProjectile(EnderPearl.class);
                    pearl.setVelocity(player.getLocation().getDirection().multiply(1.5));
                    // Make the player ride the pearl — movement is driven by pearl physics
                    pearl.setPassenger(player);
                    enderButtPearls.put(uuid, pearl);

                    // Force inventory sync next tick to fix 1.8.8 client-side item desync
                    Bukkit.getScheduler().runTask(plugin, player::updateInventory);
                    return;
                }

                // Coinshop
                if (item.getType() == Material.GOLD_INGOT
                        && item.getItemMeta().getDisplayName().equals("§cCoinshop")) {
                    Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("coinshop"));
                    return;
                }

                // Settings
                if (item.getType() == Material.REDSTONE_TORCH_ON
                        && item.getItemMeta().getDisplayName().equals("§cSettings")) {
                    Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("settings"));
                    return;
                }

                // Friends (1.8: SKULL_ITEM instead of PLAYER_HEAD)
                if (item.getType() == Material.SKULL_ITEM
                        && item.getItemMeta().getDisplayName().equals("§cFriends")) {
                    Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("friends menu"));
                    return;
                }

                // Visibility toggle (1.8: INK_SACK instead of *_DYE)
                if (item.getType() == Material.INK_SACK
                        && item.getItemMeta().getDisplayName().contains("visible")) {
                    plugin.cycleVisibilityMode(player);
                    return;
                }
            }
        }

        // Block all other interactions outside of build mode
        if (!plugin.isInBuildMode(player)) {
            event.setCancelled(true);
        }
    }

    /**
     * When an Ender Butt pearl hits a block: eject the player and remove the pearl.
     * The player stays at the impact position naturally since they were riding the pearl.
     */
    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof EnderPearl)) return;
        EnderPearl pearl = (EnderPearl) event.getEntity();

        UUID shooterUUID = null;
        for (Map.Entry<UUID, EnderPearl> entry : enderButtPearls.entrySet()) {
            if (entry.getValue().getEntityId() == pearl.getEntityId()) {
                shooterUUID = entry.getKey();
                break;
            }
        }

        if (shooterUUID == null) return;

        enderButtPearls.remove(shooterUUID);
        pearl.eject();
        pearl.remove();
    }

    /**
     * Cancel all ender pearl teleports in the lobby.
     * The Ender Butt moves the player by riding, not by teleporting.
     */
    @EventHandler
    public void onEnderPearlTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        // Prevent item movement in the Server Selector GUI
        if (event.getView().getTitle().contains("Server Selector")) {
            event.setCancelled(true);
            if (event.getClickedInventory() != null &&
                    event.getClickedInventory().equals(event.getView().getTopInventory())) {
                plugin.getServerSelectorManager().handleMenuClick(player, event.getSlot());
            }
            return;
        }

        // Prevent lobby items from being moved in the hotbar
        if (event.getCurrentItem() != null &&
                event.getClickedInventory() != null &&
                event.getClickedInventory().equals(player.getInventory())) {
            if (isLobbyItem(event.getCurrentItem())) {
                event.setCancelled(true);
                return;
            }
        }

        // Block all inventory interactions outside of build mode
        // (no offhand slot 40 check — offhand does not exist in 1.8)
        if (!plugin.isInBuildMode(player)) {
            event.setCancelled(true);
        }
    }

    private boolean isLobbyItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        String name = item.getItemMeta().getDisplayName();
        return name.equals("§cServer Selector") ||
                name.equals("§cEnder Butt") ||
                name.equals("§cCoinshop") ||
                name.equals("§cSettings") ||
                name.equals("§cFriends") ||
                name.contains("visible");
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.isInBuildMode(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // 1.8 API: PlayerPickupItemEvent instead of EntityPickupItemEvent
    @EventHandler
    public void onPickup(PlayerPickupItemEvent event) {
        if (!plugin.isInBuildMode(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
    // PlayerSwapHandItemsEvent does not exist in 1.8 (no offhand) — omitted

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onFoodChange(FoodLevelChangeEvent event) {
        event.setCancelled(true);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Location spawn = plugin.getSpawnLocation();
        if (spawn != null) {
            event.setRespawnLocation(spawn);
        }
        // Run setupPlayer on the next tick so the respawn process has fully completed
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.setupPlayer(event.getPlayer()), 1L);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        // getTo() can be null on head-only rotation
        if (event.getTo() == null) return;

        // Only check on actual block position change (performance optimization)
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();

        // Teleport to spawn if below Y=30 and not in build mode
        if (event.getTo().getY() < 30 && !plugin.isInBuildMode(player)) {
            Location spawn = plugin.getSpawnLocation();
            if (spawn != null) {
                event.setCancelled(true);
                player.teleport(spawn);
            }
        }
    }
}

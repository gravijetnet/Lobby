package net.gravijet.lobby.listener;

import net.gravijet.lobby.Main;
import net.gravijet.lobby.zone.ZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class LobbyListener implements Listener {

    private final Main        plugin;
    private final ZoneManager zoneManager;

    // Ender Butt: active pearls and previous Y for skip-through detection
    private final Map<UUID, EnderPearl> enderButtPearls = new HashMap<>();
    private final Map<UUID, Double>     lastPearlY      = new HashMap<>();

    // Lobby block lifecycle: location → {task1 (sandstone→redstone), task2 (redstone→air)}
    private final Map<Location, int[]> lobbyBlockTasks = new HashMap<>();

    public LobbyListener(Main plugin, ZoneManager zoneManager) {
        this.plugin      = plugin;
        this.zoneManager = zoneManager;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickEnderButt,   1L, 1L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickHeightLimit, 1L, 1L);
    }

    // =========================================================================
    // Height limit — backup tick (covers hovering / standing-still edge case)
    // =========================================================================

    private void tickHeightLimit() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getLocation().getY() <= 200) continue;
            if (plugin.isInBuildMode(player)) continue;

            ejectAndCancelPearl(player);
            Location spawn = plugin.getSpawnLocation();
            if (spawn != null) {
                player.teleport(spawn);
            } else {
                Location loc = player.getLocation();
                loc.setY(200);
                player.teleport(loc);
            }
        }
    }

    // =========================================================================
    // Ender Butt — per-tick physics
    // =========================================================================

    private void tickEnderButt() {
        Iterator<Map.Entry<UUID, EnderPearl>> it = enderButtPearls.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, EnderPearl> entry = it.next();
            UUID       uuid  = entry.getKey();
            EnderPearl pearl = entry.getValue();

            if (pearl.isDead() || !pearl.isValid()) {
                it.remove();
                lastPearlY.remove(uuid);
                continue;
            }

            Location loc      = pearl.getLocation();
            double   currentY = loc.getY();
            double   prevY    = lastPearlY.getOrDefault(uuid, currentY);
            lastPearlY.put(uuid, currentY);
            Vector vel = pearl.getVelocity();

            // Hard ceiling at Y=200 — fast pearls can skip multiple blocks per tick,
            // so we teleport back AND kill upward velocity.
            if (currentY > 200) {
                Location capped = loc.clone();
                capped.setY(200.0);
                pearl.teleport(capped);
                pearl.setVelocity(new Vector(vel.getX(), Math.min(vel.getY(), 0.0), vel.getZ()));
                lastPearlY.put(uuid, 200.0);
                continue;
            }

            // Block penetration: scan every block-Y crossed since the last tick.
            boolean blocked = false;

            if (loc.getBlock().getType().isSolid()) {
                blocked = true;
            }
            if (!blocked) {
                int minY = (int) Math.floor(Math.min(prevY, currentY));
                int maxY = (int) Math.floor(Math.max(prevY, currentY));
                if (minY != maxY) {
                    int bx = loc.getBlockX(), bz = loc.getBlockZ();
                    for (int y = minY; y <= maxY && !blocked; y++) {
                        if (loc.getWorld().getBlockAt(bx, y, bz).getType().isSolid()) blocked = true;
                    }
                }
            }
            // Player body: rider can clip ceilings between pearl and hitbox top.
            if (!blocked) {
                Player rider = Bukkit.getPlayer(uuid);
                if (rider != null && rider.isInsideVehicle()) {
                    Location feet = rider.getLocation();
                    if (feet.getBlock().getType().isSolid()
                            || rider.getEyeLocation().getBlock().getType().isSolid()
                            || feet.clone().add(0, 1.8, 0).getBlock().getType().isSolid()) {
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

    // =========================================================================
    // Join / quit / world change
    // =========================================================================

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.clearBuildMode(player);
        for (Player online : Bukkit.getOnlinePlayers()) {
            player.showPlayer(online); // 1.8: no plugin parameter
        }
        plugin.setupPlayer(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.clearBuildMode(player);

        EnderPearl pearl = enderButtPearls.remove(player.getUniqueId());
        lastPearlY.remove(player.getUniqueId());
        if (pearl != null && !pearl.isDead()) {
            pearl.eject();
            pearl.remove();
        }

        player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        plugin.setupWorld(event.getPlayer().getWorld());
    }

    // =========================================================================
    // Block break — only build-mode players may break blocks
    // =========================================================================

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.isInBuildMode(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // =========================================================================
    // Block place — build-mode players may place anything;
    // non-build-mode players may only place lobby blocks outside of zones
    // =========================================================================

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (plugin.isInBuildMode(player)) return;

        ItemStack item = event.getItemInHand();
        if (isLobbyBlock(item)) {
            Block placed = event.getBlockPlaced();

            // Zone containment check — replaces the old 15-block radius.
            if (zoneManager.isInsideAnyZone(placed.getLocation())) {
                event.setCancelled(true);
                player.sendMessage("§cYou cannot place blocks at the spawn");
                return;
            }

            // Allow placement, schedule lifecycle, restore infinite stack.
            scheduleLobbyBlock(placed.getLocation());
            Bukkit.getScheduler().runTask(plugin, () -> {
                ItemStack slot4 = player.getInventory().getItem(4);
                if (slot4 != null && isLobbyBlock(slot4)) {
                    slot4.setAmount(64);
                } else {
                    ItemStack fresh = new ItemStack(Material.SANDSTONE, 64);
                    ItemMeta meta = fresh.getItemMeta();
                    meta.setDisplayName("§cLobby Blocks");
                    fresh.setItemMeta(meta);
                    player.getInventory().setItem(4, fresh);
                }
                player.updateInventory();
            });
            return;
        }

        event.setCancelled(true);
    }

    // =========================================================================
    // Interactions — lobby blocks, lobby items, general restrictions
    // =========================================================================

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player    player  = event.getPlayer();
        ItemStack item    = event.getItem();
        Action    action  = event.getAction();
        Block     clicked = event.getClickedBlock();

        // Build-mode players: no restrictions
        if (plugin.isInBuildMode(player)) return;

        // Zone Wand: handled entirely by ZoneListener — don't interfere.
        if (ZoneManager.isWand(item) && player.hasPermission("lobby.zone")) return;

        // ── Lobby block placement ─────────────────────────────────────────────
        if (item != null && isLobbyBlock(item) && action == Action.RIGHT_CLICK_BLOCK) {
            return; // vanilla placement + BlockPlaceEvent takes over
        }

        // ── Cancel block interactions (right / left click on a block) ─────────
        if ((action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK)
                && clicked != null) {
            event.setCancelled(true);
        }

        if (item == null) return;

        // ── Lobby item right-click actions ────────────────────────────────────
        if (isLobbyItem(item)) {
            event.setCancelled(true);

            if (!action.toString().contains("RIGHT")) return;

            // Server Selector
            if (item.getType() == Material.COMPASS && nameEquals(item, "§cServer Selector")) {
                plugin.getServerSelectorManager().openServerSelector(player);
                return;
            }

            // Ender Butt
            if (item.getType() == Material.ENDER_PEARL && nameEquals(item, "§cEnder Butt")) {
                UUID uuid = player.getUniqueId();
                EnderPearl old = enderButtPearls.remove(uuid);
                lastPearlY.remove(uuid);
                if (old != null && !old.isDead()) {
                    old.eject();
                    old.remove();
                }
                player.playSound(player.getLocation(), Sound.ENDERMAN_TELEPORT, 1.0f, 1.0f);
                EnderPearl pearl = player.launchProjectile(EnderPearl.class);
                pearl.setVelocity(player.getLocation().getDirection().multiply(1.5));
                pearl.setPassenger(player);
                enderButtPearls.put(uuid, pearl);
                Bukkit.getScheduler().runTask(plugin, player::updateInventory);
                return;
            }

            // Coinshop
            if (item.getType() == Material.GOLD_INGOT && nameEquals(item, "§cCoinshop")) {
                Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("coinshop"));
                return;
            }

            // Settings
            if (item.getType() == Material.REDSTONE_TORCH_ON && nameEquals(item, "§cSettings")) {
                Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("settings"));
                return;
            }

            // Friends (1.8: SKULL_ITEM)
            if (item.getType() == Material.SKULL_ITEM && nameEquals(item, "§cFriends")) {
                Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("friends menu"));
                return;
            }

            // Visibility toggle (1.8: INK_SACK)
            if (item.getType() == Material.INK_SACK
                    && item.hasItemMeta()
                    && item.getItemMeta().getDisplayName().contains("visible")) {
                plugin.cycleVisibilityMode(player);
                return;
            }

            return;
        }

        // Cancel all remaining interactions for non-build-mode players
        event.setCancelled(true);
    }

    // =========================================================================
    // Ender Butt projectile hit
    // =========================================================================

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (!(event.getEntity() instanceof EnderPearl)) return;
        EnderPearl pearl = (EnderPearl) event.getEntity();

        UUID uuid = null;
        for (Map.Entry<UUID, EnderPearl> e : enderButtPearls.entrySet()) {
            if (e.getValue().getEntityId() == pearl.getEntityId()) {
                uuid = e.getKey();
                break;
            }
        }
        if (uuid == null) return;

        enderButtPearls.remove(uuid);
        lastPearlY.remove(uuid);
        pearl.eject();
        pearl.remove();
    }

    /** Cancel the vanilla ender pearl teleport — movement is done by riding. */
    @EventHandler
    public void onEnderPearlTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) {
            event.setCancelled(true);
        }
    }

    // =========================================================================
    // Inventory click
    // =========================================================================

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();

        if (event.getView().getTitle().contains("Server Selector")) {
            event.setCancelled(true);
            if (event.getClickedInventory() != null
                    && event.getClickedInventory().equals(event.getView().getTopInventory())) {
                plugin.getServerSelectorManager().handleMenuClick(player, event.getSlot());
            }
            return;
        }

        if (!plugin.isInBuildMode(player)) {
            event.setCancelled(true);
        }
    }

    // =========================================================================
    // Drop / pickup
    // =========================================================================

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.isInBuildMode(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onPickup(PlayerPickupItemEvent event) { // 1.8 API
        if (!plugin.isInBuildMode(event.getPlayer())) event.setCancelled(true);
    }

    // =========================================================================
    // Damage / food / respawn
    // =========================================================================

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) event.setCancelled(true);
    }

    @EventHandler
    public void onFoodChange(FoodLevelChangeEvent event) {
        event.setCancelled(true);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Location spawn = plugin.getSpawnLocation();
        if (spawn != null) event.setRespawnLocation(spawn);
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.setupPlayer(event.getPlayer()), 1L);
    }

    // =========================================================================
    // Player movement — height + lower bounds
    // =========================================================================

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;

        Player   player = event.getPlayer();
        Location to     = event.getTo();

        // Height limit: above Y=200 → teleport to spawn (primary check)
        if (to.getY() > 200 && !plugin.isInBuildMode(player)) {
            ejectAndCancelPearl(player);
            event.setCancelled(true);
            Location spawn = plugin.getSpawnLocation();
            if (spawn != null) player.teleport(spawn);
            return;
        }

        // Skip further checks when only head rotation changed
        if (event.getFrom().getBlockX() == to.getBlockX()
                && event.getFrom().getBlockY() == to.getBlockY()
                && event.getFrom().getBlockZ() == to.getBlockZ()) {
            return;
        }

        // Lower limit: below Y=-50 → teleport to spawn
        if (to.getY() < -50 && !plugin.isInBuildMode(player)) {
            Location spawn = plugin.getSpawnLocation();
            if (spawn != null) {
                event.setCancelled(true);
                player.teleport(spawn);
            }
        }
    }

    // =========================================================================
    // Lobby block lifecycle
    // =========================================================================

    /**
     * Schedules the two-phase lifecycle of a placed lobby block:
     *   0 s  → sandstone (just placed)
     *   4 s  → redstone block
     *   7 s  → removed (air)
     *
     * If the same location is placed again the old timers are reset.
     */
    private void scheduleLobbyBlock(Location loc) {
        int[] existing = lobbyBlockTasks.remove(loc);
        if (existing != null) {
            Bukkit.getScheduler().cancelTask(existing[0]);
            Bukkit.getScheduler().cancelTask(existing[1]);
        }

        int task1 = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (loc.getBlock().getType() == Material.SANDSTONE) {
                loc.getBlock().setType(Material.REDSTONE_BLOCK);
            }
        }, 80L).getTaskId(); // 4 s

        int task2 = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            lobbyBlockTasks.remove(loc);
            if (loc.getBlock().getType() == Material.REDSTONE_BLOCK) {
                loc.getBlock().setType(Material.AIR);
            }
        }, 140L).getTaskId(); // 7 s

        lobbyBlockTasks.put(loc, new int[]{task1, task2});
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Ejects the player from any vehicle and removes their active Ender Butt pearl. */
    private void ejectAndCancelPearl(Player player) {
        if (player.isInsideVehicle()) {
            player.getVehicle().eject();
        }
        EnderPearl pearl = enderButtPearls.remove(player.getUniqueId());
        lastPearlY.remove(player.getUniqueId());
        if (pearl != null && !pearl.isDead()) {
            pearl.eject();
            pearl.remove();
        }
    }

    /** True if the item is the sandstone lobby block given in slot 4. */
    private boolean isLobbyBlock(ItemStack item) {
        return item != null
                && item.getType() == Material.SANDSTONE
                && item.hasItemMeta()
                && "§cLobby Blocks".equals(item.getItemMeta().getDisplayName());
    }

    /**
     * True for the fixed hotbar items managed by the lobby.
     * Lobby blocks are intentionally excluded — they are handled separately
     * and must not be caught by the lobby-item cancel.
     */
    private boolean isLobbyItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        String n = item.getItemMeta().getDisplayName();
        return "§cServer Selector".equals(n)
                || "§cEnder Butt".equals(n)
                || "§cCoinshop".equals(n)
                || "§cSettings".equals(n)
                || "§cFriends".equals(n)
                || n.contains("visible");
    }

    /** Null-safe display-name equality check. */
    private boolean nameEquals(ItemStack item, String expected) {
        return item.hasItemMeta() && expected.equals(item.getItemMeta().getDisplayName());
    }
}

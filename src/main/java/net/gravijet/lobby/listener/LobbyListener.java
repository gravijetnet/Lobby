package net.gravijet.lobby.listener;

import net.gravijet.lobby.Main;
import net.gravijet.lobby.zone.Zone;
import net.gravijet.lobby.zone.ZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
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

    /**
     * Cooldown for "zone denied" messages so a player isn't spammed.
     * Maps player UUID → System.currentTimeMillis() of last deny message.
     * Throttle: 1 500 ms between messages per player.
     */
    private final Map<UUID, Long> denyMessageCooldown = new HashMap<>();

    private static final long DENY_MESSAGE_COOLDOWN_MS = 1_500L;

    public LobbyListener(Main plugin, ZoneManager zoneManager) {
        this.plugin      = plugin;
        this.zoneManager = zoneManager;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickEnderButt,    1L,  1L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickHeightLimit,  1L,  4L);
        // Backup access check: catches players who got inside a restricted zone
        // via teleportation, spawn, or any means other than walking.
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickAccessCheck, 20L, 10L);
    }

    // =========================================================================
    // Height limit — backup tick
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
    // VIP-zone access check — backup tick (every 10 ticks = 0.5 s)
    // =========================================================================

    /**
     * Catches players who are standing inside a restricted zone without the
     * required permission (e.g. after a teleport, or on first join).
     * Teleports them immediately to spawn and sends the zone's deny message.
     */
    private void tickAccessCheck() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.isInBuildMode(player)) continue;

            Zone restricted = zoneManager.getRestrictedZoneAt(player, player.getLocation());
            if (restricted == null) continue;

            // Send deny message with throttle so we don't spam on every tick.
            sendDenyMessage(player, restricted);

            Location spawn = plugin.getSpawnLocation();
            if (spawn != null) {
                player.teleport(spawn);
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

            // Hard ceiling at Y=200
            if (currentY > 200) {
                Location capped = loc.clone();
                capped.setY(200.0);
                pearl.teleport(capped);
                pearl.setVelocity(new Vector(vel.getX(), Math.min(vel.getY(), 0.0), vel.getZ()));
                lastPearlY.put(uuid, 200.0);
                continue;
            }

            // Block penetration scan
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
            // Player body ceiling clip
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

        // Always start in Survival — clear any previous build mode first.
        plugin.clearBuildMode(player);
        player.setGameMode(GameMode.SURVIVAL);

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

        denyMessageCooldown.remove(player.getUniqueId());
        player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        plugin.setupWorld(event.getPlayer().getWorld());
    }

    // =========================================================================
    // Block break
    // =========================================================================

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.isInBuildMode(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // =========================================================================
    // Block place
    // =========================================================================

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (plugin.isInBuildMode(player)) return;

        ItemStack item = event.getItemInHand();
        if (isLobbyBlock(item)) {
            Block placed = event.getBlockPlaced();

            if (zoneManager.isInsideAnyZone(placed.getLocation())) {
                event.setCancelled(true);
                player.sendMessage("§cYou can't place blocks here!");
                return;
            }

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
    // Interactions
    // =========================================================================

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player    player  = event.getPlayer();
        ItemStack item    = event.getItem();
        Action    action  = event.getAction();
        Block     clicked = event.getClickedBlock();

        if (plugin.isInBuildMode(player)) return;

        // Zone Wand is handled entirely by ZoneListener.
        if (ZoneManager.isWand(item) && player.hasPermission("lobby.zone")) return;

        // Let vanilla handle lobby-block placement; BlockPlaceEvent takes over.
        if (item != null && isLobbyBlock(item) && action == Action.RIGHT_CLICK_BLOCK) return;

        if ((action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK)
                && clicked != null) {
            event.setCancelled(true);
            event.setUseInteractedBlock(Event.Result.DENY);
        }

        if (item == null) return;

        if (isLobbyItem(item)) {
            event.setCancelled(true);
            if (!action.toString().contains("RIGHT")) return;

            if (item.getType() == Material.COMPASS && nameEquals(item, "§cServer Selector")) {
                plugin.getServerSelectorManager().openServerSelector(player);
                return;
            }
            if (item.getType() == Material.ENDER_PEARL && nameEquals(item, "§cEnder Butt")) {
                UUID uuid = player.getUniqueId();
                EnderPearl old = enderButtPearls.remove(uuid);
                lastPearlY.remove(uuid);
                if (old != null && !old.isDead()) { old.eject(); old.remove(); }
                player.playSound(player.getLocation(), Sound.ENDERMAN_TELEPORT, 1.0f, 1.0f);
                EnderPearl pearl = player.launchProjectile(EnderPearl.class);
                pearl.setVelocity(player.getLocation().getDirection().multiply(1.5));
                pearl.setPassenger(player);
                enderButtPearls.put(uuid, pearl);
                Bukkit.getScheduler().runTask(plugin, player::updateInventory);
                return;
            }
            if (item.getType() == Material.GOLD_INGOT && nameEquals(item, "§cCoinshop")) {
                Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("coinshop"));
                return;
            }
            if (item.getType() == Material.REDSTONE_TORCH_ON && nameEquals(item, "§cSettings")) {
                Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("settings"));
                return;
            }
            if (item.getType() == Material.SKULL_ITEM && nameEquals(item, "§cFriends")) {
                Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("friends menu"));
                return;
            }
            if (item.getType() == Material.INK_SACK
                    && item.hasItemMeta()
                    && item.getItemMeta().getDisplayName().contains("visible")) {
                plugin.cycleVisibilityMode(player);
                return;
            }
            return;
        }

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
            if (e.getValue().getEntityId() == pearl.getEntityId()) { uuid = e.getKey(); break; }
        }
        if (uuid == null) return;

        enderButtPearls.remove(uuid);
        lastPearlY.remove(uuid);
        pearl.eject();
        pearl.remove();
    }

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

        if (!plugin.isInBuildMode(player)) event.setCancelled(true);
    }

    // =========================================================================
    // Drop / pickup
    // =========================================================================

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.isInBuildMode(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onPickup(PlayerPickupItemEvent event) {
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
    // Player movement — height, lower bounds, VIP-zone access
    // =========================================================================

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;

        Player   player = event.getPlayer();
        Location from   = event.getFrom();
        Location to     = event.getTo();

        // ── 1. Height ceiling ─────────────────────────────────────────────────
        if (to.getY() > 200 && !plugin.isInBuildMode(player)) {
            ejectAndCancelPearl(player);
            event.setCancelled(true);
            Location spawn = plugin.getSpawnLocation();
            if (spawn != null) player.teleport(spawn);
            return;
        }

        // ── skip if only head rotation changed ────────────────────────────────
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        // ── 2. Lower floor ────────────────────────────────────────────────────
        if (to.getY() < -50 && !plugin.isInBuildMode(player)) {
            event.setCancelled(true);
            Location spawn = plugin.getSpawnLocation();
            if (spawn != null) player.teleport(spawn);
            return;
        }

        // ── 3. VIP-zone border check ──────────────────────────────────────────
        // Only runs when the player physically moved to a new block.
        if (plugin.isInBuildMode(player)) return;

        Zone restricted = zoneManager.getRestrictedZoneAt(player, to);
        if (restricted == null) return;

        // Cancel movement: player is rubber-banded back to `from`.
        event.setCancelled(true);

        // Apply knockback so the player is visibly "schleudert" away from the border.
        // Direction: reverse of attempted movement, with a small upward pop.
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double hLen = Math.sqrt(dx * dx + dz * dz);

        Vector knockback;
        if (hLen > 1e-6) {
            knockback = new Vector(-dx / hLen * 0.55, 0.22, -dz / hLen * 0.55);
        } else {
            // Player was not moving horizontally; push them away from zone centre.
            // Compute zone AABB centroid for a rough direction.
            double cx = 0, cz = 0;
            for (int[] c : restricted.getCorners()) { cx += c[0]; cz += c[1]; }
            cx /= restricted.getCorners().size();
            cz /= restricted.getCorners().size();
            double awayX = from.getX() - cx;
            double awayZ = from.getZ() - cz;
            double awayLen = Math.sqrt(awayX * awayX + awayZ * awayZ);
            if (awayLen > 1e-6) {
                knockback = new Vector(awayX / awayLen * 0.55, 0.22, awayZ / awayLen * 0.55);
            } else {
                knockback = new Vector(0, 0.3, 0);
            }
        }
        player.setVelocity(knockback);

        // Throttled deny message (max once per 1.5 s per player).
        sendDenyMessage(player, restricted);
    }

    // =========================================================================
    // Lobby block lifecycle
    // =========================================================================

    private void scheduleLobbyBlock(Location loc) {
        int[] existing = lobbyBlockTasks.remove(loc);
        if (existing != null) {
            Bukkit.getScheduler().cancelTask(existing[0]);
            Bukkit.getScheduler().cancelTask(existing[1]);
        }

        int task1 = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (loc.getBlock().getType() == Material.SANDSTONE)
                loc.getBlock().setType(Material.REDSTONE_BLOCK);
        }, 80L).getTaskId();

        int task2 = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            lobbyBlockTasks.remove(loc);
            if (loc.getBlock().getType() == Material.REDSTONE_BLOCK)
                loc.getBlock().setType(Material.AIR);
        }, 140L).getTaskId();

        lobbyBlockTasks.put(loc, new int[]{ task1, task2 });
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void ejectAndCancelPearl(Player player) {
        if (player.isInsideVehicle()) player.getVehicle().eject();
        EnderPearl pearl = enderButtPearls.remove(player.getUniqueId());
        lastPearlY.remove(player.getUniqueId());
        if (pearl != null && !pearl.isDead()) { pearl.eject(); pearl.remove(); }
    }

    /**
     * Sends the zone's deny message to the player, but at most once per
     * {@value #DENY_MESSAGE_COOLDOWN_MS} ms to prevent chat spam.
     * Multi-line messages (split on '\n') are sent as separate chat lines.
     */
    private void sendDenyMessage(Player player, Zone zone) {
        long now  = System.currentTimeMillis();
        Long last = denyMessageCooldown.get(player.getUniqueId());
        if (last != null && now - last < DENY_MESSAGE_COOLDOWN_MS) return;
        denyMessageCooldown.put(player.getUniqueId(), now);
        for (String line : zone.getDenyMessage().split("\n", -1)) {
            player.sendMessage(line);
        }
    }

    private boolean isLobbyBlock(ItemStack item) {
        return item != null
                && item.getType() == Material.SANDSTONE
                && item.hasItemMeta()
                && "§cLobby Blocks".equals(item.getItemMeta().getDisplayName());
    }

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

    private boolean nameEquals(ItemStack item, String expected) {
        return item.hasItemMeta() && expected.equals(item.getItemMeta().getDisplayName());
    }
}

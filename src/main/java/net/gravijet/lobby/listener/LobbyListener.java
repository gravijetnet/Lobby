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
import org.bukkit.entity.ItemFrame;
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
import org.bukkit.event.player.PlayerInteractEntityEvent;
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

    private final Map<UUID, EnderPearl> enderButtPearls     = new HashMap<>();
    private final Map<UUID, Location>   lastPearlLoc        = new HashMap<>();
    private final Map<Location, int[]>  lobbyBlockTasks     = new HashMap<>();
    private final Map<UUID, Long>       denyMessageCooldown = new HashMap<>();

    private static final long DENY_MESSAGE_COOLDOWN_MS = 1_500L;

    public LobbyListener(Main plugin, ZoneManager zoneManager) {
        this.plugin      = plugin;
        this.zoneManager = zoneManager;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickEnderButt,   1L,  1L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickHeightLimit, 1L,  4L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickAccessCheck, 20L, 10L);
    }

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

    private void tickAccessCheck() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (plugin.isInBuildMode(player)) continue;
            if (player.hasPermission("lobby.zone")) continue;
            Zone denied = zoneManager.getDeniedZoneAt(player, player.getLocation());
            if (denied == null) continue;
            sendDenyMessage(player, denied);
            Location spawn = plugin.getSpawnLocation();
            if (spawn != null) player.teleport(spawn);
        }
    }

    private void tickEnderButt() {
        Iterator<Map.Entry<UUID, EnderPearl>> it = enderButtPearls.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, EnderPearl> entry = it.next();
            UUID       uuid  = entry.getKey();
            EnderPearl pearl = entry.getValue();
            if (pearl.isDead() || !pearl.isValid()) {
                it.remove();
                lastPearlLoc.remove(uuid);
                continue;
            }
            Location loc = pearl.getLocation();
            lastPearlLoc.put(uuid, loc.clone());
            if (loc.getY() > 200) {
                Location capped = loc.clone();
                capped.setY(200.0);
                pearl.teleport(capped);
                Vector vel = pearl.getVelocity();
                pearl.setVelocity(new Vector(vel.getX(), Math.min(vel.getY(), 0.0), vel.getZ()));
                lastPearlLoc.put(uuid, capped.clone());
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        plugin.clearBuildMode(player);
        player.setGameMode(GameMode.SURVIVAL);
        for (Player online : Bukkit.getOnlinePlayers()) player.showPlayer(online);
        plugin.setupPlayer(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player     player = event.getPlayer();
        EnderPearl pearl  = enderButtPearls.remove(player.getUniqueId());
        lastPearlLoc.remove(player.getUniqueId());
        if (pearl != null && !pearl.isDead()) { pearl.eject(); pearl.remove(); }
        plugin.clearBuildMode(player);
        denyMessageCooldown.remove(player.getUniqueId());
        player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        plugin.setupWorld(event.getPlayer().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.isInBuildMode(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (plugin.isInBuildMode(player)) {
            event.setCancelled(false);
            return;
        }
        ItemStack item   = event.getItemInHand();
        Block     placed = event.getBlockPlaced();
        if (isLobbyBlock(item)) {
            Zone zone = zoneManager.getZoneAt(placed.getLocation());
            if (zone != null && !zone.isAllowBlockPlacement()) {
                event.setCancelled(true);
                return;
            }
            event.setCancelled(false);
            scheduleLobbyBlock(placed.getLocation());
            Bukkit.getScheduler().runTask(plugin, () -> {
                ItemStack slot4 = player.getInventory().getItem(4);
                if (slot4 != null && isLobbyBlock(slot4)) {
                    slot4.setAmount(64);
                } else {
                    ItemStack fresh = new ItemStack(Material.SANDSTONE, 64);
                    ItemMeta  meta  = fresh.getItemMeta();
                    meta.setDisplayName("§cLobby Blocks");
                    fresh.setItemMeta(meta);
                    player.getInventory().setItem(4, fresh);
                }
                player.updateInventory();
            });
        } else {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Player    player = event.getPlayer();
        ItemStack item   = event.getItem();
        Action    action = event.getAction();

        if (plugin.isInBuildMode(player)) return;

        if (ZoneManager.isWand(item) && player.hasPermission("lobby.zone")) return;

        if (item != null && isLobbyItem(item)) {
            event.setCancelled(true);
            if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
                handleLobbyItemUse(player, item);
            }
            return;
        }

        if (action == Action.RIGHT_CLICK_BLOCK && item != null && isLobbyBlock(item)) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.ALLOW);
            return;
        }

        if (SpeedCookieListener.isSpeedCookie(item)
                && (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)) {
            return;
        }

        event.setCancelled(true);
    }

    @EventHandler
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof ItemFrame && !plugin.isInBuildMode(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

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
        lastPearlLoc.remove(uuid);
        pearl.eject();
        pearl.remove();
    }

    @EventHandler
    public void onEnderPearlTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL) event.setCancelled(true);
    }

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
        if (event.getView().getTitle().equals("§bSpeed Cookie")) {
            event.setCancelled(true);
            return;
        }
        if (!plugin.isInBuildMode(player)) event.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.isInBuildMode(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onPickup(PlayerPickupItemEvent event) {
        if (!plugin.isInBuildMode(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) event.setCancelled(true);
    }

    @EventHandler
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player) event.setCancelled(true);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Location spawn = plugin.getSpawnLocation();
        if (spawn != null) event.setRespawnLocation(spawn);
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.setupPlayer(event.getPlayer()), 1L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        Player   player = event.getPlayer();
        Location from   = event.getFrom();
        Location to     = event.getTo();

        if (to.getY() > 200 && !plugin.isInBuildMode(player)) {
            ejectAndCancelPearl(player);
            event.setCancelled(true);
            Location spawn = plugin.getSpawnLocation();
            if (spawn != null) player.teleport(spawn);
            return;
        }

        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        if (to.getY() < -50 && !plugin.isInBuildMode(player)) {
            event.setCancelled(true);
            Location spawn = plugin.getSpawnLocation();
            if (spawn != null) player.teleport(spawn);
            return;
        }

        if (plugin.isInBuildMode(player) || player.hasPermission("lobby.zone")) return;

        Zone denied = zoneManager.getDeniedZoneAt(player, to);
        if (denied == null) return;

        event.setCancelled(true);

        double dx   = to.getX() - from.getX();
        double dz   = to.getZ() - from.getZ();
        double hLen = Math.sqrt(dx * dx + dz * dz);
        Vector knockback;
        if (hLen > 1e-6) {
            knockback = new Vector(-dx / hLen * 0.55, 0.22, -dz / hLen * 0.55);
        } else {
            double cx = 0, cz = 0;
            for (int[] c : denied.getCorners()) { cx += c[0]; cz += c[1]; }
            cx /= denied.getCorners().size();
            cz /= denied.getCorners().size();
            double awayX   = from.getX() - cx;
            double awayZ   = from.getZ() - cz;
            double awayLen = Math.sqrt(awayX * awayX + awayZ * awayZ);
            knockback = awayLen > 1e-6
                    ? new Vector(awayX / awayLen * 0.55, 0.22, awayZ / awayLen * 0.55)
                    : new Vector(0, 0.3, 0);
        }
        player.setVelocity(knockback);
        sendDenyMessage(player, denied);
    }

    private void handleLobbyItemUse(Player player, ItemStack item) {
        if (item.getType() == Material.COMPASS && nameEquals(item, "§cServer Selector")) {
            plugin.getServerSelectorManager().openServerSelector(player);
        } else if (item.getType() == Material.ENDER_PEARL && nameEquals(item, "§cEnder Butt")) {
            UUID uuid = player.getUniqueId();
            EnderPearl old = enderButtPearls.remove(uuid);
            lastPearlLoc.remove(uuid);
            if (old != null && !old.isDead()) { old.eject(); old.remove(); }
            player.playSound(player.getLocation(), Sound.ENDERMAN_TELEPORT, 1.0f, 1.0f);
            EnderPearl pearl = player.launchProjectile(EnderPearl.class);
            pearl.setVelocity(player.getLocation().getDirection().multiply(1.5));
            pearl.setPassenger(player);
            enderButtPearls.put(uuid, pearl);
            lastPearlLoc.put(uuid, pearl.getLocation().clone());
            Bukkit.getScheduler().runTask(plugin, player::updateInventory);
        } else if (item.getType() == Material.GOLD_INGOT && nameEquals(item, "§cCoinshop")) {
            Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("coinshop"));
        } else if (item.getType() == Material.REDSTONE_TORCH_ON && nameEquals(item, "§cSettings")) {
            Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("settings"));
        } else if (item.getType() == Material.SKULL_ITEM && nameEquals(item, "§cFriends")) {
            Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("friends menu"));
        } else if (item.getType() == Material.INK_SACK && item.hasItemMeta()
                && item.getItemMeta().getDisplayName().contains("visible")) {
            plugin.cycleVisibilityMode(player);
        }
    }

    private void scheduleLobbyBlock(Location loc) {
        int[] existing = lobbyBlockTasks.remove(loc);
        if (existing != null) {
            Bukkit.getScheduler().cancelTask(existing[0]);
            Bukkit.getScheduler().cancelTask(existing[1]);
        }
        int t1 = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (loc.getBlock().getType() == Material.SANDSTONE)
                loc.getBlock().setType(Material.REDSTONE_BLOCK);
        }, 80L).getTaskId();
        int t2 = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            lobbyBlockTasks.remove(loc);
            if (loc.getBlock().getType() == Material.REDSTONE_BLOCK)
                loc.getBlock().setType(Material.AIR);
        }, 140L).getTaskId();
        lobbyBlockTasks.put(loc, new int[]{ t1, t2 });
    }

    private void ejectAndCancelPearl(Player player) {
        if (player.isInsideVehicle()) player.getVehicle().eject();
        EnderPearl pearl = enderButtPearls.remove(player.getUniqueId());
        lastPearlLoc.remove(player.getUniqueId());
        if (pearl != null && !pearl.isDead()) { pearl.eject(); pearl.remove(); }
    }

    private void sendDenyMessage(Player player, Zone zone) {
        long now  = System.currentTimeMillis();
        Long last = denyMessageCooldown.get(player.getUniqueId());
        if (last != null && now - last < DENY_MESSAGE_COOLDOWN_MS) return;
        denyMessageCooldown.put(player.getUniqueId(), now);
        for (String line : zone.getDenyMessage().split("\n", -1)) player.sendMessage(line);
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

    private boolean nameEquals(ItemStack item, String name) {
        return item.hasItemMeta() && name.equals(item.getItemMeta().getDisplayName());
    }
}

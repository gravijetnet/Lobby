package net.gravijet.lobby.listener;

import net.gravijet.lobby.LobbyBlockManager;
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
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;
import org.bukkit.util.BlockIterator;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public class LobbyListener implements Listener {

    private final Main plugin;
    private final ZoneManager zoneManager;
    private final LobbyBlockManager lobbyBlockManager;

    private final Map<UUID, EnderPearl> enderButtPearls = new HashMap<>();
    private final Map<UUID, Location> lastPearlLoc = new HashMap<>();
    private final Map<Location, int[]> lobbyBlockTasks = new HashMap<>();
    private final Map<UUID, Long> blockDenyCooldowns = new HashMap<>();
    private static final double MAX_PEARL_SPEED = 3.0;
    private static final long BLOCK_DENY_COOLDOWN_MS = 2000L;

    public LobbyListener(Main plugin, ZoneManager zoneManager, LobbyBlockManager lobbyBlockManager) {
        this.plugin = plugin;
        this.zoneManager = zoneManager;
        this.lobbyBlockManager = lobbyBlockManager;
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickEnderButt, 1L, 1L);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickHeightLimit, 1L, 4L);
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

    private void tickEnderButt() {
        Iterator<Map.Entry<UUID, EnderPearl>> it = enderButtPearls.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, EnderPearl> entry = it.next();
            UUID uuid = entry.getKey();
            EnderPearl pearl = entry.getValue();
            if (pearl.isDead() || !pearl.isValid()) {
                it.remove();
                lastPearlLoc.remove(uuid);
                continue;
            }

            Location currentLoc = pearl.getLocation();
            Location previousLoc = lastPearlLoc.get(uuid);

            // Check for collision if we have previous location
            if (previousLoc != null && !previousLoc.getWorld().equals(currentLoc.getWorld())) {
                previousLoc = null;
            }

            boolean collisionDetected = false;

            // Check pearl collision
            if (previousLoc != null) {
                double distance = previousLoc.distance(currentLoc);
                // Only check if distance is reasonable (prevents false positives on teleport)
                if (distance < 10.0) {
                    if (hasSolidBlockBetween(previousLoc, currentLoc)) {
                        collisionDetected = true;
                    }
                }
                // If distance is too large (teleport), reset previous location
                if (distance > 10.0) {
                    previousLoc = null;
                }
            }

            // Check player collision if riding the pearl
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline() && pearl.getPassenger() != null) {
                Location playerLoc = player.getLocation();
                if (isPlayerInsideSolidBlock(playerLoc)) {
                    collisionDetected = true;
                }
            }

            if (collisionDetected) {
                // Collision detected, remove the pearl
                if (player != null && player.isOnline()) {
                    player.eject();
                    pearl.eject();
                }
                pearl.remove();
                it.remove();
                lastPearlLoc.remove(uuid);
                continue;
            }

            // Limit speed to prevent phasing
            Vector velocity = pearl.getVelocity();
            double speed = velocity.length();
            if (speed > MAX_PEARL_SPEED) { // Limit maximum speed
                velocity.normalize().multiply(MAX_PEARL_SPEED);
                pearl.setVelocity(velocity);
            }

            lastPearlLoc.put(uuid, currentLoc.clone());

            if (currentLoc.getY() > 200) {
                Location capped = currentLoc.clone();
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
        plugin.setupPlayer(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        ejectAndCancelPearl(player);
        plugin.setFlightPreference(player, !plugin.isFlightDisabledByUser(player));
        plugin.saveConfig();
        player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
        blockDenyCooldowns.remove(player.getUniqueId());

        // Remove player from build mode when leaving
        if (plugin.isInBuildMode(player)) {
            plugin.setBuildMode(player, false);
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        plugin.setupWorld(event.getPlayer().getWorld());
        plugin.restoreFlightState(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!plugin.isInBuildMode(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();

        // Build mode: unrestricted placement
        if (plugin.isInBuildMode(player)) {
            event.setCancelled(false);
            return;
        }

        ItemStack item = event.getItemInHand();
        Block placed = event.getBlockPlaced();

        // Only lobby blocks are allowed outside of build mode
        if (!isLobbyBlock(item)) {
            event.setCancelled(true);
            return;
        }

        // Check zone block-placement restriction
        if (!player.hasPermission("lobby.zone.bypass")) {
            Zone zone = zoneManager.getZoneAt(placed.getLocation());
            if (zone != null && !zone.isAllowBlockPlacement()) {
                event.setCancelled(true);
                sendBlockDenyMessage(player);
                return;
            }
        }

        // Allow lobby block and start animation
        event.setCancelled(false);
        scheduleLobbyBlock(placed.getLocation(), item.getType());

        // Keep slot 4 filled with 64 sandstone lobby blocks
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
    }

    private void sendBlockDenyMessage(Player player) {
        long now = System.currentTimeMillis();
        Long last = blockDenyCooldowns.get(player.getUniqueId());
        if (last != null && now - last < BLOCK_DENY_COOLDOWN_MS) return;
        blockDenyCooldowns.put(player.getUniqueId(), now);
        player.sendMessage("§cYou cannot place blocks in this zone!");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        Action action = event.getAction();

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

        if (SpeedCookieListener.isSpeedCookie(item) && (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK)) {
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

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof ItemFrame)) return;
        if (!(event.getDamager() instanceof Player)) return;
        if (plugin.isInBuildMode((Player) event.getDamager())) return;
        event.setCancelled(true);
    }

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
            if (event.getClickedInventory() != null && event.getClickedInventory().equals(event.getView().getTopInventory())) {
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
        if (event.getItemDrop().getItemStack().getType() == Material.DIAMOND_BLOCK) {
            event.setCancelled(true);
            return;
        }
        if (!plugin.isInBuildMode(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onPickup(PlayerPickupItemEvent event) {
        if (event.getItem().getItemStack().getType() == Material.DIAMOND_BLOCK) return;
        if (!plugin.isInBuildMode(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player && event.getCause() != EntityDamageEvent.DamageCause.VOID) {
            event.setCancelled(true);
        }
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

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) return;
        Player player = event.getPlayer();
        Location from = event.getFrom();
        Location to = event.getTo();

        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ()) {
            return;
        }

        if (to.getY() < -50 && !plugin.isInBuildMode(player)) {
            event.setCancelled(true);
            Location spawn = plugin.getSpawnLocation();
            if (spawn != null) player.teleport(spawn);
            return;
        }

        boolean flightFrom = zoneManager.isFlightAllowedAt(from);
        boolean flightTo = zoneManager.isFlightAllowedAt(to);

        if (flightFrom && !flightTo) {
            player.setAllowFlight(false);
            player.setFlying(false);
        } else if (!flightFrom && flightTo) {
            if (!plugin.isFlightDisabledByUser(player) && player.hasPermission("lobby.fly")) {
                player.setAllowFlight(true);
            }
        }
    }

    private void handleLobbyItemUse(Player player, ItemStack item) {
        if (item.getType() == Material.COMPASS && nameEquals(item, "§cServer Selector")) {
            plugin.getServerSelectorManager().openServerSelector(player);
        } else if (item.getType() == Material.ENDER_PEARL && nameEquals(item, "§cEnder Butt")) {
            UUID uuid = player.getUniqueId();
            EnderPearl old = enderButtPearls.remove(uuid);
            lastPearlLoc.remove(uuid);
            if (old != null && !old.isDead()) {
                old.eject();
                old.remove();
            }
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
        } else if (item.getType() == Material.INK_SACK && item.hasItemMeta() && item.getItemMeta().getDisplayName().contains("visible")) {
            plugin.cycleVisibilityMode(player);
        }
    }

    private void scheduleLobbyBlock(Location loc, Material itemType) {
        // Cancel any running animation at this location
        int[] existing = lobbyBlockTasks.remove(loc);
        if (existing != null) {
            for (int id : existing) Bukkit.getScheduler().cancelTask(id);
        }

        lobbyBlockManager.addLobbyBlock(loc);

        // Play placement sound
        loc.getWorld().playSound(loc, Sound.DIG_STONE, 0.8f, 1.2f);
        // Orange sparkle to mark the block
        loc.getWorld().spigot().playEffect(
            loc.clone().add(0.5, 0.5, 0.5),
            org.bukkit.Effect.COLOURED_DUST, 0, 1,
            1.0f, 0.55f, 0.0f, 1, 20, 12
        );

        // Diamond blocks display as sandstone immediately (same animation, different ending)
        final Material colorBlock = (itemType == Material.DIAMOND_BLOCK)
            ? Material.EMERALD_BLOCK
            : Material.REDSTONE_BLOCK;

        if (itemType == Material.DIAMOND_BLOCK) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (loc.getBlock().getType() == Material.DIAMOND_BLOCK) {
                    loc.getBlock().setType(Material.SANDSTONE);
                }
            });
        }

        // Phase 1 – after 5 s (100 t): sandstone → color block
        int t1 = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (loc.getBlock().getType() == Material.SANDSTONE) {
                loc.getBlock().setType(colorBlock);
                //loc.getWorld().playSound(loc, Sound.NOTE_PLING, 0.6f, 1.4f);
            }
        }, 100L).getTaskId();

        // Phase 2 – after 2 more s (40 t): color block → air
        int t2 = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            lobbyBlockTasks.remove(loc);
            lobbyBlockManager.removeLobbyBlock(loc);
            if (loc.getBlock().getType() == colorBlock) {
                loc.getBlock().setType(Material.AIR);
                //loc.getWorld().playSound(loc, Sound.POP, 0.5f, 1.5f);
                loc.getWorld().spigot().playEffect(
                    loc.clone().add(0.5, 0.5, 0.5),
                    org.bukkit.Effect.COLOURED_DUST, 0, 1,
                    0.2f, 1.0f, 0.2f, 1, 15, 12
                );
            }
        }, 140L).getTaskId();

        lobbyBlockTasks.put(loc, new int[]{t1, t2});
    }

    private void ejectAndCancelPearl(Player player) {
        if (player.isInsideVehicle()) player.getVehicle().eject();
        EnderPearl pearl = enderButtPearls.remove(player.getUniqueId());
        lastPearlLoc.remove(player.getUniqueId());
        if (pearl != null && !pearl.isDead()) {
            pearl.eject();
            pearl.remove();
        }
    }

    private boolean isLobbyBlock(ItemStack item) {
        return item != null && (item.getType() == Material.SANDSTONE || item.getType() == Material.DIAMOND_BLOCK) && item.hasItemMeta() && "§cLobby Blocks".equals(item.getItemMeta().getDisplayName());
    }

    private boolean isLobbyItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        String n = item.getItemMeta().getDisplayName();
        return "§cServer Selector".equals(n) || "§cEnder Butt".equals(n) || "§cCoinshop".equals(n) || "§cSettings".equals(n) || "§cFriends".equals(n) || n.contains("visible");
    }

    private boolean nameEquals(ItemStack item, String name) {
        return item.hasItemMeta() && name.equals(item.getItemMeta().getDisplayName());
    }

    private boolean hasSolidBlockBetween(Location from, Location to) {
        if (from == null || to == null || !from.getWorld().equals(to.getWorld())) {
            return false;
        }

        double distance = from.distance(to);
        if (distance < 0.1) {
            return false; // Too close, no need to check
        }

        Vector direction = to.toVector().subtract(from.toVector()).normalize();
        int maxDistance = (int) Math.ceil(distance) + 2;

        // Use BlockIterator for ray casting with smaller step size
        BlockIterator iterator = new BlockIterator(from.getWorld(), from.toVector(), direction, 0, maxDistance);

        while (iterator.hasNext()) {
            Block block = iterator.next();
            Material type = block.getType();

                        // Check if block is solid
            if (isSolid(type)) {
                return true; // Solid block found
            }
        }

        // Additional check: sample points along the line at higher resolution
        int steps = (int) (distance * 4); // 4 samples per block
        if (steps > 20) steps = 20; // Cap to prevent performance issues
        if (steps < 2) steps = 2;

        for (int i = 1; i < steps; i++) {
            double t = (double) i / steps;
            Location sampled = from.clone().add(direction.clone().multiply(distance * t));
            Block block = sampled.getBlock();
            if (isSolid(block.getType())) {
                return true;
            }
        }

        return false;
    }

    private boolean isPassable(Material material) {
        // List of materials that are technically solid but players can pass through
        switch (material) {
            case SIGN_POST:
            case WALL_SIGN:
            case SIGN:
            case WOOD_PLATE:
            case STONE_PLATE:
            case IRON_PLATE:
            case GOLD_PLATE:
            case REDSTONE_TORCH_ON:
            case REDSTONE_TORCH_OFF:
            case TORCH:
            case REDSTONE_WIRE:
            case TRIPWIRE:
            case TRIPWIRE_HOOK:
            case RAILS:
            case POWERED_RAIL:
            case DETECTOR_RAIL:
            case ACTIVATOR_RAIL:
            case SAPLING:
            case YELLOW_FLOWER:
            case RED_ROSE:
            case BROWN_MUSHROOM:
            case RED_MUSHROOM:
            case DEAD_BUSH:
            case LONG_GRASS:
            case VINE:
            case WATER_LILY:
            case SNOW:
            case CARPET:
            case LADDER:
            case WATER:
            case LAVA:
            case STATIONARY_WATER:
            case STATIONARY_LAVA:
            case WEB:
            case ENDER_PORTAL:
            case ENDER_PORTAL_FRAME:
            case PORTAL:
            case AIR:
            case IRON_FENCE:
                return true;
            default:
                // Check if it's a slab, stair, or other half-block
                String name = material.name();
                if (name.contains("SLAB") || name.contains("STEP") || name.contains("STAIRS")) {
                    return true;
                }
                // Check for glass panes and thin glass
                if (name.contains("GLASS_PANE") || name.contains("THIN_GLASS")) {
                    return true;
                }
                return false;
        }
    }

    private boolean isPlayerInsideSolidBlock(Location playerLoc) {
        // Check multiple points around player's hitbox (0.6x0.6x1.8)
        double halfWidth = 0.3;
        double height = 1.8;

        // Check feet and head first (quick check)
        Block feetBlock = playerLoc.getBlock();
        Block headBlock = playerLoc.clone().add(0, height - 0.1, 0).getBlock();
        if (isSolid(feetBlock.getType()) || isSolid(headBlock.getType())) {
            return true;
        }

        // Check 8 points around the player's hitbox
        double[] offsets = {-halfWidth, halfWidth};
        for (double dx : offsets) {
            for (double dz : offsets) {
                for (double dy = 0; dy <= height; dy += 0.5) {
                    Location checkLoc = playerLoc.clone().add(dx, dy, dz);
                    Block block = checkLoc.getBlock();
                    if (isSolid(block.getType())) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private boolean isSolid(Material material) {
        if (material == Material.AIR || material == Material.WATER || material == Material.LAVA ||
            material == Material.STATIONARY_WATER || material == Material.STATIONARY_LAVA) {
            return false;
        }

        if (!material.isSolid()) {
            return false;
        }

        // Check if it's passable
        return !isPassable(material);
    }
}

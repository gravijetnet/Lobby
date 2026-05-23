package net.gravijet.lobby.listener;

import net.gravijet.lobby.LobbyBlockManager;
import net.gravijet.lobby.Main;
import net.gravijet.lobby.zone.Zone;
import net.gravijet.lobby.zone.ZoneManager;
import org.bukkit.*;
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
    // Keyed by "world:x:y:z" to avoid Location hashCode/equals issues with yaw/pitch
    private final Map<String, int[]> lobbyBlockTasks = new HashMap<>();
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
            // Eject and remove the pearl first so the player is no longer a vehicle
            // passenger before the teleport, preventing pearl/player desync.
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
        if (enderButtPearls.isEmpty()) return;
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
            if (currentLoc.getWorld() == null) {
                it.remove();
                lastPearlLoc.remove(uuid);
                continue;
            }
            Location previousLoc = lastPearlLoc.get(uuid);

            // Check for collision if we have previous location
            if (previousLoc != null && (previousLoc.getWorld() == null || !previousLoc.getWorld().equals(currentLoc.getWorld()))) {
                previousLoc = null;
            }

            // Zone restriction: stop pearl if it enters a zone the rider can't access
            Player riderCheck = Bukkit.getPlayer(uuid);
            if (riderCheck != null && riderCheck.isOnline() && pearl.getPassenger() != null) {
                Zone deniedZone = zoneManager.getDeniedZoneAt(riderCheck, currentLoc);
                if (deniedZone != null) {
                    Location safeReturn = (previousLoc != null) ? previousLoc.clone() : null;
                    Location riderLoc = riderCheck.getLocation();
                    riderCheck.eject();
                    pearl.eject();
                    pearl.remove();
                    it.remove();
                    lastPearlLoc.remove(uuid);
                    if (safeReturn != null && zoneManager.getDeniedZoneAt(riderCheck, safeReturn) == null) {
                        safeReturn.setY(riderLoc.getY());
                        safeReturn.setYaw(riderLoc.getYaw());
                        safeReturn.setPitch(riderLoc.getPitch());
                        riderCheck.teleport(safeReturn);
                    }
                    riderCheck.setVelocity(new Vector(0, 0, 0));
                    continue;
                }
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

            // Check player collision if riding the pearl (reuse riderCheck fetched above)
            Player player = riderCheck;
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

            // Steer pearl toward player's current look direction (fixes direction control in 1.9+)
            if (player != null && player.isOnline() && pearl.getPassenger() != null) {
                Vector lookDir = player.getLocation().getDirection();
                Vector current = pearl.getVelocity();
                double speed = current.length();
                if (speed < 0.5) speed = 1.5;
                if (speed > MAX_PEARL_SPEED) speed = MAX_PEARL_SPEED;
                // Blend 60% current direction + 40% look direction for smooth steering
                Vector blended = current.normalize().multiply(0.6).add(lookDir.multiply(0.4));
                double blendedLen = blended.length();
                Vector steered = blendedLen > 1e-6 ? blended.multiply(1.0 / blendedLen) : lookDir.clone().normalize();
                double finalSpeed = Math.min(speed, MAX_PEARL_SPEED);
                pearl.setVelocity(steered.multiply(finalSpeed));
            } else {
                Vector velocity = pearl.getVelocity();
                double speed = velocity.length();
                if (speed > MAX_PEARL_SPEED) {
                    pearl.setVelocity(velocity.normalize().multiply(MAX_PEARL_SPEED));
                }
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
        plugin.savePlayerFlightPreference(player);
        player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard());
        plugin.clearScoreboardCache(player.getUniqueId());
        blockDenyCooldowns.remove(player.getUniqueId());
        plugin.cleanupPlayerState(player.getUniqueId());
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
        Zone zone = zoneManager.getZoneAt(placed.getLocation());
        if (zone != null && !zone.isAllowBlockPlacement()) {
            event.setCancelled(true);
            sendBlockDenyMessage(player);
            return;
        }

        // Allow lobby block and start animation
        event.setCancelled(false);
        scheduleLobbyBlock(placed.getLocation(), item.getType());

        // Keep slot 4 filled with 64 sandstone lobby blocks (only for named lobby block items)
        Bukkit.getScheduler().runTask(plugin, () -> {
            ItemStack slot4 = player.getInventory().getItem(4);
            boolean isNamedLobbyBlock = false;
            if (slot4 != null && slot4.hasItemMeta()) {
                ItemMeta slot4Meta = slot4.getItemMeta();
                isNamedLobbyBlock = slot4Meta != null
                    && "§cBlocks".equals(slot4Meta.getDisplayName())
                    && slot4.getType() == Material.SANDSTONE;
            }
            if (isNamedLobbyBlock) {
                slot4.setAmount(64);
            } else {
                ItemStack fresh = new ItemStack(Material.SANDSTONE, 64);
                ItemMeta meta = fresh.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName("§cBlocks");
                    fresh.setItemMeta(meta);
                }
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
        plugin.getMessages().send(player, "lobby-block.cannot-place");
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

        // Don't cancel physical interactions (pressure plates, tripwires, etc.)
        if (action == Action.PHYSICAL) {
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
        if (plugin.getServerSelectorManager().isSelectorTitle(event.getView().getTitle())) {
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
        if (!plugin.isInBuildMode(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onPickup(PlayerPickupItemEvent event) {
        if (!plugin.isInBuildMode(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
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
            enderButtPearls.put(uuid, pearl);
            lastPearlLoc.put(uuid, pearl.getLocation().clone());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!pearl.isDead() && pearl.isValid()) pearl.setPassenger(player);
                player.updateInventory();
            });
        } else if (item.getType() == Material.GOLD_INGOT && nameEquals(item, "§cCoinshop")) {
            Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("coinshop"));
        } else if (item.getType() == Material.REDSTONE_TORCH_ON && nameEquals(item, "§cSettings")) {
            Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("settings"));
        } else if (item.getType() == Material.SKULL_ITEM && nameEquals(item, "§cFriends")) {
            Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("friends menu"));
        } else if (item.getType() == Material.INK_SACK && item.hasItemMeta()) {
            ItemMeta inkMeta = item.getItemMeta();
            if (inkMeta != null && inkMeta.getDisplayName().contains("visible")) {
                plugin.cycleVisibilityMode(player);
            }
        }
    }

    private static String blockKey(Location loc) {
        if (loc.getWorld() == null) return "null:" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
        String worldName = loc.getWorld().getName().replace(":", "_");
        return worldName + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ();
    }

    private void scheduleLobbyBlock(Location loc, Material itemType) {
        String key = blockKey(loc);
        // Cancel any running animation at this location
        int[] existing = lobbyBlockTasks.remove(key);
        if (existing != null) {
            for (int id : existing) Bukkit.getScheduler().cancelTask(id);
        }

        lobbyBlockManager.addLobbyBlock(loc);

        final Material colorBlock = (itemType == Material.DIAMOND_BLOCK)
            ? Material.EMERALD_BLOCK
            : Material.REDSTONE_BLOCK;

        long firstChangeTicks = plugin.getConfig().getLong("lobby-blocks.first-change-ticks", 100L);
        long removeTicks = plugin.getConfig().getLong("lobby-blocks.remove-ticks", 140L);
        if (firstChangeTicks < 1L) firstChangeTicks = 100L;
        if (removeTicks <= firstChangeTicks) removeTicks = firstChangeTicks + 40L;

        // Phase 1 – placed block → color block
        int t1 = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (loc.getBlock().getType() == itemType) {
                loc.getBlock().setType(colorBlock);
            }
            // If the block type changed unexpectedly (placed over by another player),
            // the block is no longer a recognizable lobby block — evict it from tracking
            // so phase 2 still cleans up the key even if it doesn't set AIR.
        }, firstChangeTicks).getTaskId();

        // Phase 2 – color block → air (always remove from tracking regardless of current type)
        int t2 = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            lobbyBlockTasks.remove(key);
            lobbyBlockManager.removeLobbyBlock(loc);
            if (loc.getBlock().getType() == colorBlock) {
                loc.getBlock().setType(Material.AIR);
            }
            // If the block was already replaced by something else, just stop tracking it —
            // the foreign block is left intact.
        }, removeTicks).getTaskId();

        lobbyBlockTasks.put(key, new int[]{t1, t2});
    }

    private void ejectAndCancelPearl(Player player) {
        if (player.isInsideVehicle()) {
            org.bukkit.entity.Entity vehicle = player.getVehicle();
            if (vehicle != null) vehicle.eject();
        }
        EnderPearl pearl = enderButtPearls.remove(player.getUniqueId());
        lastPearlLoc.remove(player.getUniqueId());
        if (pearl != null && !pearl.isDead()) {
            pearl.eject();
            pearl.remove();
        }
    }

    private boolean isLobbyBlock(ItemStack item) {
        if (item == null) return false;
        // Any diamond block is always allowed as a lobby block
        if (item.getType() == Material.DIAMOND_BLOCK) return true;
        if (item.getType() != Material.SANDSTONE || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return "§cBlocks".equals(meta.getDisplayName());
    }

    private boolean isLobbyItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        String n = meta.getDisplayName();
        return "§cServer Selector".equals(n) || "§cEnder Butt".equals(n) || "§cCoinshop".equals(n) || "§cSettings".equals(n) || "§cFriends".equals(n) || n.contains("visible");
    }

    private boolean nameEquals(ItemStack item, String name) {
        if (!item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        return name.equals(meta.getDisplayName());
    }

    private boolean hasSolidBlockBetween(Location from, Location to) {
        if (from == null || to == null || from.getWorld() == null || to.getWorld() == null
                || !from.getWorld().equals(to.getWorld())) {
            return false;
        }

        double distance = from.distance(to);
        if (distance < 0.1) {
            return false; // Too close, no need to check
        }

        Vector direction = to.toVector().subtract(from.toVector()).normalize();
        int maxDistance = (int) Math.ceil(distance);

        try {
            BlockIterator iterator = new BlockIterator(from.getWorld(), from.toVector(), direction, 0, maxDistance);
            while (iterator.hasNext()) {
                Block block = iterator.next();
                if (isSolid(block.getType())) {
                    return true;
                }
            }
        } catch (IllegalStateException e) {
            return false;
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
                return true;
            default:
                // Check for glass panes and thin glass (players can move through)
                String name = material.name();
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

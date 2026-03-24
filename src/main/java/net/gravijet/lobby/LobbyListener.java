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
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

public class LobbyListener implements Listener {
    private final Main plugin;

    public LobbyListener(Main plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Build-Modus-State bereinigen ohne doppeltes Inventar-Setup auszulösen.
        // setupPlayer kümmert sich danach um alles (Gamemode, Inventar, etc.)
        plugin.clearBuildMode(player);

        // Tablist für alle Spieler sichtbar machen (1.8 API: kein Plugin-Parameter)
        for (Player online : Bukkit.getOnlinePlayers()) {
            event.getPlayer().showPlayer(online);
        }

        plugin.setupPlayer(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Nur State bereinigen – kein Inventar-/Gamemode-Reset für einen Spieler der bereits weg ist
        plugin.clearBuildMode(player);

        // Scoreboard zurücksetzen beim Verlassen
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

        // Blockiere ALLE Block-Interaktionen wenn nicht im Build-Modus
        if (!plugin.isInBuildMode(player) &&
                (action == Action.RIGHT_CLICK_BLOCK || action == Action.LEFT_CLICK_BLOCK) &&
                block != null) {
            event.setCancelled(true);
        }

        if (item == null) return;

        // Verhindere, dass Lobby-Items verbraucht werden
        if (isLobbyItem(item)) {
            event.setCancelled(true);

            // Führe die Aktionen nur bei Rechtsklick aus
            if (action.toString().contains("RIGHT")) {
                // Server Selector Handling
                if (item.getType() == Material.COMPASS && item.getItemMeta().getDisplayName().equals("§aServer Selector")) {
                    plugin.getServerSelectorManager().openServerSelector(player);
                    return;
                }

                // Ender Butt Handling
                if (item.getType() == Material.ENDER_PEARL && item.getItemMeta().getDisplayName().equals("§aEnder Butt")) {
                    // Sound abspielen
                    player.playSound(player.getLocation(), Sound.ENDERMAN_TELEPORT, 1.0f, 1.0f);

                    // EnderPerle werfen
                    EnderPearl pearl = player.launchProjectile(EnderPearl.class);
                    Vector direction = player.getLocation().getDirection().multiply(1.5);
                    pearl.setVelocity(direction);
                    // KEIN setPassenger(player) – das würde den Spieler als Reiter der Perle setzen (falsche API-Nutzung)

                    // EnderPerle nach kurzer Zeit entfernen (verhindert Teleport)
                    Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        if (pearl != null && !pearl.isDead()) {
                            pearl.remove();
                        }
                    }, 20L);
                    return;
                }

                // Coinshop Item
                if (item.getType() == Material.GOLD_INGOT && item.getItemMeta().getDisplayName().equals("§aCoinshop")) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        player.performCommand("coinshop");
                    });
                    return;
                }

                // Settings Item
                if (item.getType() == Material.REDSTONE_TORCH_ON && item.getItemMeta().getDisplayName().equals("§aSettings")) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        player.performCommand("settings");
                    });
                    return;
                }

                // Friends Item (1.8: SKULL_ITEM statt PLAYER_HEAD)
                if (item.getType() == Material.SKULL_ITEM && item.getItemMeta().getDisplayName().equals("§aFriends")) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        player.performCommand("friends menu");
                    });
                    return;
                }

                // Visibility Item (1.8: INK_SACK statt *_DYE)
                if (item.getType() == Material.INK_SACK && item.getItemMeta().getDisplayName().contains("visible")) {
                    plugin.cycleVisibilityMode(player);
                    return;
                }
            }
        }

        // Verhindere alle anderen Interaktionen ohne Build-Rechte
        if (!plugin.isInBuildMode(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();

        // Verhindere das Bewegen von Items im Server Selector
        if (event.getView().getTitle().contains("Server Selector")) {
            event.setCancelled(true);
            // Handle Server Selector clicks nur wenn im eigenen Inventar geklickt wird
            if (event.getClickedInventory() != null &&
                    event.getClickedInventory().equals(event.getView().getTopInventory())) {
                plugin.getServerSelectorManager().handleMenuClick(player, event.getSlot());
            }
            return;
        }

        // Verhindere das Bewegen von Lobby-Items in der Hotbar
        if (event.getCurrentItem() != null &&
                event.getClickedInventory() != null &&
                event.getClickedInventory().equals(player.getInventory())) {

            ItemStack clickedItem = event.getCurrentItem();
            if (isLobbyItem(clickedItem)) {
                event.setCancelled(true);
                return;
            }
        }

        // Verhindere alle Inventar-Interaktionen außer im Build-Modus
        if (!plugin.isInBuildMode(player)) {
            event.setCancelled(true);
        }
        // Kein Offhand-Slot (Slot 40) in 1.8 — Check entfernt
    }

    // Methode zur Überprüfung, ob es sich um ein Lobby-Item handelt
    private boolean isLobbyItem(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;

        String displayName = item.getItemMeta().getDisplayName();
        return displayName.equals("§aServer Selector") ||
                displayName.equals("§aEnder Butt") ||
                displayName.equals("§aCoinshop") ||
                displayName.equals("§aSettings") ||
                displayName.equals("§aFriends") ||
                displayName.contains("visible");
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (!plugin.isInBuildMode(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // 1.8 API: PlayerPickupItemEvent statt EntityPickupItemEvent
    @EventHandler
    public void onPickup(PlayerPickupItemEvent event) {
        if (!plugin.isInBuildMode(event.getPlayer())) {
            event.setCancelled(true);
        }
    }
    // PlayerSwapHandItemsEvent existiert nicht in 1.8 (kein Offhand) → entfernt

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
        // setupPlayer erst im nächsten Tick aufrufen, damit der Respawn-Vorgang abgeschlossen ist.
        // player.teleport() direkt im Respawn-Event zu rufen ist falsch und wird vom Server ignoriert/überschrieben.
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.setupPlayer(event.getPlayer()), 1L);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        // getTo() kann null sein bei reiner Kopf-Rotation
        if (event.getTo() == null) return;

        // Nur bei tatsächlicher Positions-Änderung prüfen (Performance)
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        // Teleportiere zum Spawn wenn unter Y=30 und nicht im Build-Modus
        if (event.getTo().getY() < 30 && !plugin.isInBuildMode(player)) {
            Location spawn = plugin.getSpawnLocation();
            if (spawn != null) {
                // Direkt teleportieren statt Task einreihen – verhindert Task-Spam beim Fallen
                event.setCancelled(true);
                player.teleport(spawn);
            }
        }
    }
}
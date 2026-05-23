package net.gravijet.lobby.listener;

import net.gravijet.lobby.Main;
import net.gravijet.lobby.command.GetSpeedCookieCommand;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class SpeedCookieListener implements Listener {

    private final Main plugin;
    private final GetSpeedCookieCommand cookieCommand;
    private final Set<UUID> consuming = new HashSet<>();

    public SpeedCookieListener(Main plugin, GetSpeedCookieCommand cookieCommand) {
        this.plugin = plugin;
        this.cookieCommand = cookieCommand;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (!"§bSpeed Cookie".equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        if (event.getClickedInventory() == null) return;
        if (!event.getClickedInventory().equals(event.getView().getTopInventory())) return;
        if (event.getSlot() != 13) return;
        cookieCommand.handleCookieClick((Player) event.getWhoClicked());
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Player player = event.getPlayer();
        if (!isSpeedCookie(player.getItemInHand())) return;

        UUID uuid = player.getUniqueId();
        int prevFood = player.getFoodLevel();
        if (prevFood >= 20) {
            consuming.add(uuid);
            player.setFoodLevel(6);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player p = Bukkit.getPlayer(uuid);
                // Only restore if onConsume did not fire (player is still in consuming set)
                if (consuming.remove(uuid) && p != null && p.isOnline()) {
                    p.setFoodLevel(prevFood);
                }
            }, 80L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        consuming.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (!isSpeedCookie(event.getItem())) return;
        consuming.remove(player.getUniqueId());
        player.setFoodLevel(20);
        // ambient=true shows subtle particles; particles=true shows the effect icon in the HUD
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 60 * 10, 1, true, true), true);
        plugin.getMessages().send(player, "speedcookie.received-eat");
    }

    public static boolean isSpeedCookie(ItemStack item) {
        if (item == null || item.getType() != Material.COOKIE || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && "§bSpeed Cookie".equals(meta.getDisplayName());
    }
}

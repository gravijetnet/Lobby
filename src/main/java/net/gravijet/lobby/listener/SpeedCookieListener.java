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
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.UUID;

public class SpeedCookieListener implements Listener {

    private final Main plugin;
    private final GetSpeedCookieCommand cookieCommand;

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

        if (player.getFoodLevel() >= 20) {
            player.setFoodLevel(6);
            UUID uuid = player.getUniqueId();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline() && player.getFoodLevel() <= 6) {
                    player.setFoodLevel(20);
                }
            }, 80L);
        }
    }

    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        if (!isSpeedCookie(event.getItem())) return;
        player.setFoodLevel(20);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 20 * 60 * 10, 1, false, false), true);
        plugin.getMessages().send(player, "speedcookie.received-eat");
    }

    public static boolean isSpeedCookie(ItemStack item) {
        return item != null && item.getType() == Material.COOKIE && item.hasItemMeta() && "§bSpeed Cookie".equals(item.getItemMeta().getDisplayName());
    }
}

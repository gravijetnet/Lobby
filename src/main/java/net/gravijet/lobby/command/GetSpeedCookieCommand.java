package net.gravijet.lobby.command;

import net.gravijet.lobby.Main;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class GetSpeedCookieCommand implements CommandExecutor {

    public static final long COOLDOWN_MS = 3_600_000L;

    private final Main            plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public GetSpeedCookieCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            plugin.getMessages().send(sender, "general.player-only");
            return true;
        }
        openMenu((Player) sender);
        return true;
    }

    public void openMenu(Player player) {
        Inventory inv  = Bukkit.createInventory(null, 27, "§bSpeed Cookie");
        ItemStack pane = new ItemStack(Material.STAINED_GLASS_PANE, 1, (short) 7);
        ItemMeta  paneMeta = pane.getItemMeta();
        if (paneMeta != null) {
            paneMeta.setDisplayName("§8 ");
            pane.setItemMeta(paneMeta);
        }
        for (int i = 0; i < 27; i++) {
            if (i != 13) inv.setItem(i, pane);
        }
        inv.setItem(13, buildMenuCookieItem(player.getUniqueId()));
        player.openInventory(inv);
    }

    public void handleCookieClick(Player player) {
        UUID uuid      = player.getUniqueId();
        long remaining = getRemainingCooldown(uuid);
        if (remaining > 0) {
            plugin.getMessages().send(player, "speedcookie.cooldown", "time", formatTime(remaining));
            return;
        }

        // Give the item FIRST so that a crash between this line and the cooldown save
        // does not permanently burn the cooldown without the player receiving the cookie.
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(buildGiveCookieItem());
        player.closeInventory();
        if (!leftover.isEmpty()) {
            plugin.getMessages().send(player, "speedcookie.inventory-full");
            return;
        }

        // Item successfully placed — now record the cooldown.
        long now = System.currentTimeMillis();
        cooldowns.put(uuid, now);
        plugin.getPlayersConfig().set(uuid + ".cookie-cooldown", now);
        // Save asynchronously to avoid freezing the main thread on every click.
        Bukkit.getScheduler().runTaskAsynchronously(plugin, plugin::savePlayersConfig);

        plugin.getMessages().send(player, "speedcookie.received-menu");
    }

    public ItemStack buildMenuCookieItem(UUID uuid) {
        ItemStack cookie = new ItemStack(Material.COOKIE);
        ItemMeta  meta   = cookie.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§bSpeed Cookie");
            List<String> lore = new ArrayList<>();
            lore.add("§7Grants §bSpeed II §7for §b10 minutes§7!");
            long remaining = getRemainingCooldown(uuid);
            if (remaining > 0) {
                lore.add("§cAvailable in: §e" + formatTime(remaining));
            } else {
                lore.add("§aClick to receive!");
            }
            meta.setLore(lore);
            cookie.setItemMeta(meta);
        }
        return cookie;
    }

    public static ItemStack buildGiveCookieItem() {
        ItemStack cookie = new ItemStack(Material.COOKIE);
        ItemMeta  meta   = cookie.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§bSpeed Cookie");
            List<String> lore = new ArrayList<>();
            lore.add("§7Grants §bSpeed II §7for §b10 minutes§7!");
            lore.add("§7Right-click to eat!");
            meta.setLore(lore);
            cookie.setItemMeta(meta);
        }
        return cookie;
    }

    public boolean hasCooldown(UUID uuid) {
        return getRemainingCooldown(uuid) > 0;
    }

    public long getRemainingCooldown(UUID uuid) {
        Long last = cooldowns.get(uuid);
        if (last == null) {
            // Fall back to persisted value (survives restarts/reloads)
            long persisted = plugin.getPlayersConfig().getLong(uuid + ".cookie-cooldown", 0L);
            if (persisted == 0L) return 0;
            long remaining = (persisted + COOLDOWN_MS) - System.currentTimeMillis();
            if (remaining > 0) {
                cooldowns.put(uuid, persisted); // cache it
                return remaining;
            }
            return 0;
        }
        return Math.max(0, (last + COOLDOWN_MS) - System.currentTimeMillis());
    }

    private static String formatTime(long ms) {
        long secs = ms / 1000;
        long mins = secs / 60;
        secs = secs % 60;
        return String.format("%02d:%02d", mins, secs);
    }
}

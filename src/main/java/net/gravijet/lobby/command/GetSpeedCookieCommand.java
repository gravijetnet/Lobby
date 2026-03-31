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

    private final Main plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    public GetSpeedCookieCommand(Main plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cNur für Spieler!");
            return true;
        }
        openMenu((Player) sender);
        return true;
    }

    public void openMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, "§bSpeed Cookie");

        ItemStack pane = new ItemStack(Material.STAINED_GLASS_PANE, 1, (short) 7);
        ItemMeta paneMeta = pane.getItemMeta();
        paneMeta.setDisplayName("§8 ");
        pane.setItemMeta(paneMeta);
        for (int i = 0; i < 27; i++) {
            if (i != 13) inv.setItem(i, pane);
        }

        inv.setItem(13, buildMenuCookieItem(player.getUniqueId()));
        player.openInventory(inv);
    }

    public void handleCookieClick(Player player) {
        UUID uuid = player.getUniqueId();
        long remaining = getRemainingCooldown(uuid);
        if (remaining > 0) {
            player.sendMessage("§cDu musst noch §e" + formatTime(remaining) + " §cwarten!");
            return;
        }
        cooldowns.put(uuid, System.currentTimeMillis());
        player.getInventory().addItem(buildGiveCookieItem());
        player.closeInventory();
        player.sendMessage("§aDu hast einen §bSpeed Cookie §aerhalten!");
    }

    public ItemStack buildMenuCookieItem(UUID uuid) {
        ItemStack cookie = new ItemStack(Material.COOKIE);
        ItemMeta meta = cookie.getItemMeta();
        meta.setDisplayName("§bSpeed Cookie");
        List<String> lore = new ArrayList<>();
        lore.add("§7Gibt dir §bSpeed II §7für §b10 Minuten§7!");
        long remaining = getRemainingCooldown(uuid);
        if (remaining > 0) {
            lore.add("§cVerfügbar in: §e" + formatTime(remaining));
        } else {
            lore.add("§aKlicke zum Erhalten!");
        }
        meta.setLore(lore);
        cookie.setItemMeta(meta);
        return cookie;
    }

    public static ItemStack buildGiveCookieItem() {
        ItemStack cookie = new ItemStack(Material.COOKIE);
        ItemMeta meta = cookie.getItemMeta();
        meta.setDisplayName("§bSpeed Cookie");
        List<String> lore = new ArrayList<>();
        lore.add("§7Gibt dir §bSpeed II §7für §b10 Minuten§7!");
        lore.add("§7Halte Rechtsklick zum Essen!");
        meta.setLore(lore);
        cookie.setItemMeta(meta);
        return cookie;
    }

    public boolean hasCooldown(UUID uuid) {
        Long last = cooldowns.get(uuid);
        return last != null && System.currentTimeMillis() - last < COOLDOWN_MS;
    }

    public long getRemainingCooldown(UUID uuid) {
        Long last = cooldowns.get(uuid);
        if (last == null) return 0;
        return Math.max(0, (last + COOLDOWN_MS) - System.currentTimeMillis());
    }

    private static String formatTime(long ms) {
        long secs = ms / 1000;
        long mins = secs / 60;
        secs = secs % 60;
        return String.format("%02d:%02d", mins, secs);
    }
}

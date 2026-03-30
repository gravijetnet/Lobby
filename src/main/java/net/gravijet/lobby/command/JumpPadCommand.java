package net.gravijet.lobby.command;

import net.gravijet.lobby.Main;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Simple jump pad system using slime blocks or pressure plates.
 * 
 * Commands:
 * /jumppad add <strength> [type] - Add jump pad at targeted block
 * /jumppad remove - Remove jump pad at targeted block
 * /jumppad list - List all jump pads
 * /jumppad wand - Get jump pad wand to select blocks
 * 
 * Types: SLIME_BLOCK, STONE_PRESSURE_PLATE, GOLD_PLATE, IRON_PLATE
 */
public class JumpPadCommand implements CommandExecutor, Listener {

    private final Main plugin;
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private static final long COOLDOWN_MS = 1000L;
    
    public JumpPadCommand(Main plugin) {
        this.plugin = plugin;
    }
    
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }
        
        Player player = (Player) sender;
        
        if (!player.hasPermission("lobby.jumppad")) {
            player.sendMessage("§cYou don't have permission to use jump pad commands.");
            return true;
        }
        
        if (args.length == 0) {
            sendUsage(player);
            return true;
        }
        
        String subCommand = args[0].toLowerCase();
        
        switch (subCommand) {
            case "add":
                handleAdd(player, args);
                break;
            case "remove":
                handleRemove(player);
                break;
            case "list":
                handleList(player);
                break;
            case "wand":
                handleWand(player);
                break;
            default:
                sendUsage(player);
                break;
        }
        
        return true;
    }
    
    private void handleAdd(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage("§cUsage: /jumppad add <strength> [type]");
            player.sendMessage("§7Types: SLIME_BLOCK, STONE_PRESSURE_PLATE, GOLD_PLATE, IRON_PLATE");
            player.sendMessage("§7Default: SLIME_BLOCK");
            return;
        }
        
        double strength;
        try {
            strength = Double.parseDouble(args[1]);
        } catch (NumberFormatException e) {
            player.sendMessage("§cStrength must be a number (e.g., 1.5).");
            return;
        }
        
        if (strength <= 0) {
            player.sendMessage("§cStrength must be positive.");
            return;
        }
        
        Material type = Material.SLIME_BLOCK;
        if (args.length >= 3) {
            try {
                type = Material.valueOf(args[2].toUpperCase());
            } catch (IllegalArgumentException e) {
                player.sendMessage("§cInvalid block type. Valid types: SLIME_BLOCK, STONE_PRESSURE_PLATE, GOLD_PLATE, IRON_PLATE");
                return;
            }
        }
        
        Block target = player.getTargetBlock(null, 10);
        if (target == null || target.getType() == Material.AIR) {
            player.sendMessage("§cYou must be looking at a block.");
            return;
        }
        
        // Save jump pad to config
        Location loc = target.getLocation();
        String key = "jumppads." + loc.getWorld().getName() + "_" + loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ();
        
        FileConfiguration config = plugin.getConfig();
        config.set(key + ".world", loc.getWorld().getName());
        config.set(key + ".x", loc.getBlockX());
        config.set(key + ".y", loc.getBlockY());
        config.set(key + ".z", loc.getBlockZ());
        config.set(key + ".strength", strength);
        config.set(key + ".type", type.toString());
        plugin.saveConfig();
        
        player.sendMessage("§aJump pad added at your target block!");
        player.sendMessage("§7Strength: " + strength + ", Type: " + type.toString());
    }
    
    private void handleRemove(Player player) {
        Block target = player.getTargetBlock(null, 10);
        if (target == null || target.getType() == Material.AIR) {
            player.sendMessage("§cYou must be looking at a block.");
            return;
        }
        
        Location loc = target.getLocation();
        String key = "jumppads." + loc.getWorld().getName() + "_" + loc.getBlockX() + "_" + loc.getBlockY() + "_" + loc.getBlockZ();
        
        FileConfiguration config = plugin.getConfig();
        if (config.contains(key)) {
            config.set(key, null);
            plugin.saveConfig();
            player.sendMessage("§aJump pad removed!");
        } else {
            player.sendMessage("§cNo jump pad found at the targeted block.");
        }
    }
    
    private void handleList(Player player) {
        FileConfiguration config = plugin.getConfig();
        ConfigurationSection section = config.getConfigurationSection("jumppads");
        
        if (section == null || section.getKeys(false).isEmpty()) {
            player.sendMessage("§7No jump pads defined.");
            return;
        }
        
        player.sendMessage("§6Jump Pads (§f" + section.getKeys(false).size() + "§6):");
        int i = 1;
        for (String key : section.getKeys(false)) {
            String world = config.getString(key + ".world");
            int x = config.getInt(key + ".x");
            int y = config.getInt(key + ".y");
            int z = config.getInt(key + ".z");
            double strength = config.getDouble(key + ".strength");
            String type = config.getString(key + ".type", "SLIME_BLOCK");
            
            player.sendMessage(String.format("§7  %d. §f%s: (%d, %d, %d) §7- Strength: §f%.1f §7- Type: §f%s",
                    i++, world, x, y, z, strength, type));
        }
    }
    
    private void handleWand(Player player) {
        // Give player a stick to select blocks
        org.bukkit.inventory.ItemStack wand = new org.bukkit.inventory.ItemStack(Material.BLAZE_ROD);
        org.bukkit.inventory.meta.ItemMeta meta = wand.getItemMeta();
        meta.setDisplayName("§6Jump Pad Wand");
        meta.setLore(java.util.Arrays.asList(
            "§7Right-click a block to add as jump pad",
            "§7Left-click a block to remove jump pad",
            "§7Use /jumppad add <strength> [type] after selecting"
        ));
        wand.setItemMeta(meta);
        player.getInventory().addItem(wand);
        player.sendMessage("§aJump Pad Wand given!");
    }
    
    private void sendUsage(Player player) {
        player.sendMessage("§c§lGraviJet §7» §f§lJump Pad §8- §7Commands");
        player.sendMessage("§4• §c/jumppad add <strength> [type] §7» §fAdd jump pad at targeted block");
        player.sendMessage("§4• §c/jumppad remove §7» §fRemove jump pad at targeted block");
        player.sendMessage("§4• §c/jumppad list §7» §fList all jump pads");
        player.sendMessage("§4• §c/jumppad wand §7» §fGet jump pad selection wand");
        player.sendMessage("§7Types: SLIME_BLOCK, STONE_PRESSURE_PLATE, GOLD_PLATE, IRON_PLATE");
    }
    
    // Listener for jump pads
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() &&
            event.getFrom().getBlockY() == event.getTo().getBlockY() &&
            event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return; // Only rotation changed
        }
        
        Player player = event.getPlayer();
        Location loc = player.getLocation();
        
        // Check cooldown
        UUID uuid = player.getUniqueId();
        Long lastLaunch = cooldowns.get(uuid);
        long now = System.currentTimeMillis();
        if (lastLaunch != null && now - lastLaunch < COOLDOWN_MS) {
            return;
        }
        
        // Check block player is standing on (feet position)
        Block feetBlock = loc.getBlock();
        Block belowBlock = loc.clone().subtract(0, 1, 0).getBlock();
        
        // Check both feet and below block
        if (isJumpPadBlock(feetBlock) || isJumpPadBlock(belowBlock)) {
            Block jumpPadBlock = isJumpPadBlock(feetBlock) ? feetBlock : belowBlock;
            
            // Get jump pad data from config
            String key = "jumppads." + jumpPadBlock.getWorld().getName() + "_" + 
                        jumpPadBlock.getX() + "_" + jumpPadBlock.getY() + "_" + jumpPadBlock.getZ();
            
            FileConfiguration config = plugin.getConfig();
            if (config.contains(key)) {
                double strength = config.getDouble(key + ".strength", 1.5);
                
                // Launch player forward and upward
                Vector direction = player.getLocation().getDirection();
                direction.setY(0.5); // Add upward boost
                direction.normalize();
                direction.multiply(strength);
                
                player.setVelocity(direction);
                cooldowns.put(uuid, now);
                
                // Optional sound effect
                // player.playSound(player.getLocation(), org.bukkit.Sound.ENDERDRAGON_WING, 1.0f, 1.0f);
            }
        }
    }
    
    private boolean isJumpPadBlock(Block block) {
        if (block == null) return false;
        Material type = block.getType();
        return type == Material.SLIME_BLOCK ||
               type == Material.STONE_PLATE ||
               type == Material.GOLD_PLATE ||
               type == Material.IRON_PLATE;
    }
}
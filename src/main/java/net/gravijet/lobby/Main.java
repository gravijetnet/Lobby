package net.gravijet.lobby;

import net.gravijet.lobby.command.*;
import net.gravijet.lobby.jumppad.JumppadListener;
import net.gravijet.lobby.jumppad.JumppadManager;
import net.gravijet.lobby.listener.LobbyListener;
import net.gravijet.lobby.listener.SpeedCookieListener;
import net.gravijet.lobby.selector.ServerSelectorManager;
import net.gravijet.lobby.zone.ZoneListener;
import net.gravijet.lobby.zone.ZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class Main extends JavaPlugin {

    private final Set<UUID> buildModePlayers = new HashSet<>();
    private ScoreboardManager scoreboardManager;
    private ServerSelectorManager serverSelectorManager;
    private ZoneManager zoneManager;
    private JumppadManager jumppadManager;
    private boolean hasPlaceholderAPI = false;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        serverSelectorManager = new ServerSelectorManager(this);
        serverSelectorManager.loadConfig();

        zoneManager = new ZoneManager(this);
        zoneManager.loadZones();
        zoneManager.startParticleTask();

        jumppadManager = new JumppadManager(this);
        jumppadManager.loadJumppads();

        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        GetSpeedCookieCommand cookieCommand = new GetSpeedCookieCommand(this);
        getServer().getPluginManager().registerEvents(new LobbyListener(this, zoneManager), this);
        getServer().getPluginManager().registerEvents(new ZoneListener(this, zoneManager), this);
        getServer().getPluginManager().registerEvents(new JumppadListener(this, jumppadManager), this);
        getServer().getPluginManager().registerEvents(new SpeedCookieListener(this, cookieCommand), this);

        getCommand("build").setExecutor(new BuildCommand(this));
        getCommand("setspawn").setExecutor(new SetSpawnCommand(this));
        getCommand("spawn").setExecutor(new SpawnCommand(this));
        getCommand("fly").setExecutor(new FlyCommand(this));
        getCommand("zone").setExecutor(new ZoneCommand(this, zoneManager));
        getCommand("lobbyreload").setExecutor(new ReloadCommand(this));
        getCommand("jumppad").setExecutor(new JumppadCommand(this, jumppadManager));
        getCommand("getspeedcookie").setExecutor(cookieCommand);

        hasPlaceholderAPI = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;

        Bukkit.setDefaultGameMode(GameMode.SURVIVAL);
        loadSpawnLocation();
        scoreboardManager = Bukkit.getScoreboardManager();

        for (World world : Bukkit.getWorlds()) {
            setupWorld(world);
        }

        Bukkit.getScheduler().runTaskTimer(this, this::updateAllScoreboards, 0L, 20L);
    }

    @Override
    public void onDisable() {
        if (zoneManager != null) zoneManager.stopParticleTask();
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    public void setupWorld(World world) {
        world.setGameRuleValue("doDaylightCycle", "false");
        world.setTime(6000);
        world.setStorm(false);
        world.setThundering(false);
    }

    public void setupPlayer(Player player) {
        if (isInBuildMode(player)) {
            player.setGameMode(GameMode.CREATIVE);
        } else {
            player.setGameMode(GameMode.SURVIVAL);
            if (player.hasPermission("lobby.fly")) {
                player.setAllowFlight(true);
                player.setFlying(true);
            } else {
                player.setAllowFlight(false);
                player.setFlying(false);
            }
        }

        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.getInventory().clear();

        if (!isInBuildMode(player)) {
            setupInventory(player);
        }

        updatePlayerVisibility(player);
        updateScoreboard(player);

        Location spawn = getSpawnLocation();
        if (spawn != null) {
            player.teleport(spawn);
        }
    }

    public void setupInventory(Player player) {
        ItemStack serverSelector = new ItemStack(Material.COMPASS);
        ItemMeta selectorMeta = serverSelector.getItemMeta();
        selectorMeta.setDisplayName("§cServer Selector");
        serverSelector.setItemMeta(selectorMeta);
        player.getInventory().setItem(0, serverSelector);

        ItemStack enderButt = new ItemStack(Material.ENDER_PEARL);
        ItemMeta enderMeta = enderButt.getItemMeta();
        enderMeta.setDisplayName("§cEnder Butt");
        enderButt.setItemMeta(enderMeta);
        player.getInventory().setItem(1, enderButt);

        ItemStack coinshop = new ItemStack(Material.GOLD_INGOT);
        ItemMeta coinshopMeta = coinshop.getItemMeta();
        coinshopMeta.setDisplayName("§cCoinshop");
        coinshop.setItemMeta(coinshopMeta);
        player.getInventory().setItem(2, coinshop);

        ItemStack lobbyBlocks = new ItemStack(Material.SANDSTONE, 64);
        ItemMeta lobbyMeta = lobbyBlocks.getItemMeta();
        lobbyMeta.setDisplayName("§cLobby Blocks");
        lobbyBlocks.setItemMeta(lobbyMeta);
        player.getInventory().setItem(4, lobbyBlocks);

        ItemStack settings = new ItemStack(Material.REDSTONE_TORCH_ON);
        ItemMeta settingsMeta = settings.getItemMeta();
        settingsMeta.setDisplayName("§cSettings");
        settings.setItemMeta(settingsMeta);
        player.getInventory().setItem(6, settings);

        ItemStack friends = new ItemStack(Material.SKULL_ITEM, 1, (short) 3);
        SkullMeta friendsMeta = (SkullMeta) friends.getItemMeta();
        friendsMeta.setDisplayName("§cFriends");
        friendsMeta.setOwner(player.getName());
        friends.setItemMeta(friendsMeta);
        player.getInventory().setItem(7, friends);

        updateVisibilityItem(player);
    }

    public void updateVisibilityItem(Player player) {
        String visibility = getConfig().getString(
                "players." + player.getUniqueId() + ".visibility", "ALL");

        short dyeDamage;
        String displayName;
        switch (visibility) {
            case "VIP":
                dyeDamage = 5;
                displayName = "§5Only VIP players are visible";
                break;
            case "STAFF":
                dyeDamage = 14;
                displayName = "§6Only Staff is visible";
                break;
            case "NONE":
                dyeDamage = 1;
                displayName = "§cNo players are visible";
                break;
            default:
                dyeDamage = 10;
                displayName = "§aAll players are visible";
                break;
        }

        ItemStack item = new ItemStack(Material.INK_SACK, 1, dyeDamage);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(displayName);
        item.setItemMeta(meta);
        player.getInventory().setItem(8, item);
    }

    public void updatePlayerVisibility(Player player) {
        String visibility = getConfig().getString(
                "players." + player.getUniqueId() + ".visibility", "ALL");
        String vipPerm = getConfig().getString("visibility.vip-permission", "lobby.visibility.vip");
        String staffPerm = getConfig().getString("visibility.staff-permission", "lobby.visibility.staff");

        for (Player online : Bukkit.getOnlinePlayers()) {
            switch (visibility) {
                case "VIP":
                    if (online.hasPermission(vipPerm)) player.showPlayer(online);
                    else player.hidePlayer(online);
                    break;
                case "STAFF":
                    if (online.hasPermission(staffPerm)) player.showPlayer(online);
                    else player.hidePlayer(online);
                    break;
                case "NONE":
                    player.hidePlayer(online);
                    break;
                default:
                    player.showPlayer(online);
                    break;
            }
        }
    }

    public void cycleVisibilityMode(Player player) {
        String current = getConfig().getString(
                "players." + player.getUniqueId() + ".visibility", "ALL");
        String next;
        switch (current) {
            case "ALL":
                next = "VIP";
                break;
            case "VIP":
                next = "STAFF";
                break;
            case "STAFF":
                next = "NONE";
                break;
            default:
                next = "ALL";
                break;
        }

        getConfig().set("players." + player.getUniqueId() + ".visibility", next);
        saveConfig();

        updateVisibilityItem(player);
        updatePlayerVisibility(player);
    }

    private void updateAllScoreboards() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateScoreboard(player);
        }
    }

    public void updateScoreboard(Player player) {
        Scoreboard board = scoreboardManager.getNewScoreboard();
        Objective objective = board.registerNewObjective("lobby", "dummy");
        objective.setDisplayName("§c§lexample.invalid");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        int playtime = 0;
        try {
            playtime = Integer.parseInt(
                    getPlaceholder(player, "%phoenix_player_playtime_seconds%")) / 3600;
        } catch (NumberFormatException ignored) {
        }

        int score = 15;
        objective.getScore("§7§m-------------------").setScore(score--);
        objective.getScore("§8» §cRank: §6" + getPlaceholder(player, "%phoenix_player_real_rank%")).setScore(score--);
        objective.getScore("§8» §cPlayers: §6" + getPlaceholder(player, "%phoenix_server_global_online%")).setScore(score--);
        objective.getScore("§8» §cCoins: §6" + getPlaceholder(player, "%pxcosmetics_player_coins%")).setScore(score--);
        objective.getScore("§8» §cLevel: §6" + getPlaceholder(player, "%phoenix_player_level_displayname%")).setScore(score--);
        objective.getScore("§8» §cPlaytime: §6" + playtime + "h").setScore(score--);
        objective.getScore("§f ").setScore(score--);
        objective.getScore("§7§oexample.invalid").setScore(score--);
        objective.getScore("§7§o§m-------------------").setScore(score--);
        player.setScoreboard(board);
    }


    public boolean isInBuildMode(Player player) {
        return buildModePlayers.contains(player.getUniqueId());
    }

    public void clearBuildMode(Player player) {
        buildModePlayers.remove(player.getUniqueId());
    }

    public void setBuildMode(Player player, boolean enable) {
        if (enable) {
            buildModePlayers.add(player.getUniqueId());
            player.setGameMode(GameMode.CREATIVE);
            player.getInventory().clear();
        } else {
            buildModePlayers.remove(player.getUniqueId());
            player.setGameMode(GameMode.SURVIVAL);
            player.getInventory().clear();
            setupInventory(player);
            // Restore flight immediately after exiting build mode
            restoreFlightState(player);
        }
    }

    /**
     * Enables flight for players who have the lobby.fly permission and are not in
     * a no-fly zone; disables it otherwise. Call after any state change that might
     * affect the player's flight eligibility.
     */
    public void restoreFlightState(Player player) {
        if (isInBuildMode(player)) return;
        boolean zoneAllows = zoneManager == null || zoneManager.isFlightAllowedAt(player.getLocation());
        if (player.hasPermission("lobby.fly") && zoneAllows) {
            player.setAllowFlight(true);
            player.setFlying(true);
        } else {
            player.setAllowFlight(false);
            player.setFlying(false);
        }
    }

    public Location getSpawnLocation() {
        if (!getConfig().contains("spawn.world")) return null;

        String worldName = getConfig().getString("spawn.world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;

        double x = getConfig().getDouble("spawn.x");
        double y = getConfig().getDouble("spawn.y");
        double z = getConfig().getDouble("spawn.z");
        float yaw = (float) getConfig().getDouble("spawn.yaw");
        float pitch = (float) getConfig().getDouble("spawn.pitch");
        return new Location(world, x, y, z, yaw, pitch);
    }

    public void setSpawnLocation(Location loc) {
        getConfig().set("spawn.world", loc.getWorld().getName());
        getConfig().set("spawn.x", loc.getX());
        getConfig().set("spawn.y", loc.getY());
        getConfig().set("spawn.z", loc.getZ());
        getConfig().set("spawn.yaw", loc.getYaw());
        getConfig().set("spawn.pitch", loc.getPitch());
        saveConfig();
    }

    private void loadSpawnLocation() {
        if (!getConfig().contains("spawn.world")) {
            World world = Bukkit.getWorld("spawn");
            if (world == null && !Bukkit.getWorlds().isEmpty()) {
                world = Bukkit.getWorlds().get(0);
            }
            if (world != null) {
                setSpawnLocation(new Location(world, 20.5, 112, -173.5, -135, 0));
            }
        }
    }

    public ServerSelectorManager getServerSelectorManager() {
        return serverSelectorManager;
    }

    public ZoneManager getZoneManager() {
        return zoneManager;
    }

    public JumppadManager getJumppadManager() {
        return jumppadManager;
    }

    public void reloadPluginConfig() {
        reloadConfig();

        serverSelectorManager.loadConfig();

        zoneManager.stopParticleTask();
        zoneManager.loadZones();
        zoneManager.startParticleTask();

        jumppadManager.loadJumppads();

        for (World world : Bukkit.getWorlds()) {
            setupWorld(world);
        }

        hasPlaceholderAPI = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;

        for (Player player : Bukkit.getOnlinePlayers()) {
            updatePlayerVisibility(player);
            updateVisibilityItem(player);
            updateScoreboard(player);
        }

        getLogger().info("Configuration reloaded successfully!");
    }

    private String getPlaceholder(Player player, String placeholder) {
        if (hasPlaceholderAPI) {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, placeholder);
        }
        return placeholder;
    }
}

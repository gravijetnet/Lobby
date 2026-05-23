package net.gravijet.lobby;

import net.gravijet.lobby.command.*;
import net.gravijet.lobby.listener.LobbyListener;
import net.gravijet.lobby.listener.ProtocolCheckListener;
import net.gravijet.lobby.listener.SpeedCookieListener;
import net.gravijet.lobby.selector.ServerSelectorManager;
import net.gravijet.lobby.zone.ZoneListener;
import net.gravijet.lobby.zone.ZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Main extends JavaPlugin {

    private final Set<UUID> buildModePlayers = new HashSet<>();
    private final Set<UUID> flightDisabledByUser = new HashSet<>();
    private final Map<UUID, List<String>> scoreboardCache = new HashMap<>();
    private FileConfiguration playersConfig;
    private File playersFile;
    private ScoreboardManager scoreboardManager;
    private ServerSelectorManager serverSelectorManager;
    private ZoneManager zoneManager;
    private ZoneListener zoneListener;
    private net.gravijet.lobby.listener.LobbyListener lobbyListener;
    private VisibilityManager visibilityManager;
    private LobbyBlockManager lobbyBlockManager;
    private MessagesManager messagesManager;
    private boolean hasPlaceholderAPI = false;

    private void loadPlayersConfig() {
        playersFile = new File(getDataFolder(), "players.yml");
        if (!playersFile.exists()) {
            try { playersFile.createNewFile(); } catch (IOException e) { getLogger().warning("Could not create players.yml"); }
        }
        playersConfig = YamlConfiguration.loadConfiguration(playersFile);
    }

    public void savePlayersConfig() {
        if (playersConfig == null || playersFile == null) return;
        try { playersConfig.save(playersFile); } catch (IOException e) { getLogger().warning("Could not save players.yml"); }
    }

    public FileConfiguration getPlayersConfig() {
        return playersConfig;
    }

    public void savePlayerFlightPreference(Player player) {
        if (playersConfig == null) return;
        boolean prefersFlight = isInBuildMode(player) || !isFlightDisabledByUser(player);
        playersConfig.set(player.getUniqueId() + ".prefers-flight", prefersFlight);
        savePlayersConfig();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadPlayersConfig();

        messagesManager = new MessagesManager(this);

        serverSelectorManager = new ServerSelectorManager(this);
        serverSelectorManager.loadConfig();

        visibilityManager = new VisibilityManager(this);
        visibilityManager.migrateFromMainConfig();

        zoneManager = new ZoneManager(this);
        zoneManager.loadZones();
        zoneManager.startParticleTask();

        lobbyBlockManager = new LobbyBlockManager(this);
        lobbyBlockManager.startCleanupTask();

        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        GetSpeedCookieCommand cookieCommand = new GetSpeedCookieCommand(this);
        lobbyListener = new LobbyListener(this, zoneManager, lobbyBlockManager);
        getServer().getPluginManager().registerEvents(lobbyListener, this);
        zoneListener = new ZoneListener(this, zoneManager);
        getServer().getPluginManager().registerEvents(zoneListener, this);
        getServer().getPluginManager().registerEvents(new SpeedCookieListener(this, cookieCommand), this);
        getServer().getPluginManager().registerEvents(new ProtocolCheckListener(this), this);

        getCommand("build").setExecutor(new BuildCommand(this));
        getCommand("setspawn").setExecutor(new SetSpawnCommand(this));
        getCommand("spawn").setExecutor(new SpawnCommand(this));
        getCommand("fly").setExecutor(new FlyCommand(this));
        getCommand("zone").setExecutor(new ZoneCommand(this, zoneManager));
        getCommand("lobbyreload").setExecutor(new ReloadCommand(this));
        getCommand("getspeedcookie").setExecutor(cookieCommand);

        hasPlaceholderAPI = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;

        loadSpawnLocation();
        scoreboardManager = Bukkit.getScoreboardManager();

        for (World world : Bukkit.getWorlds()) {
            setupWorld(world);
        }

        // Worlds are now loaded — safe to clean up any persisted lobby blocks
        lobbyBlockManager.removeAllLobbyBlocks();

        Bukkit.getScheduler().runTaskTimer(this, this::updateAllScoreboards, 0L, 20L);

        for (Player player : new ArrayList<>(Bukkit.getOnlinePlayers())) {
            setupPlayer(player);
        }
    }

    @Override
    public void onDisable() {
        if (lobbyListener != null) lobbyListener.stopTasks();
        if (zoneListener != null) zoneListener.stopTasks();
        if (zoneManager != null) zoneManager.stopParticleTask();
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean prefersFlight = isInBuildMode(player) || !isFlightDisabledByUser(player);
            if (playersConfig != null) {
                playersConfig.set(player.getUniqueId() + ".prefers-flight", prefersFlight);
            }
        }
        savePlayersConfig();
        saveConfig();

        // Remove all lobby blocks on disable
        if (lobbyBlockManager != null) {
            lobbyBlockManager.removeAllLobbyBlocks();
        }
    }

    public void setupWorld(World world) {
        world.setGameRuleValue("doDaylightCycle", "false");
        world.setGameRuleValue("doWeatherCycle", "false");
        world.setTime(6000);
        world.setStorm(false);
        world.setThundering(false);
    }

    public void setupPlayer(Player player) {
        // Ensure player is not in build mode when joining
        buildModePlayers.remove(player.getUniqueId());

        player.setGameMode(GameMode.SURVIVAL);
        player.setHealth(20.0);
        player.setFoodLevel(20);
        player.setSaturation(20f);
        player.getInventory().clear();

        setupInventory(player);

        refreshVisibilityForJoin(player);
        updateScoreboard(player);

        boolean prefersFlight = (playersConfig != null)
                ? playersConfig.getBoolean(player.getUniqueId() + ".prefers-flight", player.hasPermission("lobby.fly"))
                : player.hasPermission("lobby.fly");
        setFlightPreference(player, prefersFlight);

        UUID uid = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Player p = Bukkit.getPlayer(uid);
            if (p == null || !p.isOnline()) return;
            Location spawn = getSpawnLocation();
            if (spawn != null) {
                p.teleport(spawn);
            }
            restoreFlightState(p);
        }, 1L);
    }

    public void cleanupPlayerState(UUID uuid) {
        flightDisabledByUser.remove(uuid);
        buildModePlayers.remove(uuid);
    }

    public void setupInventory(Player player) {
        ItemStack serverSelector = new ItemStack(Material.COMPASS);
        ItemMeta selectorMeta = serverSelector.getItemMeta();
        if (selectorMeta != null) {
            selectorMeta.setDisplayName("§cServer Selector");
            serverSelector.setItemMeta(selectorMeta);
        }
        player.getInventory().setItem(0, serverSelector);

        ItemStack enderButt = new ItemStack(Material.ENDER_PEARL);
        ItemMeta enderMeta = enderButt.getItemMeta();
        if (enderMeta != null) {
            enderMeta.setDisplayName("§cEnder Butt");
            enderButt.setItemMeta(enderMeta);
        }
        player.getInventory().setItem(1, enderButt);

        ItemStack coinshop = new ItemStack(Material.GOLD_INGOT);
        ItemMeta coinshopMeta = coinshop.getItemMeta();
        if (coinshopMeta != null) {
            coinshopMeta.setDisplayName("§cCoinshop");
            coinshop.setItemMeta(coinshopMeta);
        }
        player.getInventory().setItem(2, coinshop);

        ItemStack lobbyBlocks = new ItemStack(Material.SANDSTONE, 64);
        ItemMeta lobbyMeta = lobbyBlocks.getItemMeta();
        if (lobbyMeta != null) {
            lobbyMeta.setDisplayName("§cBlocks");
            lobbyBlocks.setItemMeta(lobbyMeta);
        }
        player.getInventory().setItem(4, lobbyBlocks);

        ItemStack settings = new ItemStack(Material.REDSTONE_TORCH_ON);
        ItemMeta settingsMeta = settings.getItemMeta();
        if (settingsMeta != null) {
            settingsMeta.setDisplayName("§cSettings");
            settings.setItemMeta(settingsMeta);
        }
        player.getInventory().setItem(6, settings);

        ItemStack friends = new ItemStack(Material.SKULL_ITEM, 1, (short) 3);
        ItemMeta friendsRawMeta = friends.getItemMeta();
        if (friendsRawMeta instanceof SkullMeta) {
            SkullMeta friendsMeta = (SkullMeta) friendsRawMeta;
            friendsMeta.setDisplayName("§cFriends");
            friendsMeta.setOwner(player.getName());
            friends.setItemMeta(friendsMeta);
        }
        player.getInventory().setItem(7, friends);

        updateVisibilityItem(player);
    }

    public void updateVisibilityItem(Player player) {
        String visibility = visibilityManager.getPlayerVisibility(player.getUniqueId());

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
        if (meta != null) {
            meta.setDisplayName(displayName);
            item.setItemMeta(meta);
        }
        player.getInventory().setItem(8, item);
    }

    public void updatePlayerVisibility(Player player) {
        String vipPerm = getConfig().getString("visibility.vip-permission", "lobby.visibility.vip");
        String staffPerm = getConfig().getString("visibility.staff-permission", "lobby.visibility.staff");
        String visibility = visibilityManager.getPlayerVisibility(player.getUniqueId());

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(player)) continue;
            applyVisibility(player, online, visibility, vipPerm, staffPerm);
        }
    }

    /**
     * Single-pass join visibility sync: applies the joining player's own preference toward
     * everyone, and every existing player's preference toward the joining player.
     */
    public void refreshVisibilityForJoin(Player target) {
        String vipPerm = getConfig().getString("visibility.vip-permission", "lobby.visibility.vip");
        String staffPerm = getConfig().getString("visibility.staff-permission", "lobby.visibility.staff");
        String targetVisibility = visibilityManager.getPlayerVisibility(target.getUniqueId());

        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(target)) continue;
            // Apply joining player's preference toward this viewer
            applyVisibility(target, viewer, targetVisibility, vipPerm, staffPerm);
            // Apply this viewer's preference toward the joining player
            String viewerVisibility = visibilityManager.getPlayerVisibility(viewer.getUniqueId());
            applyVisibility(viewer, target, viewerVisibility, vipPerm, staffPerm);
        }
    }

    private void applyVisibility(Player viewer, Player target, String visibility,
                                 String vipPerm, String staffPerm) {
        boolean show;
        switch (visibility) {
            case "VIP":
                show = target.hasPermission(vipPerm);
                break;
            case "STAFF":
                show = target.hasPermission(staffPerm);
                break;
            case "NONE":
                show = false;
                break;
            default:
                show = true;
                break;
        }
        if (show) viewer.showPlayer(target);
        else viewer.hidePlayer(target);
    }

    public void cycleVisibilityMode(Player player) {
        String current = visibilityManager.getPlayerVisibility(player.getUniqueId());
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

        visibilityManager.setPlayerVisibility(player.getUniqueId(), next);

        updateVisibilityItem(player);
        updatePlayerVisibility(player);
    }

    private void updateAllScoreboards() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateScoreboard(player);
        }
    }

    public void updateScoreboard(Player player) {
        if (scoreboardManager == null) return;
        Scoreboard board = player.getScoreboard();
        Objective objective = board.getObjective(DisplaySlot.SIDEBAR);

        // If the player doesn't have our scoreboard, or the objective is not ours, create and assign it.
        boolean freshBoard = objective == null || !objective.getName().equals("lobby");
        if (freshBoard) {
            board = scoreboardManager.getNewScoreboard();
            objective = board.registerNewObjective("lobby", "dummy");
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            player.setScoreboard(board);
            scoreboardCache.remove(player.getUniqueId());
        }

        long playtime = 0;
        try {
            String playtimeValue = getPlaceholder(player, "%phoenix_player_playtime_seconds%");
            if (playtimeValue != null && !playtimeValue.startsWith("%")) {
                playtime = Long.parseLong(playtimeValue) / 3600;
            }
        } catch (NumberFormatException ignored) {
            // Ignored if placeholder is not a number
        }

        // Each line is prefixed with a unique invisible padding (§r§0, §r§1, …) so
        // duplicate placeholder values never collide as scoreboard score keys.
        List<String> lines = Arrays.asList(
                "§r§0§7§m-------------------",
                "§r§1§8» §cRank: §6" + getPlaceholder(player, "%phoenix_player_real_rank%"),
                "§r§2§8» §cPlayers: §6" + getPlaceholder(player, "%phoenix_server_global_online%"),
                "§r§3§8» §cCoins: §6" + getPlaceholder(player, "%pxcosmetics_player_coins%"),
                "§r§4§8» §cLevel: §6" + getPlaceholder(player, "%phoenix_player_level_displayname%"),
                "§r§5§8» §cPlaytime: §6" + playtime + "h",
                "§r§6 ",
                "§r§7§7§oexample.invalid",
                "§r§8§7§o§m-------------------"
        );

        // Skip the costly teardown/rebuild (and packet spam/flicker) when nothing changed.
        if (lines.equals(scoreboardCache.get(player.getUniqueId()))) {
            return;
        }

        objective.setDisplayName("§c§lexample.invalid");

        // Clear all old scores to prevent duplicates and remove old lines.
        // Copy the set first — resetScores mutates the backing collection in 1.8.8.
        for (String entry : new ArrayList<>(board.getEntries())) {
            board.resetScores(entry);
        }

        int score = lines.size();
        for (String line : lines) {
            objective.getScore(line).setScore(score--);
        }

        scoreboardCache.put(player.getUniqueId(), new ArrayList<>(lines));
    }

    public void clearScoreboardCache(UUID uuid) {
        scoreboardCache.remove(uuid);
    }

    public boolean isFlightDisabledByUser(Player player) {
        return flightDisabledByUser.contains(player.getUniqueId());
    }

    public void setFlightPreference(Player player, boolean wantsFlight) {
        if (wantsFlight) {
            flightDisabledByUser.remove(player.getUniqueId());
        } else {
            flightDisabledByUser.add(player.getUniqueId());
        }
    }

    public boolean isInBuildMode(Player player) {
        return buildModePlayers.contains(player.getUniqueId());
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
            restoreFlightState(player);
        }
    }

    public void restoreFlightState(Player player) {
        if (isInBuildMode(player)) return;

        if (isFlightDisabledByUser(player)) {
            player.setAllowFlight(false);
            player.setFlying(false);
            return;
        }

        boolean zoneAllows = zoneManager == null || zoneManager.isFlightAllowedAt(player.getLocation());
        boolean hasPermission = player.hasPermission("lobby.fly");

        if (hasPermission && zoneAllows) {
            player.setAllowFlight(true);
        } else {
            player.setAllowFlight(false);
            player.setFlying(false);
        }
    }

    public Location getSpawnLocation() {
        if (!getConfig().contains("spawn.world")) return null;

        String worldName = getConfig().getString("spawn.world");
        if (worldName == null) return null;
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
        if (loc.getWorld() == null) return;
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

    public MessagesManager getMessages() {
        return messagesManager;
    }

    public ServerSelectorManager getServerSelectorManager() {
        return serverSelectorManager;
    }

    public ZoneManager getZoneManager() {
        return zoneManager;
    }

    public VisibilityManager getVisibilityManager() {
        return visibilityManager;
    }

    public void reloadPluginConfig() {
        reloadConfig();

        serverSelectorManager.loadConfig();
        visibilityManager.reloadVisibilityConfig();

        zoneManager.stopParticleTask();
        zoneManager.loadZones();
        zoneManager.startParticleTask();

        if (lobbyBlockManager != null) {
            lobbyBlockManager.reloadLobbyBlocksConfig();
        }

        messagesManager.load();

        for (World world : Bukkit.getWorlds()) {
            setupWorld(world);
        }

        hasPlaceholderAPI = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;

        // Remove cache entries for players who are no longer online
        Set<UUID> onlineUUIDs = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) onlineUUIDs.add(p.getUniqueId());
        scoreboardCache.keySet().retainAll(onlineUUIDs);

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

        // Fallback values when PlaceholderAPI is not available
        switch (placeholder) {
            case "%phoenix_player_playtime_seconds%":
                return "0";
            case "%vault_eco_balance_formatted%":
            case "%pxcosmetics_player_coins%":
                return "0";
            case "%luckperms_prefix%":
            case "%phoenix_player_real_rank%":
                return "Player";
            case "%phoenix_server_global_online%":
                return String.valueOf(Bukkit.getOnlinePlayers().size());
            case "%phoenix_player_level_displayname%":
                return "1";
            default:
                return "";
        }
    }

    public boolean hasPlaceholderAPI() {
        return hasPlaceholderAPI;
    }
}

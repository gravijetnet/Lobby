package net.gravijet.lobby;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.*;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class Main extends JavaPlugin {
    private Set<UUID> buildModePlayers = new HashSet<>();
    private ScoreboardManager scoreboardManager;
    private boolean hasPlaceholderAPI = false;
    private boolean hasPhoenixAPI = false;
    private ServerSelectorManager serverSelectorManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        serverSelectorManager = new ServerSelectorManager(this);
        serverSelectorManager.loadConfig();

        // Register BungeeCord channel
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        getServer().getPluginManager().registerEvents(new LobbyListener(this), this);
        getCommand("build").setExecutor(new BuildCommand(this));
        getCommand("setspawn").setExecutor(new SetSpawnCommand(this));
        getCommand("spawn").setExecutor(new SpawnCommand(this));
        getCommand("fly").setExecutor(new FlyCommand(this));

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            hasPlaceholderAPI = true;
            getLogger().info("PlaceholderAPI found - enabling placeholder support");
        } else {
            getLogger().warning("PlaceholderAPI not found - placeholders will not work");
        }

        if (Bukkit.getPluginManager().getPlugin("PhoenixAPI") != null) {
            hasPhoenixAPI = true;
            getLogger().info("PhoenixAPI found - enabling rank support");
        } else {
            getLogger().warning("PhoenixAPI not found - rank prefixes will not work");
        }

        for (World world : Bukkit.getWorlds()) {
            setupWorld(world);
        }

        loadSpawnLocation();
        scoreboardManager = Bukkit.getScoreboardManager();
        startScoreboardUpdater();
        startTablistUpdater();
    }

    @Override
    public void onDisable() {
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    private void startScoreboardUpdater() {
        Bukkit.getScheduler().runTaskTimer(this, this::updateAllScoreboards, 0L, 20L);
    }

    private void startTablistUpdater() {
        Bukkit.getScheduler().runTaskTimer(this, this::updateAllTablists, 0L, 20L);
    }

    public void updateAllScoreboards() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateScoreboard(player);
        }
    }

    public void updateAllTablists() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateTablist(player);
        }
    }

    public void updateScoreboard(Player player) {
        Scoreboard board = scoreboardManager.getNewScoreboard();
        // 1.8 API: registerNewObjective only takes 2 parameters; display name is set separately
        Objective objective = board.registerNewObjective("lobby", "dummy");
        objective.setDisplayName("§c§lGravijet.net");
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);

        int score = 15;

        int playtime = 0;
        try {
            playtime = Integer.parseInt(getPlaceholder(player, "%phoenix_player_playtime_seconds%")) / 3600;
        } catch (NumberFormatException e) {
            playtime = 0;
        }

        // Each entry must be unique
        objective.getScore("§7§m-------------------").setScore(score--);
        objective.getScore("§8» §cRank: §6" + getPlaceholder(player, "%phoenix_player_real_rank%")).setScore(score--);
        objective.getScore("§8» §cPlayers: §6" + getPlaceholder(player, "%phoenix_server_global_online%")).setScore(score--);
        objective.getScore("§8» §cCoins: §6" + getPlaceholder(player, "%pxcosmetics_player_coins%")).setScore(score--);
        objective.getScore("§8» §cLevel: §6" + getPlaceholder(player, "%phoenix_player_level_displayname%")).setScore(score--);
        objective.getScore("§8» §cPlaytime: §6" + playtime + "§6h").setScore(score--);
        objective.getScore("§f ").setScore(score--);
        objective.getScore("§7§ogravijet.net").setScore(score--);
        objective.getScore("§7§o§m-------------------").setScore(score--);

        // Attach nametag teams to the same scoreboard so they are not lost on update
        if (hasPhoenixAPI) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                addNametagToBoard(board, online);
            }
        }

        player.setScoreboard(board);
    }

    private void addNametagToBoard(Scoreboard board, Player online) {
        try {
            Object profileManager = getPhoenixProfileManager();
            if (profileManager == null) return;
            Object profile = getProfileByPlayer(profileManager, online);
            if (profile == null) return;
            Object rank = getRankFromProfile(profile);
            if (rank == null) return;
            String prefix = getRankPrefix(rank);
            if (prefix == null) return;

            Team team = board.getTeam(online.getName());
            if (team == null) {
                team = board.registerNewTeam(online.getName());
            }
            if (!team.hasEntry(online.getName())) {
                team.addEntry(online.getName());
            }

            String formattedPrefix = ChatColor.translateAlternateColorCodes('&', prefix);
            // Prefix limited to 16 characters (1.8 limit)
            if (formattedPrefix.length() > 16) {
                formattedPrefix = formattedPrefix.substring(0, 16);
            }
            team.setPrefix(formattedPrefix);
            // team.setColor() does not exist in 1.8 API — omitted
        } catch (Exception e) {
            // Ignore errors thrown by PhoenixAPI
        }
    }

    public void updateTablist(Player player) {
        if (!hasPhoenixAPI) return;

        try {
            Object profileManager = getPhoenixProfileManager();
            if (profileManager == null) return;

            Object profile = getProfileByPlayer(profileManager, player);
            if (profile == null) return;

            Object rank = getRankFromProfile(profile);
            if (rank == null) return;

            String prefix = getRankPrefix(rank);
            if (prefix != null && !prefix.isEmpty()) {
                String displayName = ChatColor.translateAlternateColorCodes('&', prefix) + " " + player.getName();
                player.setPlayerListName(displayName);
            }
        } catch (Exception e) {
            getLogger().warning("Error updating tab list for " + player.getName() + ": " + e.getMessage());
        }
    }

    // PhoenixAPI reflection helpers
    private Object getPhoenixProfileManager() {
        try {
            Class<?> phoenixClass = Class.forName("xyz.refinedev.phoenix.Phoenix");
            Object phoenixInstance = phoenixClass.getMethod("getInstance").invoke(null);
            return phoenixClass.getMethod("getProfileManager").invoke(phoenixInstance);
        } catch (Exception e) {
            return null;
        }
    }

    private Object getProfileByPlayer(Object profileManager, Player player) {
        try {
            return profileManager.getClass().getMethod("getByPlayer", Player.class).invoke(profileManager, player);
        } catch (Exception e) {
            return null;
        }
    }

    private Object getRankFromProfile(Object profile) {
        try {
            return profile.getClass().getMethod("getRank").invoke(profile);
        } catch (Exception e) {
            return null;
        }
    }

    private String getRankPrefix(Object rank) {
        try {
            return (String) rank.getClass().getMethod("getPrefix").invoke(rank);
        } catch (Exception e) {
            return null;
        }
    }

    private ChatColor getRankColor(Object rank) {
        try {
            return (ChatColor) rank.getClass().getMethod("getColor").invoke(rank);
        } catch (Exception e) {
            return null;
        }
    }

    private String getPlaceholder(Player player, String placeholder) {
        if (hasPlaceholderAPI) {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, placeholder);
        }
        // Fallback values when PlaceholderAPI is not available
        switch (placeholder) {
            case "%phoenix_player_rank%":
                if (player.hasPermission("lobby.visibility.staff")) return "Staff";
                if (player.hasPermission("lobby.visibility.vip")) return "VIP";
                return "Player";
            case "%phoenix_server_global_online%":
                return String.valueOf(Bukkit.getOnlinePlayers().size());
            case "%pxcosmetics_player_coins%":
                return "0";
            case "%phoenix_player_level_displayname%":
                return "1";
            case "%phoenix_player_playtime_seconds%":
                return "0";
            default:
                return placeholder;
        }
    }

    public void setupWorld(World world) {
        world.setGameRuleValue("doDaylightCycle", "false");
        world.setTime(6000);
        world.setStorm(false);
        world.setThundering(false);
    }

    public void setupPlayer(Player player) {
        // Auto-fly for players with permission — always enable
        if (player.hasPermission("lobby.fly")) {
            player.setAllowFlight(true);
            player.setFlying(true);
        } else {
            player.setAllowFlight(false);
            player.setFlying(false);
        }

        if (isInBuildMode(player)) {
            player.setGameMode(GameMode.CREATIVE);
        } else {
            player.setGameMode(GameMode.ADVENTURE);
        }

        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 1, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP, Integer.MAX_VALUE, 0, false, false));

        // Clear inventory (no setItemInOffHand in 1.8)
        player.getInventory().clear();

        setupInventory(player);
        updatePlayerVisibility(player);
        updateScoreboard(player);
        updateTablist(player);

        Location spawn = getSpawnLocation();
        if (spawn != null) {
            player.teleport(spawn);
        }
    }

    public void setupInventory(Player player) {
        // Slot 0: Server Selector (compass)
        ItemStack serverSelector = new ItemStack(Material.COMPASS);
        ItemMeta selectorMeta = serverSelector.getItemMeta();
        selectorMeta.setDisplayName("§cServer Selector");
        serverSelector.setItemMeta(selectorMeta);
        player.getInventory().setItem(0, serverSelector);

        // Slot 1: Ender Butt
        ItemStack enderButt = new ItemStack(Material.ENDER_PEARL);
        ItemMeta enderMeta = enderButt.getItemMeta();
        enderMeta.setDisplayName("§cEnder Butt");
        enderButt.setItemMeta(enderMeta);
        player.getInventory().setItem(1, enderButt);

        // Slot 2: Coinshop (gold ingot)
        ItemStack coinshop = new ItemStack(Material.GOLD_INGOT);
        ItemMeta coinshopMeta = coinshop.getItemMeta();
        coinshopMeta.setDisplayName("§cCoinshop");
        coinshop.setItemMeta(coinshopMeta);
        player.getInventory().setItem(2, coinshop);

        // Slot 6: Settings
        ItemStack settings = new ItemStack(Material.REDSTONE_TORCH_ON);
        ItemMeta settingsMeta = settings.getItemMeta();
        settingsMeta.setDisplayName("§cSettings");
        settings.setItemMeta(settingsMeta);
        player.getInventory().setItem(6, settings);

        // Slot 7: Friends (player skull) — 1.8: SKULL_ITEM with damage 3
        ItemStack friends = new ItemStack(Material.SKULL_ITEM, 1, (short) 3);
        SkullMeta friendsMeta = (SkullMeta) friends.getItemMeta();
        friendsMeta.setDisplayName("§cFriends");
        friendsMeta.setOwner(player.getName());
        friends.setItemMeta(friendsMeta);
        player.getInventory().setItem(7, friends);

        // Slot 8: Visibility toggle
        updateVisibilityItem(player);

        // Slots 3, 4, 5 remain empty
    }

    public void updateVisibilityItem(Player player) {
        String visibility = getConfig().getString("players." + player.getUniqueId() + ".visibility", "ALL");

        // 1.8: no LIME_DYE etc. — use INK_SACK with damage values
        short dyeDamage;
        String displayName;
        switch (visibility) {
            case "VIP":
                dyeDamage = 5;  // purple
                displayName = "§5Only VIP players are visible";
                break;
            case "STAFF":
                dyeDamage = 14; // orange
                displayName = "§6Only Staff is visible";
                break;
            case "NONE":
                dyeDamage = 1;  // red
                displayName = "§cNo players are visible";
                break;
            default:
                dyeDamage = 10; // lime
                displayName = "§cAll players are visible";
                break;
        }

        ItemStack visibilityItem = new ItemStack(Material.INK_SACK, 1, dyeDamage);
        ItemMeta meta = visibilityItem.getItemMeta();
        meta.setDisplayName(displayName);
        visibilityItem.setItemMeta(meta);
        player.getInventory().setItem(8, visibilityItem);
    }

    public void updatePlayerVisibility(Player player) {
        String visibility = getConfig().getString("players." + player.getUniqueId() + ".visibility", "ALL");

        // Read permission nodes from config (configurable in config.yml under visibility:)
        String vipPerm = getConfig().getString("visibility.vip-permission", "lobby.visibility.vip");
        String staffPerm = getConfig().getString("visibility.staff-permission", "lobby.visibility.staff");

        // 1.8 API: showPlayer/hidePlayer has no plugin parameter
        for (Player online : Bukkit.getOnlinePlayers()) {
            switch (visibility) {
                case "VIP":
                    if (online.hasPermission(vipPerm)) {
                        player.showPlayer(online);
                    } else {
                        player.hidePlayer(online);
                    }
                    break;
                case "STAFF":
                    if (online.hasPermission(staffPerm)) {
                        player.showPlayer(online);
                    } else {
                        player.hidePlayer(online);
                    }
                    break;
                case "NONE":
                    player.hidePlayer(online);
                    break;
                default:
                    player.showPlayer(online);
                    break;
            }
        }

        Bukkit.getScheduler().runTask(this, () -> updateTablist(player));
    }

    public void cycleVisibilityMode(Player player) {
        String current = getConfig().getString("players." + player.getUniqueId() + ".visibility", "ALL");
        String next;

        switch (current) {
            case "ALL":   next = "VIP";   break;
            case "VIP":   next = "STAFF"; break;
            case "STAFF": next = "NONE";  break;
            default:      next = "ALL";   break;
        }

        getConfig().set("players." + player.getUniqueId() + ".visibility", next);
        saveConfig();

        updateVisibilityItem(player);
        updatePlayerVisibility(player);

        // Update tab list for all players after visibility change
        Bukkit.getScheduler().runTask(this, () -> {
            for (Player online : Bukkit.getOnlinePlayers()) {
                updateTablist(online);
            }
        });
    }

    public boolean isInBuildMode(Player player) {
        return buildModePlayers.contains(player.getUniqueId());
    }

    /**
     * Removes the player from the build mode set without side effects
     * (no inventory reset, no game mode change).
     * Use on join/quit where setupPlayer is called afterwards or the player is already gone.
     */
    public void clearBuildMode(Player player) {
        buildModePlayers.remove(player.getUniqueId());
    }

    public void setBuildMode(Player player, boolean buildMode) {
        if (buildMode) {
            buildModePlayers.add(player.getUniqueId());
            player.setGameMode(GameMode.CREATIVE);
            player.getInventory().clear();
        } else {
            buildModePlayers.remove(player.getUniqueId());
            player.setGameMode(GameMode.ADVENTURE);
            player.getInventory().clear();
            setupInventory(player);
        }
    }

    public Location getSpawnLocation() {
        if (!getConfig().contains("spawn.world")) {
            return null;
        }

        String worldName = getConfig().getString("spawn.world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }

        double x = getConfig().getDouble("spawn.x");
        double y = getConfig().getDouble("spawn.y");
        double z = getConfig().getDouble("spawn.z");
        float yaw = (float) getConfig().getDouble("spawn.yaw");
        float pitch = (float) getConfig().getDouble("spawn.pitch");

        return new Location(world, x, y, z, yaw, pitch);
    }

    public void setSpawnLocation(Location location) {
        getConfig().set("spawn.world", location.getWorld().getName());
        getConfig().set("spawn.x", location.getX());
        getConfig().set("spawn.y", location.getY());
        getConfig().set("spawn.z", location.getZ());
        getConfig().set("spawn.yaw", location.getYaw());
        getConfig().set("spawn.pitch", location.getPitch());
        saveConfig();
    }

    private void loadSpawnLocation() {
        if (!getConfig().contains("spawn.world")) {
            World world = Bukkit.getWorld("spawn");
            if (world == null && !Bukkit.getWorlds().isEmpty()) {
                world = Bukkit.getWorlds().get(0);
            }
            if (world != null) {
                Location defaultSpawn = new Location(world, 20.5, 112, -173.5, -135, 0);
                setSpawnLocation(defaultSpawn);
            }
        }
    }

    public ServerSelectorManager getServerSelectorManager() {
        return serverSelectorManager;
    }
}

package net.gravijet.lobby;

import net.gravijet.lobby.command.BuildCommand;
import net.gravijet.lobby.command.FlyCommand;
import net.gravijet.lobby.command.SetSpawnCommand;
import net.gravijet.lobby.command.SpawnCommand;
import net.gravijet.lobby.command.ZoneCommand;
import net.gravijet.lobby.listener.LobbyListener;
import net.gravijet.lobby.selector.ServerSelectorManager;
import net.gravijet.lobby.zone.ZoneListener;
import net.gravijet.lobby.zone.JumpPadManager;
import net.gravijet.lobby.listener.JumpPadListener;
import net.gravijet.lobby.zone.ZoneManager;
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
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class Main extends JavaPlugin {

    // -------------------------------------------------------------------------
    // Fields
    // -------------------------------------------------------------------------

    private final Set<UUID> buildModePlayers = new HashSet<>();
    private ScoreboardManager    scoreboardManager;
    private ServerSelectorManager serverSelectorManager;
    private ZoneManager           zoneManager;
    private JumpPadManager        jumpPadManager;
    private boolean hasPlaceholderAPI = false;
    private boolean hasPhoenixAPI     = false;

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onEnable() {
        saveDefaultConfig();

        serverSelectorManager = new ServerSelectorManager(this);
        serverSelectorManager.loadConfig();

        zoneManager = new ZoneManager(this);
        zoneManager.loadZones();
        zoneManager.startParticleTask();

        jumpPadManager = new JumpPadManager(this);
        jumpPadManager.loadJumpPads();

        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");

        getServer().getPluginManager().registerEvents(new LobbyListener(this, zoneManager), this);
        getServer().getPluginManager().registerEvents(new ZoneListener(this, zoneManager),  this);
        getServer().getPluginManager().registerEvents(new JumpPadListener(jumpPadManager), this);

        getCommand("build").setExecutor(new BuildCommand(this));
        getCommand("setspawn").setExecutor(new SetSpawnCommand(this));
        getCommand("spawn").setExecutor(new SpawnCommand(this));
        getCommand("fly").setExecutor(new FlyCommand(this));
        getCommand("zone").setExecutor(new ZoneCommand(this, zoneManager));

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            hasPlaceholderAPI = true;
            getLogger().info("PlaceholderAPI found — placeholder support enabled.");
        } else {
            getLogger().warning("PlaceholderAPI not found — placeholders will not resolve.");
        }

        if (Bukkit.getPluginManager().getPlugin("PhoenixAPI") != null) {
            hasPhoenixAPI = true;
            getLogger().info("PhoenixAPI found — rank support enabled.");
        } else {
            getLogger().warning("PhoenixAPI not found — rank prefixes will not resolve.");
        }

        Bukkit.setDefaultGameMode(GameMode.SURVIVAL);
        loadSpawnLocation();
        scoreboardManager = Bukkit.getScoreboardManager();

        for (World world : Bukkit.getWorlds()) {
            setupWorld(world);
        }

        Bukkit.getScheduler().runTaskTimer(this, this::updateAllScoreboards, 0L, 20L);
        Bukkit.getScheduler().runTaskTimer(this, this::updateAllTablists,   0L, 20L);
    }

    @Override
    public void onDisable() {
        if (zoneManager != null) zoneManager.stopParticleTask();
        if (jumpPadManager != null) jumpPadManager.saveAll();
        getServer().getMessenger().unregisterOutgoingPluginChannel(this);
    }

    // -------------------------------------------------------------------------
    // World setup
    // -------------------------------------------------------------------------

    public void setupWorld(World world) {
        world.setGameRuleValue("doDaylightCycle", "false");
        world.setTime(6000);
        world.setStorm(false);
        world.setThundering(false);
    }

    // -------------------------------------------------------------------------
    // Player setup
    // -------------------------------------------------------------------------

    /** Full player initialisation: game mode, inventory, effects, teleport. */
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
        //speed und jump boost 2 entfernen?
       // player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 1, false, false));
       // player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP,  Integer.MAX_VALUE, 0, false, false));

        player.getInventory().clear();

        if (!isInBuildMode(player)) {
            setupInventory(player);
        }

        updatePlayerVisibility(player);
        updateScoreboard(player);
        updateTablist(player);

        Location spawn = getSpawnLocation();
        if (spawn != null) {
            player.teleport(spawn);
        }
    }

    /** Fills the lobby hotbar items for a non-build-mode player. */
    public void setupInventory(Player player) {
        // Slot 0: Server Selector
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

        // Slot 2: Coinshop
        ItemStack coinshop = new ItemStack(Material.GOLD_INGOT);
        ItemMeta coinshopMeta = coinshop.getItemMeta();
        coinshopMeta.setDisplayName("§cCoinshop");
        coinshop.setItemMeta(coinshopMeta);
        player.getInventory().setItem(2, coinshop);

        // Slot 4: Lobby Blocks — sandstone blocks placeable in the lobby
        ItemStack lobbyBlocks = new ItemStack(Material.SANDSTONE, 64);
        ItemMeta lobbyMeta = lobbyBlocks.getItemMeta();
        lobbyMeta.setDisplayName("§cLobby Blocks");
        lobbyBlocks.setItemMeta(lobbyMeta);
        player.getInventory().setItem(4, lobbyBlocks);

        // Slot 6: Settings
        ItemStack settings = new ItemStack(Material.REDSTONE_TORCH_ON);
        ItemMeta settingsMeta = settings.getItemMeta();
        settingsMeta.setDisplayName("§cSettings");
        settings.setItemMeta(settingsMeta);
        player.getInventory().setItem(6, settings);

        // Slot 7: Friends (1.8: SKULL_ITEM with damage 3 for player heads)
        ItemStack friends = new ItemStack(Material.SKULL_ITEM, 1, (short) 3);
        SkullMeta friendsMeta = (SkullMeta) friends.getItemMeta();
        friendsMeta.setDisplayName("§cFriends");
        friendsMeta.setOwner(player.getName());
        friends.setItemMeta(friendsMeta);
        player.getInventory().setItem(7, friends);

        // Slot 8: Visibility toggle
        updateVisibilityItem(player);

        // Slots 3, 5 remain empty
    }

    // -------------------------------------------------------------------------
    // Visibility
    // -------------------------------------------------------------------------

    public void updateVisibilityItem(Player player) {
        String visibility = getConfig().getString(
                "players." + player.getUniqueId() + ".visibility", "ALL");

        short  dyeDamage;
        String displayName;
        switch (visibility) {
            case "VIP":
                dyeDamage   = 5;
                displayName = "§5Only VIP players are visible";
                break;
            case "STAFF":
                dyeDamage   = 14;
                displayName = "§6Only Staff is visible";
                break;
            case "NONE":
                dyeDamage   = 1;
                displayName = "§cNo players are visible";
                break;
            default: // ALL
                dyeDamage   = 10;
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
        String vipPerm   = getConfig().getString("visibility.vip-permission",  "lobby.visibility.vip");
        String staffPerm = getConfig().getString("visibility.staff-permission", "lobby.visibility.staff");

        for (Player online : Bukkit.getOnlinePlayers()) {
            switch (visibility) {
                case "VIP":
                    if (online.hasPermission(vipPerm)) player.showPlayer(online);
                    else                                player.hidePlayer(online);
                    break;
                case "STAFF":
                    if (online.hasPermission(staffPerm)) player.showPlayer(online);
                    else                                  player.hidePlayer(online);
                    break;
                case "NONE":
                    player.hidePlayer(online);
                    break;
                default: // ALL
                    player.showPlayer(online);
                    break;
            }
        }

        Bukkit.getScheduler().runTask(this, () -> updateTablist(player));
    }

    public void cycleVisibilityMode(Player player) {
        String current = getConfig().getString(
                "players." + player.getUniqueId() + ".visibility", "ALL");
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

        Bukkit.getScheduler().runTask(this, () -> {
            for (Player online : Bukkit.getOnlinePlayers()) {
                updateTablist(online);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Scoreboard & tab list
    // -------------------------------------------------------------------------

    private void updateAllScoreboards() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateScoreboard(player);
        }
    }

    private void updateAllTablists() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            updateTablist(player);
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
        } catch (NumberFormatException ignored) { }

        int score = 15;
        objective.getScore("§7§m-------------------").setScore(score--);
        objective.getScore("§8» §cRank: §6"    + getPlaceholder(player, "%phoenix_player_real_rank%")).setScore(score--);
        objective.getScore("§8» §cPlayers: §6" + getPlaceholder(player, "%phoenix_server_global_online%")).setScore(score--);
        objective.getScore("§8» §cCoins: §6"   + getPlaceholder(player, "%pxcosmetics_player_coins%")).setScore(score--);
        objective.getScore("§8» §cLevel: §6"   + getPlaceholder(player, "%phoenix_player_level_displayname%")).setScore(score--);
        objective.getScore("§8» §cPlaytime: §6" + playtime + "h").setScore(score--);
        objective.getScore("§f ").setScore(score--);
        objective.getScore("§7§oexample.invalid").setScore(score--);
        objective.getScore("§7§o§m-------------------").setScore(score--);

        if (hasPhoenixAPI) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                applyRankNametag(board, online);
            }
        }

        player.setScoreboard(board);
    }

    private void applyRankNametag(Scoreboard board, Player target) {
        try {
            Object profileManager = getPhoenixProfileManager();
            if (profileManager == null) return;
            Object profile = getProfileByPlayer(profileManager, target);
            if (profile == null) return;
            Object rank = getRankFromProfile(profile);
            if (rank == null) return;
            String prefix = getRankPrefix(rank);
            if (prefix == null) return;

            Team team = board.getTeam(target.getName());
            if (team == null) team = board.registerNewTeam(target.getName());
            if (!team.hasEntry(target.getName())) team.addEntry(target.getName());

            String formatted = ChatColor.translateAlternateColorCodes('&', prefix);
            if (formatted.length() > 16) formatted = formatted.substring(0, 16);
            team.setPrefix(formatted);
        } catch (Exception ignored) { }
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
                String name = ChatColor.translateAlternateColorCodes('&', prefix) + " " + player.getName();
                player.setPlayerListName(name);
            }
        } catch (Exception e) {
            getLogger().warning("Error updating tab list for " + player.getName() + ": " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Build mode
    // -------------------------------------------------------------------------

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
        }
    }

    // -------------------------------------------------------------------------
    // Spawn location
    // -------------------------------------------------------------------------

    public Location getSpawnLocation() {
        if (!getConfig().contains("spawn.world")) return null;

        String worldName = getConfig().getString("spawn.world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;

        double x     = getConfig().getDouble("spawn.x");
        double y     = getConfig().getDouble("spawn.y");
        double z     = getConfig().getDouble("spawn.z");
        float  yaw   = (float) getConfig().getDouble("spawn.yaw");
        float  pitch = (float) getConfig().getDouble("spawn.pitch");
        return new Location(world, x, y, z, yaw, pitch);
    }

    public void setSpawnLocation(Location loc) {
        getConfig().set("spawn.world", loc.getWorld().getName());
        getConfig().set("spawn.x",     loc.getX());
        getConfig().set("spawn.y",     loc.getY());
        getConfig().set("spawn.z",     loc.getZ());
        getConfig().set("spawn.yaw",   loc.getYaw());
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

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public ServerSelectorManager getServerSelectorManager() { return serverSelectorManager; }
    public ZoneManager           getZoneManager()           { return zoneManager; }
    public JumpPadManager        getJumpPadManager()       { return jumpPadManager; }

    // -------------------------------------------------------------------------
    // Placeholder helpers
    // -------------------------------------------------------------------------

    private String getPlaceholder(Player player, String placeholder) {
        if (hasPlaceholderAPI) {
            return me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player, placeholder);
        }
        switch (placeholder) {
            case "%phoenix_player_real_rank%":         return "Player";
            case "%phoenix_server_global_online%":     return String.valueOf(Bukkit.getOnlinePlayers().size());
            case "%pxcosmetics_player_coins%":         return "0";
            case "%phoenix_player_level_displayname%": return "1";
            case "%phoenix_player_playtime_seconds%":  return "0";
            default:                                   return placeholder;
        }
    }

    // PhoenixAPI reflection helpers (avoids a hard compile-time dependency)

    private Object getPhoenixProfileManager() {
        try {
            Class<?> c = Class.forName("xyz.refinedev.phoenix.Phoenix");
            Object inst = c.getMethod("getInstance").invoke(null);
            return c.getMethod("getProfileManager").invoke(inst);
        } catch (Exception e) { return null; }
    }

    private Object getProfileByPlayer(Object profileManager, Player player) {
        try {
            return profileManager.getClass()
                    .getMethod("getByPlayer", Player.class)
                    .invoke(profileManager, player);
        } catch (Exception e) { return null; }
    }

    private Object getRankFromProfile(Object profile) {
        try {
            return profile.getClass().getMethod("getRank").invoke(profile);
        } catch (Exception e) { return null; }
    }

    private String getRankPrefix(Object rank) {
        try {
            return (String) rank.getClass().getMethod("getPrefix").invoke(rank);
        } catch (Exception e) { return null; }
    }
}

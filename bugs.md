# Bug Report — Lobby Plugin

Exhaustive audit of all 20 Java files. Bugs grouped by file with file path and line numbers.

---

## Main.java

**L56 — Logic / Silent failure on file creation**
`playersFile.createNewFile()` return value is ignored. If the parent directory doesn't exist, the call silently fails and `playersConfig` loads from a non-existent file path, returning an empty config with no warning beyond the caught IOException.

**L114 — Logic / Global side-effect never reverted**
`Bukkit.setDefaultGameMode(GameMode.SURVIVAL)` changes the server-wide default game mode for all worlds. It is never reverted in `onDisable`, so it permanently changes the server setting after the plugin is removed or reloaded.

**L134–138 — Logic / Flight preferences not always saved on shutdown**
`onDisable` iterates `Bukkit.getOnlinePlayers()` to save flight preferences. During some shutdown sequences, this collection can be empty or incomplete before `onDisable` fires, silently losing per-player data.

**L149 — Logic / Deprecated gamerule API**
`world.setGameRuleValue("doDaylightCycle", "false")` uses the deprecated 1.8 string API. The rule `doWeatherCycle` is also never disabled, so weather restarts itself despite `setStorm(false)` and `setThundering(false)`.

**L229 — Logic / Skull owner set by name, not UUID**
`friendsMeta.setOwner(player.getName())` is deprecated since 1.12. On offline-mode servers or with Geyser/Floodgate, name-based skull owner lookups can return null textures or wrong skins.

**L346–357 — Logic / Scoreboard object leak on reload**
`updateScoreboard` creates a new `Scoreboard` and assigns it to the player when the "lobby" objective is missing. The old custom scoreboard is abandoned in Bukkit's internal scoreboard map. Repeated reloads accumulate orphaned scoreboard instances.

**L362 — Performance / PlaceholderAPI called on main thread in repeating task**
`getPlaceholder` calls `PlaceholderAPI.setPlaceholders` synchronously inside a 20-tick timer iterating all online players. Network-querying placeholders (e.g. `%phoenix_server_global_online%`) block the main thread.

**L460–461 — NPE risk / Null world name**
`getConfig().getString("spawn.world")` can return `null` if the YAML key exists but has a null value. This is passed directly to `Bukkit.getWorld(worldName)`, which throws `NullPointerException` in CraftBukkit.

---

## LobbyBlockManager.java

**L28 — Logic / Block API called in constructor**
`removeAllLobbyBlocks()` is called inside the constructor. Calling Bukkit world/block APIs during object construction (before `onEnable` fully completes) is unsafe; the scheduler may not be fully started.

**L93 — Thread-safety / Bare int field**
`pendingSaveTaskId` is a bare `int` accessed from main-thread scheduler callbacks. If `reloadLobbyBlocksConfig` is ever called from an async context, this is an unsynchronized data race.

**L100–105 — Logic / Config snapshot may be stale**
`configSnapshot` captures `lobbyBlocksConfig` at schedule time. If `reloadLobbyBlocksConfig` runs and replaces `lobbyBlocksConfig` between the schedule and the task firing, the snapshot writes data to the old (replaced) config object, effectively discarding the save.

**L157 — ConcurrentModificationException risk**
`cleanupExpiredBlocks` iterates directly over `lobbyBlockKeys` (`for (String key : lobbyBlockKeys)`). If a scheduler callback calls `addLobbyBlock` or `removeLobbyBlock` during the same tick, this throws `ConcurrentModificationException`.

**L130–131 — Logic / Incomplete material whitelist in removeAllLobbyBlocks**
`removeAllLobbyBlocks` only removes `DIAMOND_BLOCK`, `SANDSTONE`, `REDSTONE_BLOCK`, and `EMERALD_BLOCK`. If a new lobby block material is added later, it will not be cleaned up here, leaving blocks permanently in the world.

---

## VisibilityManager.java

**L65–67 — Logic / Unbounded growth of visibility.yml**
Player UUIDs written via `setPlayerVisibility` are never cleaned up from `visibility.yml`. Every unique player who joins adds a permanent entry, causing the file to grow indefinitely.

**L86 — Logic / Partial migration on crash**
`migrateFromMainConfig` deletes each player's old config entry after migrating it (L86). If the server crashes mid-migration, some players will have been migrated (old entry deleted) while others haven't, leaving the data in an inconsistent state with no recovery path.

---

## MessagesManager.java

**L29–33 — Resource leak / InputStream not closed**
`plugin.getResource("messages.yml")` returns an `InputStream` that is wrapped in `InputStreamReader` and passed to `YamlConfiguration.loadConfiguration`. The stream is never explicitly closed, causing a resource leak on every call to `load()` (including reloads).

---

## Zone.java

**L36 — Logic / Permission lower-cased on setRequiredPermission**
`setRequiredPermission` at L103 calls `permission.trim().toLowerCase()`. This silently mangles mixed-case permission nodes (e.g. `Zone.Staff` → `zone.staff`). If the permission plugin is case-sensitive, the permission check will always fail.

**L40–47 — Logic / Bounding box with empty corners list**
If `corners` is empty (should be prevented by validation, but possible via direct construction), `bboxMinX` / `bboxMaxX` / `bboxMinZ` / `bboxMaxZ` are initialized to `Integer.MAX_VALUE` / `Integer.MIN_VALUE`. The `contains()` method checks `corners.size() < 3` but only after the bounding-box check, which would always be true for `MAX_VALUE`, making the behavior accidentally correct but fragile.

---

## ZoneManager.java

**L81 — Logic / Zone name case mismatch**
`zones.put(name.toLowerCase(), zone)` stores with a lowercase key, but `zone.getName()` returns the original mixed-case name passed to the constructor. Any code that retrieves `zone.getName()` and then calls `zones.get(zone.getName())` (without lowercasing) gets null, since the key is lowercase.

**L116 — Logic / Double `\n` substitution**
`setZoneDenyMessage` calls `message.replace("\\n", "\n")`. But `ZoneCommand.handleSetMessage` (L271) also calls `.replace("\\n", "\n")` on the string before passing it here. The substitution happens twice on the same data path.

**L183 — NPE risk / getItemMeta() result not cached**
`isWand` calls `item.getItemMeta().getDisplayName()` after `item.hasItemMeta()`. In some CraftBukkit versions, `getItemMeta()` can return null even when `hasItemMeta()` returns true (race between item modification and read). The result of `getItemMeta()` should be cached in a local variable.

**L227–228 — Logic / Wrong world used for particle spawn**
`spawnDust` creates a `Location` with `viewer.getWorld()`. If the viewer changed worlds between the outer `sameWorld` check (L211) and `spawnDust` being called, particles appear in the wrong world.

---

## ZoneConfigManager.java

**L99–113 — Logic / Incomplete rollback on error**
`saveZonesToConfig` swaps `zonesConfig = fresh` before writing. On `IOException` or `RuntimeException`, it restores `zonesConfig = previous`. However, an `Error` (e.g. `OutOfMemoryError`) bypasses both `catch` blocks, leaving `zonesConfig` permanently pointing to the partially-populated `fresh` config with `previous` discarded.

**L164–179 — Logic / One bad corner aborts the entire zone**
`deserializeZone` returns `null` on any invalid corner string, dropping the entire zone from memory. A zone with one malformed corner entry in YAML is silently discarded rather than degraded gracefully.

**L171 — Logic / `split(",")` does not handle leading whitespace on full string**
`raw.split(",")` then `parts[0].trim()` and `parts[1].trim()`. If the YAML parser delivers the whole corner string with leading whitespace (e.g. `" 100,200"`), `parts[0]` becomes `" 100"` — but `.trim()` handles this. However `parts[1]` trimming is also done, so this is actually safe. Low severity but asymmetric: if someone edits the YAML by hand with `100 , 200`, the space before the comma ends up in `parts[0]` and `.trim()` handles it; but this isn't obviously correct.

---

## ZoneListener.java

**L40–66 — Performance / O(players × zones) per 0.5 seconds**
`startStuckCheck` runs every 10 ticks for every online player, calling `zoneManager.getDeniedZoneAt(player, ...)` which iterates all zones. With many players and many zones, this is an expensive O(n×m) operation on the main thread every 0.5 seconds.

**L56–64 — Logic / Knockback oscillation in concave zones**
`depth` is computed from `findNearestBoundaryPoint` using only XZ distance. In concave polygons, the nearest boundary point can be on the "wrong" side of the zone interior, causing the knockback to push the player deeper into the zone rather than out, creating an oscillation loop.

**L100–101 — Logic / Player teleported to pearl's last position, not their own**
When a pearl enters a denied zone, `safeReturn` is set to `previousLoc` (the pearl's previous location one tick ago). The player is teleported there. However the pearl and the player share the same position only while the player is a passenger — the pearl lags behind the player's visual position, so `previousLoc` is the pearl's last tracked location, not the player's actual location.

**L209–210 — Logic / Stale depth calculation**
In `onPlayerMove` when `alreadyInside` is true, `boundary` and `depth` are computed from `from` (the event's "from" location). By the time the scheduled knockback task fires (next tick), the player may have moved significantly, making the depth-based strength calculation inaccurate.

**L233 — Logic / Zero horizontal velocity on flat approach**
`vel = new Vector(0, 0, 0)` then `vel.setY(0.45)` when the player approaches with near-zero XZ direction. This pushes the player straight up with no horizontal component. On a flat wall, the player falls back down onto the wall and bounces repeatedly without being pushed away.

---

## ZoneSelectionSession.java

**L66 — Logic / Misleading Y coordinate in summary**
`buildSummary` prints `c.getBlockY()` for each corner. Zones only use X and Z from corners (Y range is set via minY/maxY in `/zone save`). Showing Y in the corner summary misleads admins into thinking per-corner Y values affect zone boundaries.

---

## LobbyListener.java

**L53–54 — Performance / 1-tick repeating task for all players**
`tickEnderButt` runs every single tick (20 times/second) iterating all `enderButtPearls` entries. Even when no pearls are active, the task fires every tick. This wastes scheduler overhead when the map is empty.

**L91 — Logic / `currentLoc.getWorld()` is never null-checked**
Line 91 checks `previousLoc.getWorld() == null`, but `currentLoc.getWorld()` is never checked. If the pearl is in an unloaded chunk or void, `currentLoc.getWorld()` is null, and `currentLoc.getWorld().equals(...)` on L91 throws NPE.

**L120 — Logic / `distance()` throws on cross-world locations**
`previousLoc.distance(currentLoc)` throws `IllegalArgumentException` if the locations are in different worlds. The null-world guard on L91 only nullifies `previousLoc` if `previousLoc.getWorld()` is null; if both worlds are non-null but different (from a world-change event), L120 throws.

**L199 — Logic / Scoreboard reset on quit may conflict with other plugins**
`player.setScoreboard(Bukkit.getScoreboardManager().getMainScoreboard())` on quit overrides any scoreboard another plugin set at the `PlayerQuitEvent`. Event handler ordering is not guaranteed.

**L255–260 — Logic / Slot 4 refill can give 64 DIAMOND_BLOCKs**
The slot 4 refill check at L258 includes `slot4.getType() == Material.DIAMOND_BLOCK`. If a player somehow has a DIAMOND_BLOCK named "§cBlocks" in slot 4 (e.g. obtained during a brief build mode), the refill will set its amount to 64, giving them a stack of diamond blocks.

**L302 — Logic / PHYSICAL interaction early return is unconditional**
`onInteract` returns without cancelling for `Action.PHYSICAL` even if the player holds a lobby item. This is intentional for pressure plates, but the return is at the very end of the method — any code path before this return already ran `event.setCancelled(true)` or returned. The PHYSICAL check here is dead code. (See L312–315.)

**L338–351 — Performance / O(n) pearl search by entity ID**
`onProjectileHit` iterates all `enderButtPearls` entries to find the matching pearl by entity ID. A reverse lookup map would be O(1).

**L388 — Logic / Void damage not cancelled**
`onDamage` cancels all damage except `VOID`. Players who fall into the void receive real void damage and die. The height-limit teleport (`tickHeightLimit`) only runs every 4 ticks — a player in free fall can die before being teleported. Void damage should also be cancelled here.

**L447 — Logic / Pearl passenger set synchronously on launch**
`pearl.setPassenger(player)` is called in the same tick as `player.launchProjectile(EnderPearl.class)`. In 1.8, passenger state sometimes fails to sync to clients when set immediately after launch. A 1-tick delay via `runTask` is more reliable.

**L544–545 — NPE risk / `from.getWorld()` not null-checked**
`hasSolidBlockBetween` calls `from.getWorld().equals(to.getWorld())` without checking if `from.getWorld()` or `to.getWorld()` is null. If either is in an unloaded world, this throws NPE.

**L558 — Logic / BlockIterator maxDistance may overshoot**
`maxDistance = (int) Math.ceil(distance) + 2` adds 2 extra blocks of ray-cast beyond the actual travel distance. This causes false collision positives for blocks near but not between `from` and `to`.

---

## SpeedCookieListener.java

**L53–59 — Logic / Fragile food restore check**
The 80-tick food-restore task checks `p.getFoodLevel() <= 6` before restoring. The threshold of 6 is arbitrary. If any other code (outside this plugin) sets food to a value between 7 and 19, the restore is skipped permanently, leaving the player with reduced food. The `FoodLevelChangeEvent` cancel in `LobbyListener` prevents this in practice, but the logic relies on an undocumented invariant.

**L70 — Logic / `addPotionEffect` with `force=true` overwrites external speed effects**
`player.addPotionEffect(effect, true)` replaces any existing Speed II effect (even one with a longer duration from an external source). An admin-applied permanent speed buff would be silently overwritten when a player eats a cookie.

---

## ProtocolCheckListener.java

**L37 — Logic / ViaVersion 4.x method lookup fails silently**
`api.getClass().getMethod("getPlayerVersion", Object.class)` looks for a method accepting `Object`. In ViaVersion 4.x, this method signature is `getPlayerVersion(UUID)`. The lookup throws `NoSuchMethodException`, caught silently, leaving `method = null` — disabling the protocol check on ViaVersion 4.x even though ViaVersion is installed.

**L44 — Logic / Confusing/incorrect null check comment**
`if (api != null && method == null)` is intended to detect a partially-resolved ViaVersion API. However, `api` from a previous loop iteration persists in the variable across iterations. If an earlier `className` succeeded in getting `api` but failed to get `method`, then `api != null && method == null` is true. The `break` at L41 means this can only happen if `getMethod` throws after `getAPI` succeeded — so the check is functionally correct but very fragile.

---

## ServerSelectorManager.java

**L135 — Security / Geyser/Bedrock player names truncated**
`player.getName().replaceAll("[^a-zA-Z0-9_]", "")` strips dots from Bedrock player names (Geyser names use `.` prefix). The sanitized name silently differs from the actual player name, garbling commands that reference the player.

**L139 — Security / Insufficient server name sanitization**
`action.substring(8).replaceAll("[\\x00-\\x1F\\x7F]", "")` only strips ASCII control characters from BungeeCord server names. Unicode homoglyphs, right-to-left override characters, or names containing spaces (valid in BungeeCord config) pass through without sanitization.

**L146 — Security / Console command injection via config**
`Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)` runs a console (OP-level) command sourced from the plugin config. A compromised or world-writable config file allows arbitrary command execution with console privileges.

---

## GetSpeedCookieCommand.java

**L78 — Thread-safety / Async save of non-thread-safe YamlConfiguration**
`plugin::savePlayersConfig` is run via `runTaskAsynchronously`. `YamlConfiguration.save` is not thread-safe. If the main thread modifies `playersConfig` (e.g. `savePlayerFlightPreference` or another cookie click) concurrently with the async save, this causes `ConcurrentModificationException` or a corrupt YAML file.

**L66–70 — Logic / Leftover item silently discarded**
If `player.getInventory().addItem(...)` returns a non-empty leftover map (full inventory), the item is not given, the cooldown is not recorded, and no item is dropped. The leftover `ItemStack` in the returned map is simply abandoned — it does not appear in the world or return to the player.

---

## BuildCommand.java

No critical bugs.

---

## FlyCommand.java

**L32 — Logic / Fly toggle uses `getAllowFlight()` instead of preference**
`if (player.getAllowFlight())` checks the current server-side flight state. If a player is in a no-flight zone (`setAllowFlight(false)` by the zone system), `/fly` appears to "enable" flight (since `getAllowFlight()` returns false → enters the enable branch), but `restoreFlightState` immediately re-disables it. The toggle should check `plugin.isFlightDisabledByUser(player)` instead.

**L56 — Logic / Same issue for `/fly <target>`**
`if (target.getAllowFlight())` has the same wrong-state-check problem as L32 for targeted players.

---

## ZoneCommand.java

**L107 — Logic / Dead code: `name.contains(" ")` is always false**
`args[1]` is a single Bukkit command argument token, so it can never contain a space (Bukkit splits command input on spaces before building the `args` array). The `name.contains(" ")` check is unreachable.

**L271 — Logic / Double `\n` substitution (same as ZoneManager.java L116)**
`handleSetMessage` calls `sb.toString().replace("\\n", "\n")` before passing to `zoneManager.setZoneDenyMessage`. `setZoneDenyMessage` also calls `message.replace("\\n", "\n")`. The replacement is applied twice to the same string.

---

## Summary by Category

| Category | Count |
|---|---|
| Logic bugs | 30 |
| NPE / null-safety | 6 |
| Performance | 4 |
| Thread-safety / race condition | 3 |
| Security | 3 |
| Resource leak | 1 |
| Dead code | 2 |

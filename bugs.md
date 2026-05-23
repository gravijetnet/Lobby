# Bug Report — Lobby Plugin

All known bugs have been fixed. This file documents the full audit history.

---

## Fixed (latest pass — 2026-05-23)

| # | File | Line(s) | Bug | Fix |
|---|------|---------|-----|-----|
| I | Main.java | 417–419 | Crash: `board.getEntries()` iterated while `board.resetScores()` mutates the backing set → `ConcurrentModificationException` | Copy the set into a new `ArrayList` before iterating |
| II | LobbyListener.java | 180–193 | Logic/NaN: Ender-butt steering called `current.length()` as the divisor without a zero-guard; when raw velocity was near-zero, dividing produced NaN velocity components | Captured `rawSpeed = current.length()` separately; the near-zero guard now uses `rawSpeed` (not the bumped `speed`) |
| III | LobbyListener.java | 126 | Dead code / wrong API: `riderCheck.eject()` ejects the *player's* passengers (none), not the player from the pearl — was a no-op before the correct `pearl.eject()` on the next line | Removed the dead `riderCheck.eject()` call |
| IV | LobbyListener.java | 171 | Same as III: `player.eject()` in collision branch ejects the player's passengers (none) instead of ejecting the player from the pearl | Removed the dead `player.eject()` call; `pearl.eject()` already does the correct thing |
| V | LobbyListener.java | 278–300 | Missing online check: deferred inventory-refill task captured `player` directly; if the player quit within the 1-tick delay, `updateInventory()` would run on an offline player | Captured UUID, re-fetched player inside the task with an online check |
| VI | LobbyListener.java | 488–491 | Missing online check: deferred `pearl.setPassenger(player)` task captured `player` directly; if the player quit within the 1-tick delay, a passenger would be set on a now-orphaned pearl | Captured UUID, re-fetched player inside the task; if offline, removes the orphaned pearl |

---

## Fixed (previous pass — A/B/C/D/E)

| # | File | Line(s) | Bug | Fix |
|---|------|---------|-----|-----|
| A | LobbyListener.java | 191–195 | Crash: `velocity.normalize()` called on near-zero velocity (unridden pearl at rest) | Added `speed > 1e-6` guard before `normalize()` |
| B | LobbyListener.java | 552–558 | Logic: `ejectAndCancelPearl` called `vehicle.eject()` (ejects passengers from vehicle) instead of `player.leaveVehicle()` on an untracked pearl | Changed to `player.leaveVehicle()` + `vehicle.remove()` |
| C | GetSpeedCookieCommand.java | 76 | NPE: `plugin.getPlayersConfig().set(...)` without null check (if players.yml creation failed) | Added null check around `pc.set()` and `savePlayersConfig()` |
| D | ZoneListener.java | 119 | Missing null guard: `findGroundAt` accepted a World parameter that could be null | Added early `if (world == null) return null` |
| E | LobbyListener.java | 61–68 | Resource leak: lobby block animation tasks not cancelled in `stopTasks()` on plugin disable | Added loop to cancel all pending `lobbyBlockTasks` in `stopTasks()` |

---

## Fixed (first pass)

| # | File | Line(s) | Bug | Fix |
|---|------|---------|-----|-----|
| 1 | SpeedCookieListener.java | 77 | NPE: `getItemMeta()` chained without null check | Extract meta into variable with null guard |
| 2 | LobbyListener.java | 207 | NPE: `Bukkit.getScoreboardManager()` can return null | Guard result before calling `getMainScoreboard()` |
| 3 | ServerSelectorManager.java | 99 | NPE: `PlaceholderAPI.setPlaceholders()` result used without null check | Null-check result before assignment |
| 5 | Main.java | 73, 137 | Logic: redundant `X ? true : Y` ternary | Simplified to `X \|\| Y` |
| 7 | LobbyListener.java | 255–274 | Logic: slot 4 not refilled when it is null/empty | Always execute the refill branch when `isNamedLobbyBlock` is false |
| 8 | LobbyListener.java | 522–533 | Logic: `ejectAndCancelPearl` ejects any vehicle, not just tracked pearls | Only eject from tracked pearl; non-pearl vehicles are not ejected |
| 9 | LobbyListener.java | 106–118 | Logic: `safeReturn` teleport position not validated for solid ground | Added `isSafeGround()` check before teleporting |
| 10 | ZoneListener.java | 108–125 | Logic: `findSafeOutsideLocation` used player's current Y without scanning for ground | Added `findGroundAt()` that scans downward up to 8 blocks |
| 11 | ZoneListener.java | 325–339 | Logic: `calcSafePosition` returned candidates with no ground check | Added `hasSolidGround()` check on each candidate |
| 12 | LobbyListener.java | 426–436 | Logic: build-mode players had flight stripped by zone transitions | Wrapped flight-zone logic in `!isInBuildMode(player)` guard |
| 17 | Main.java | 284–305 | Performance: two separate O(n) visibility loops on player join | Merged into a single-pass loop in `refreshVisibilityForJoin` |
| 19 | ServerSelectorManager.java | 150–155 | Security: arbitrary console commands with weak sanitization | Removed `.` from safe chars, stripped full control-char range + shell operators; wrapped in try-catch |
| 20 | ServerSelectorManager.java | 158–161 | Security: raw `player.getName()` in `playercommand` (offline-mode risk) | Now uses `safeName` (sanitized) instead of raw player name |
| 21 | SpeedCookieListener.java | 26, 50–61 | Race: `consuming` set not cleared on player quit | Added `onQuit` handler to remove UUID from `consuming` |
| 23 | LobbyListener.java | 214–217 | Missing: scoreboard cache not invalidated on world change | Added `clearScoreboardCache()` call in `onWorldChange` |
| 24 | GetSpeedCookieCommand.java | 125 | Missing null check: `getPlayersConfig()` used without null guard | Added null check before calling `.getLong()` |
| 26 | ServerSelectorManager.java | 155 | Missing error handling: `dispatchCommand` exceptions swallowed silently | Wrapped in try-catch with logger warning |
| 29 | LobbyBlockManager.java | 28 | Startup ordering: `removeAllLobbyBlocks()` called from constructor before worlds load | Moved call to `Main.onEnable` after world setup loop |
| 31 | VisibilityManager.java | 76–84 | Performance: unnecessary cancel+reschedule of save task on every `setPlayerVisibility` call | Changed to skip scheduling if a task is already pending |
| 32 | LobbyListener.java | 410 | Logic: `onPlayerMove` processed moves already cancelled by `ZoneListener` | Added `ignoreCancelled = true` |

---

## Not Fixed / Notes

| # | File | Reason |
|---|------|--------|
| 4 | Main.java:152 | `setGameRuleValue` is the only API available on Spigot 1.8.8 — cannot use the modern typed GameRule API |
| 13 | LobbyListener.java:343 | Structurally safe: loop exits via `break` before the outer `remove()`. No change needed |
| 14 | LobbyListener.java:575 | `BlockIterator` allocation in hot loop — acceptable for this plugin's scale; premature optimization |
| 15 | LobbyListener.java:57 | Height-limit tick every 4 ticks — lightweight enough at this player count |
| 16 | Main.java:402 | Re-examined: `setDisplayName` is already inside the cache-miss branch (after the early `return`), so it is not called every tick. False positive in original audit |
| 18 | MessagesManager.java:31 | `InputStreamReader` closing delegates to the wrapped `InputStream` in Java — no actual leak |
| 22 | LobbyListener.java:45 | `blockDenyCooldowns` is removed on quit (line 209). Entries are bounded by online players — not a leak |
| 25 | ZoneConfigManager.java:94 | Null check already present on line 95 |
| 27 | ZoneManager.java:194 | `sameWorld()` guard already prevents NPE on null world locations |
| 28 | ZoneManager.java:244 | `< 2` threshold vs `>= 3` save requirement: in-memory state can transiently have 2 corners (wand session), never a saved zone. No crash possible |
| 30 | LobbyBlockManager.java:116 | Bukkit scheduler is single-threaded (main thread). Cancel+save cannot race — false positive |
| 33 | LobbyListener.java:224 | Intentional: lobby blocks must override anti-grief plugin cancellations in this plugin's design |
| 34 | Main.java:243 | `setOwningPlayer` does not exist in Spigot 1.8.8 API — `setOwner(String)` is the only option; reverted |
| 35 | ZoneCommand.java:266 | Functional limitation, not a crash. Admins can use `\n` literal in commands |
| 36 | Zone.java:125 | Bounding box is slightly over-inclusive at max edges — harmless extra polygon check, no correctness issue |

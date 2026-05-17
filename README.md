# Lobby Plugin

A Spigot 1.8.8 lobby plugin featuring zones, flight control, visibility settings, temporary blocks, and more.

---

## Table of Contents

- [Commands](#commands)
- [Permissions](#permissions)
- [Features](#features)

---

## Commands

### `/spawn`
Teleports you to the lobby spawn point.

| Usage | Permission |
|-------|------------|
| `/spawn` | `lobby.spawn` |
| `/spawn <player>` | `lobby.spawn.other` |

---

### `/setspawn`
Sets the lobby spawn point to your current location.

| Usage | Permission |
|-------|------------|
| `/setspawn` | `lobby.setspawn` |

---

### `/fly`
Toggles flight for yourself or another player.

| Usage | Permission |
|-------|------------|
| `/fly` | `lobby.fly` |
| `/fly <player>` | `lobby.fly.other` |

---

### `/build`
Toggles build mode. When active, switches you to Creative and lets you freely place and break blocks.

| Usage | Permission |
|-------|------------|
| `/build` | `lobby.build` |
| `/build <player>` | `lobby.build.other` |

---

### `/zone`
Manages restricted areas (zones) in the lobby world.

| Subcommand | Description |
|-----------|-------------|
| `/zone wand` | Gives you the zone selection wand. Right-click to add corners, left-click to remove the last corner. |
| `/zone save <name> [minY maxY]` | Saves your current corner selection as a zone with an optional Y-range. |
| `/zone delete <name>` | Deletes a zone. |
| `/zone list` | Lists all zones with their corner count, world, Y-range, and restriction status. |
| `/zone clear` | Clears your current selection. |
| `/zone info <name>` | Shows details about a zone (world, Y-range, corners, permission, message, settings). |
| `/zone setperm <name> <permission>` | Sets the permission required to enter the zone. |
| `/zone clearperm <name>` | Removes the entry permission restriction from a zone. |
| `/zone setmessage <name> <message>` | Sets the message shown when a player is denied entry. Supports `&` color codes and `{permission}`. |
| `/zone setblockplace <name> <true\|false>` | Allows or denies block placement inside the zone. |
| `/zone setflight <name> <true\|false>` | Allows or disables flight inside the zone. |

**Permission:** `lobby.zone`

---

### `/lobbyreload`
Reloads all plugin configuration files (config, zones, messages, visibility, lobby blocks, server selector).

| Usage | Permission |
|-------|------------|
| `/lobbyreload` | `lobby.reload` |

---

### `/getspeedcookie`
Opens a menu where you can claim a Speed Cookie. The cookie grants Speed II for 10 minutes when eaten. Has a 1-hour cooldown per player.

| Usage | Permission |
|-------|------------|
| `/getspeedcookie` | *(none — available to all)* |

---

## Permissions

| Permission | Description | Default |
|-----------|-------------|---------|
| `lobby.spawn` | Teleport to spawn | op |
| `lobby.spawn.other` | Teleport other players to spawn | op |
| `lobby.setspawn` | Set the spawn location | op |
| `lobby.fly` | Toggle flight | op |
| `lobby.fly.other` | Toggle flight for other players | op |
| `lobby.build` | Toggle build mode | op |
| `lobby.build.other` | Toggle build mode for other players | op |
| `lobby.zone` | Manage zones | op |
| `lobby.reload` | Reload plugin configuration | op |
| `lobby.visibility.vip` | Show up in VIP visibility filter | false |
| `lobby.visibility.staff` | Show up in staff visibility filter | op |
| `zone.entry.<name>` | Enter a restricted zone (auto-created per zone) | op |

---

## Features

### Spawn System
Players are teleported to the spawn point when they join, respawn, fall below y=-50 (void protection), or are above y=200. The spawn location is set with `/setspawn` and stored in `config.yml`.

---

### Zones
Polygonal areas defined in the XZ plane with an optional Y-range. Zones can:
- **Restrict entry** — players without the required permission are knocked back and shown a deny message. If a player gets stuck at a boundary, they are ejected to spawn after 1.5 seconds.
- **Control block placement** — allow or deny placing lobby blocks inside the zone.
- **Control flight** — automatically enable or disable flight as players enter and exit.

Zones are created by selecting corners with the zone wand and running `/zone save`.

---

### Flight
Players with `lobby.fly` can toggle flight with `/fly`. Flight preference is saved per player and restored on rejoin. Zones can override this — flight is automatically disabled when entering a no-flight zone and re-enabled when leaving (unless the player had manually disabled it).

---

### Build Mode
Players with `lobby.build` can toggle Creative mode. While active, they can freely place and break any block. Leaving build mode restores Survival and the standard lobby inventory.

---

### Lobby Blocks (Temporary Blocks)
Players can place temporary blocks that disappear automatically:
- **Sandstone blocks** from the lobby inventory item (slot 4, auto-restocked to 64).
- **Diamond blocks** (always allowed).

After placement, blocks change color and then disappear. Default timing: 5 seconds to change, 7 seconds to vanish. Block placement can be denied per zone.

---

### Ender Butt
An ender pearl item in slot 1. Right-clicking launches the player as a rideable projectile. The pearl follows your look direction, is capped at speed 3.0 and height y=200, and is removed if you enter a zone you don't have access to. Throwing a new pearl removes any active one.

---

### Visibility
Cycle through visibility modes using the dye item in slot 8:

| Mode | Icon | Who you see |
|------|------|-------------|
| **ALL** | Green dye | All players |
| **VIP** | Magenta dye | Players with `lobby.visibility.vip` |
| **STAFF** | Orange dye | Players with `lobby.visibility.staff` |
| **NONE** | Red dye | Nobody |

Your preference is saved and restored on rejoin.

---

### Server Selector
A compass in slot 0 opens a configurable GUI menu to connect to other servers via BungeeCord. Items, slots, names, lore, and target servers are configured in `config.yml`. Supports PlaceholderAPI in lore lines.

---

### Scoreboard
A live sidebar scoreboard displays rank, global player count, coins, level, and playtime. Updates every second and only refreshes when values change.

---

### Inventory
The standard lobby hotbar:

| Slot | Item | Action |
|------|------|--------|
| 0 | Server Selector (compass) | Opens server selector GUI |
| 1 | Ender Butt (ender pearl) | Launches rideable pearl |
| 2 | Coinshop (gold ingot) | Runs `/coinshop` |
| 4 | Blocks (sandstone ×64) | Place temporary blocks |
| 6 | Settings (redstone torch) | Runs `/settings` |
| 7 | Friends (skull) | Runs `/friends menu` |
| 8 | Visibility (dye) | Cycle visibility mode |

Players cannot drop items, pick up items, or rearrange their hotbar outside of build mode.

---

### World Settings
- Time is locked at noon (no day/night cycle).
- Weather is always clear.
- Players are invulnerable to all damage except the void.
- Food and health are always full.

---

## Configuration Files

| File | Purpose |
|------|---------|
| `config.yml` | Spawn location, lobby block timings, visibility permissions, server selector layout |
| `zones.yml` | Zone definitions (auto-managed by `/zone` commands) |
| `messages.yml` | All plugin messages |
| `players.yml` | Per-player preferences (flight) |
| `visibility.yml` | Per-player visibility mode |
| `lobbyblocks.yml` | Tracked temporary block locations (cleared on startup) |

---

## Dependencies

| Dependency | Required |
|-----------|---------|
| PlaceholderAPI | Required |
| PhoenixAPI | Optional (for custom placeholders) |
| ViaVersion | Optional (for client version detection) |

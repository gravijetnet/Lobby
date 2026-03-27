package net.gravijet.lobby.zone;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Mutable, per-player zone-selection session.
 * Holds the ordered list of 3-D corner Locations being built up by a player
 * using the Zone Wand.
 *
 * Kept separate from {@link Zone} so that the immutable Zone never holds
 * a live Bukkit World reference or mutable state.
 */
public final class ZoneSelectionSession {

    private final UUID           ownerUUID;
    /** 3-D locations in selection order. The Y is preserved for particle pillar display. */
    private final List<Location> corners = new ArrayList<>();

    public ZoneSelectionSession(UUID ownerUUID) {
        this.ownerUUID = ownerUUID;
    }

    // -------------------------------------------------------------------------
    // Mutation
    // -------------------------------------------------------------------------

    /**
     * Appends a corner and returns its 1-based index.
     * The Location is cloned so external mutations don't affect this session.
     */
    public int addCorner(Location loc) {
        corners.add(loc.clone());
        return corners.size();
    }

    /**
     * Removes the last corner. No-op if the list is already empty.
     *
     * @return true if a corner was removed, false if the list was empty
     */
    public boolean removeLastCorner() {
        if (corners.isEmpty()) return false;
        corners.remove(corners.size() - 1);
        return true;
    }

    /** Empties the corner list. */
    public void clear() {
        corners.clear();
    }

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    public UUID getOwnerUUID() { return ownerUUID; }

    /** Unmodifiable view of current corners. */
    public List<Location> getCorners() { return Collections.unmodifiableList(corners); }

    public int     size()    { return corners.size(); }
    public boolean isEmpty() { return corners.isEmpty(); }

    /** True when at least 3 corners have been selected (minimum for a polygon). */
    public boolean isComplete() { return corners.size() >= 3; }

    /**
     * World name of the first corner, or {@code null} if no corners have been
     * added yet. All corners are expected to share the same world; mixed-world
     * selections are rejected in {@link ZoneManager#saveZone}.
     */
    public String getWorldName() {
        if (corners.isEmpty()) return null;
        Location first = corners.get(0);
        return first.getWorld() != null ? first.getWorld().getName() : null;
    }

    // -------------------------------------------------------------------------
    // Summary text (shown on Shift+right-click)
    // -------------------------------------------------------------------------

    /**
     * Returns a human-readable summary of the current selection for display
     * in the player's chat.
     */
    public List<String> buildSummary() {
        List<String> lines = new ArrayList<>();
        lines.add("§6§l--- Zone Selection ---");
        lines.add("§7Corners: §f" + corners.size());

        for (int i = 0; i < corners.size(); i++) {
            Location c = corners.get(i);
            lines.add(String.format("§7  #%d §f(%d, %d, %d)",
                    i + 1, c.getBlockX(), c.getBlockY(), c.getBlockZ()));
        }

        if (isComplete()) {
            lines.add("§aValid polygon — use §e/zone save <name> §ato save.");
        } else {
            lines.add("§cNeed at least §f3 §ccorners (currently " + corners.size() + ").");
        }
        return lines;
    }
}

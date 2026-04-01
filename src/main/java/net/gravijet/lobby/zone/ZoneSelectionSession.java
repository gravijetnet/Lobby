package net.gravijet.lobby.zone;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class ZoneSelectionSession {

    private final UUID ownerUUID;
    private final List<Location> corners = new ArrayList<>();

    public ZoneSelectionSession(UUID ownerUUID) {
        this.ownerUUID = ownerUUID;
    }

    public int addCorner(Location loc) {
        corners.add(loc.clone());
        return corners.size();
    }

    public boolean removeLastCorner() {
        if (corners.isEmpty()) return false;
        corners.remove(corners.size() - 1);
        return true;
    }

    public void clear() {
        corners.clear();
    }

    public UUID getOwnerUUID() {
        return ownerUUID;
    }

    public List<Location> getCorners() {
        return Collections.unmodifiableList(corners);
    }

    public int size() {
        return corners.size();
    }

    public boolean isEmpty() {
        return corners.isEmpty();
    }

    public boolean isComplete() {
        return corners.size() >= 3;
    }

    public String getWorldName() {
        if (corners.isEmpty()) return null;
        Location first = corners.get(0);
        return first.getWorld() != null ? first.getWorld().getName() : null;
    }

    public List<String> buildSummary() {
        List<String> lines = new ArrayList<>();
        lines.add("§6§l--- Zone Selection ---");
        lines.add("§7Corners: §f" + corners.size());
        for (int i = 0; i < corners.size(); i++) {
            Location c = corners.get(i);
            lines.add(String.format("§7  #%d §f(%d, %d, %d)", i + 1, c.getBlockX(), c.getBlockY(), c.getBlockZ()));
        }
        if (isComplete()) {
            lines.add("§aValid polygon — use §e/zone save <name> §ato save.");
        } else {
            lines.add("§cNeed at least §f3 §ccorners (currently " + corners.size() + ").");
        }
        return lines;
    }
}

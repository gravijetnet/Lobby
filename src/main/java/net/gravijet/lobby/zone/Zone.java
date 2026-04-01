package net.gravijet.lobby.zone;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Zone {

    private final String name;
    private final String worldName;
    private final int minY;
    private final int maxY;
    private final List<int[]> corners;

    private String requiredPermission;
    private String denyMessage;
    private boolean allowBlockPlacement;
    private boolean allowFlight = true;

    private static final String DEFAULT_DENY_MESSAGE = "§cYou are not allowed to enter this area!";

    Zone(String name, String worldName, int minY, int maxY, List<int[]> corners) {
        this.name = name;
        this.worldName = worldName;
        this.minY = minY;
        this.maxY = maxY;
        this.corners = Collections.unmodifiableList(new ArrayList<>(corners));
        this.requiredPermission = "zone.entry." + this.name.toLowerCase();
        this.denyMessage = DEFAULT_DENY_MESSAGE;
        this.allowBlockPlacement = false;
    }

    public String getName() {
        return name;
    }

    public String getWorldName() {
        return worldName;
    }

    public int getMinY() {
        return minY;
    }

    public int getMaxY() {
        return maxY;
    }

    public List<int[]> getCorners() {
        return corners;
    }

    public String getRequiredPermission() {
        return requiredPermission;
    }

    public String getDenyMessage() {
        return denyMessage;
    }

    public boolean isAllowBlockPlacement() {
        return allowBlockPlacement;
    }

    public boolean isAllowFlight() {
        return allowFlight;
    }

    public boolean isRestricted() {
        return requiredPermission != null && !requiredPermission.isEmpty();
    }

    public boolean canEnter(Player player) {
        if (!isRestricted()) return true;
        return player.hasPermission(requiredPermission);
    }

    void setRequiredPermission(String permission) {
        if (permission == null || permission.trim().isEmpty()) {
            this.requiredPermission = "zone.entry." + this.name.toLowerCase();
        } else {
            this.requiredPermission = permission.trim().toLowerCase();
        }
    }

    void setDenyMessage(String message) {
        this.denyMessage = (message != null && !message.isEmpty()) ? message : DEFAULT_DENY_MESSAGE;
    }

    void setAllowBlockPlacement(boolean allow) {
        this.allowBlockPlacement = allow;
    }

    void setAllowFlight(boolean allow) {
        this.allowFlight = allow;
    }

    public boolean contains(Location loc) {
        if (loc.getWorld() == null) return false;
        if (!loc.getWorld().getName().equals(worldName)) return false;
        int by = loc.getBlockY();
        if (by < minY || by > maxY) return false;
        if (corners.size() < 3) return false;
        return isInsidePolygon(loc.getBlockX() + 0.5, loc.getBlockZ() + 0.5);
    }

    private boolean isInsidePolygon(double px, double pz) {
        int n = corners.size();
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = corners.get(i)[0] + 0.5, zi = corners.get(i)[1] + 0.5;
            double xj = corners.get(j)[0] + 0.5, zj = corners.get(j)[1] + 0.5;
            if (((zi > pz) != (zj > pz)) && (px < (xj - xi) * (pz - zi) / (zj - zi) + xi)) {
                inside = !inside;
            }
        }
        return inside;
    }

    @Override
    public String toString() {
        return "Zone{name=" + name + ", world=" + worldName + ", corners=" + corners.size() + ", y=[" + minY + "," + maxY + "]" + (isRestricted() ? ", perm=" + requiredPermission : "") + "}";
    }
}

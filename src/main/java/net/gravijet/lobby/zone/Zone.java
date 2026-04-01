package net.gravijet.lobby.zone;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Zone {

    private final String      name;
    private final String      worldName;
    private final int         minY;
    private final int         maxY;
    private final List<int[]> corners;

    private String  requiredPermission;
    private String  denyMessage;
    private boolean allowBlockPlacement;
    private boolean allowFlight = true;

    private static final String DEFAULT_DENY_MESSAGE = "§cYou are not allowed to enter this area!";

    Zone(String name, String worldName, int minY, int maxY, List<int[]> corners) {
        this.name                = name;
        this.worldName           = worldName;
        this.minY                = minY;
        this.maxY                = maxY;
        this.corners             = Collections.unmodifiableList(new ArrayList<>(corners));
        this.requiredPermission  = null;
        this.denyMessage         = DEFAULT_DENY_MESSAGE;
        this.allowBlockPlacement = false;
    }

    public String getName()       { return name; }
    public String getWorldName()  { return worldName; }
    public int    getMinY()       { return minY; }
    public int    getMaxY()       { return maxY; }
    public List<int[]> getCorners() { return corners; }

    public String  getRequiredPermission()  { return requiredPermission; }
    public String  getDenyMessage()         { return denyMessage; }
    public boolean isAllowBlockPlacement()  { return allowBlockPlacement; }
    public boolean isAllowFlight()          { return allowFlight; }

    public boolean isRestricted() { return requiredPermission != null && !requiredPermission.isEmpty(); }

    public boolean canEnter(Player player) {
        if (!isRestricted()) return true;
        return player.hasPermission(requiredPermission);
    }

    void setRequiredPermission(String permission) {
        if (permission != null) {
            permission = permission.trim();
            if (permission.isEmpty()) permission = null;
        }
        this.requiredPermission = permission;
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

    public boolean containsPoint(String worldName, double x, double y, double z) {
        if (!this.worldName.equals(worldName)) return false;
        if (y < minY || y > maxY) return false;
        if (corners.size() < 3) return false;
        return isInsidePolygon(x, z);
    }

    private boolean isInsidePolygon(double px, double pz) {
        int n = corners.size();
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = corners.get(i)[0], zi = corners.get(i)[1];
            double xj = corners.get(j)[0], zj = corners.get(j)[1];
            if (((zi > pz) != (zj > pz)) && (px < (xj - xi) * (pz - zi) / (zj - zi) + xi)) {
                inside = !inside;
            }
        }
        return inside;
    }

    List<double[]> edgePoints(double y, double spacing) {
        List<double[]> out = new ArrayList<>();
        int n = corners.size();
        if (n < 2) return out;
        for (int i = 0; i < n; i++) {
            double ax = corners.get(i)[0] + 0.5,    az = corners.get(i)[1] + 0.5;
            double bx = corners.get((i + 1) % n)[0] + 0.5, bz = corners.get((i + 1) % n)[1] + 0.5;
            double dx = bx - ax, dz = bz - az;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len == 0) continue;
            double ux = dx / len, uz = dz / len;
            for (double t = 0; t <= len; t += spacing) {
                out.add(new double[]{ ax + ux * t, y, az + uz * t });
            }
        }
        return out;
    }

    List<double[]> cornerPillarPoints(double baseY, double height) {
        List<double[]> out = new ArrayList<>();
        for (int[] c : corners) {
            double cx = c[0] + 0.5, cz = c[1] + 0.5;
            for (double dy = 0; dy <= height; dy += 0.5) {
                out.add(new double[]{ cx, baseY + dy, cz });
            }
        }
        return out;
    }

    @Override
    public String toString() {
        return "Zone{name=" + name + ", world=" + worldName
                + ", corners=" + corners.size()
                + ", y=[" + minY + "," + maxY + "]"
                + (isRestricted() ? ", perm=" + requiredPermission : "")
                + "}";
    }
}

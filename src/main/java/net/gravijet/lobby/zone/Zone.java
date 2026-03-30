package net.gravijet.lobby.zone;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Polygon zone defined by 2-D corner points (X, Z) and Y-bounds.
 *
 * Geometry (corners, worldName, minY, maxY) is set at construction and never
 * mutated.  Access-control state (requiredPermission, denyMessage) is mutable
 * via package-private setters so ZoneManager can update it without replacing
 * the whole object.
 *
 * Containment uses the ray-casting algorithm: a ray in the +X direction
 * from the test point counts edge crossings. An odd count means inside.
 */
public final class Zone {

    // -------------------------------------------------------------------------
    // Immutable geometry
    // -------------------------------------------------------------------------

    private final String      name;
    private final String      worldName;
    private final int         minY;
    private final int         maxY;
    /** Each element is int[]{x, z}. Minimum 3 entries for a valid polygon. */
    private final List<int[]> corners;

    // -------------------------------------------------------------------------
    // Mutable access control (updated by ZoneManager only)
    // -------------------------------------------------------------------------

    /**
     * Permission node required to enter this zone. {@code null} means the zone
     * is open to all players.
     */
    private String requiredPermission;

    /**
     * Message sent (and title shown) when a player is denied entry.
     * Supports §-colour codes.
     */
    private String denyMessage;

    private static final String DEFAULT_DENY_MESSAGE = "§cYou are not allowed to enter this area!";

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    Zone(String name, String worldName, int minY, int maxY, List<int[]> corners) {
        this.name                = name;
        this.worldName           = worldName;
        this.minY                = minY;
        this.maxY                = maxY;
        this.corners             = Collections.unmodifiableList(new ArrayList<>(corners));
        this.requiredPermission  = null;
        this.denyMessage         = DEFAULT_DENY_MESSAGE;
    }

    // -------------------------------------------------------------------------
    // Geometry accessors (immutable)
    // -------------------------------------------------------------------------

    public String getName()       { return name; }
    public String getWorldName()  { return worldName; }
    public int    getMinY()       { return minY; }
    public int    getMaxY()       { return maxY; }

    /** Unmodifiable list of [x, z] pairs in selection order. */
    public List<int[]> getCorners() { return corners; }

    // -------------------------------------------------------------------------
    // Access-control accessors
    // -------------------------------------------------------------------------

    /** The permission node required to enter, or {@code null} if unrestricted. */
    public String getRequiredPermission() { return requiredPermission; }

    /** The message sent to players who are denied entry. Never {@code null}. */
    public String getDenyMessage()        { return denyMessage; }

    /** Returns {@code true} if this zone has an entry restriction. */
    public boolean isRestricted() { return requiredPermission != null && !requiredPermission.isEmpty(); }

    /**
     * Returns {@code true} if the player is allowed to enter this zone.
     * Always returns {@code true} when the zone is unrestricted.
     */
    public boolean canEnter(Player player) {
        if (!isRestricted()) return true;
        return player.hasPermission(requiredPermission);
    }

    /**
     * Returns {@code true} if the given player does NOT have the required
     * permission to enter this zone.  Always returns {@code false} when the
     * zone is unrestricted.
     * @deprecated Use {@link #canEnter(Player)} instead
     */
    @Deprecated
    public boolean isRestrictedFor(Player player) {
        if (!isRestricted()) return false;
        return !player.hasPermission(requiredPermission);
    }

    // -------------------------------------------------------------------------
    // Access-control mutators (package-private — only ZoneManager may call)
    // -------------------------------------------------------------------------

    void setRequiredPermission(String permission) {
        this.requiredPermission = (permission != null && !permission.isEmpty()) ? permission : null;
    }

    void setDenyMessage(String message) {
        this.denyMessage = (message != null && !message.isEmpty()) ? message : DEFAULT_DENY_MESSAGE;
    }

    // -------------------------------------------------------------------------
    // Containment
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the given block location is inside this zone.
     * Tests the block-centre point (blockX + 0.5, blockZ + 0.5).
     */
    public boolean contains(Location loc) {
        if (loc.getWorld() == null) return false;
        if (!loc.getWorld().getName().equals(worldName)) return false;

        int by = loc.getBlockY();
        if (by < minY || by > maxY) return false;
        if (corners.size() < 3) return false;

        return isInsidePolygon(loc.getBlockX() + 0.5, loc.getBlockZ() + 0.5);
    }

    /**
     * Returns {@code true} if the given fractional (player-position) coordinates
     * are inside this zone. Used for movement checks where the exact floating-
     * point position matters more than a block-centre approximation.
     */
    public boolean containsPoint(String worldName, double x, double y, double z) {
        if (!this.worldName.equals(worldName)) return false;
        if (y < minY || y > maxY) return false;
        if (corners.size() < 3) return false;
        return isInsidePolygon(x, z);
    }

    /**
     * Ray-casting point-in-polygon on the X-Z plane.
     * Casts a ray from (px, pz) in the +X direction and counts edge crossings.
     */
    private boolean isInsidePolygon(double px, double pz) {
        int n = corners.size();
        boolean inside = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = corners.get(i)[0];
            double zi = corners.get(i)[1];
            double xj = corners.get(j)[0];
            double zj = corners.get(j)[1];

            if (((zi > pz) != (zj > pz))
                    && (px < (xj - xi) * (pz - zi) / (zj - zi) + xi)) {
                inside = !inside;
            }
        }
        return inside;
    }

    // -------------------------------------------------------------------------
    // Geometry helpers for particle rendering (package-private)
    // -------------------------------------------------------------------------

    List<double[]> edgePoints(double y, double spacing) {
        List<double[]> out = new ArrayList<>();
        int n = corners.size();
        if (n < 2) return out;

        for (int i = 0; i < n; i++) {
            double ax = corners.get(i)[0] + 0.5;
            double az = corners.get(i)[1] + 0.5;
            double bx = corners.get((i + 1) % n)[0] + 0.5;
            double bz = corners.get((i + 1) % n)[1] + 0.5;

            double dx  = bx - ax;
            double dz  = bz - az;
            double len = Math.sqrt(dx * dx + dz * dz);
            if (len == 0) continue;

            double ux = dx / len;
            double uz = dz / len;
            for (double t = 0; t <= len; t += spacing) {
                out.add(new double[]{ ax + ux * t, y, az + uz * t });
            }
        }
        return out;
    }

    List<double[]> cornerPillarPoints(double baseY, double height) {
        List<double[]> out = new ArrayList<>();
        for (int[] c : corners) {
            double cx = c[0] + 0.5;
            double cz = c[1] + 0.5;
            for (double dy = 0; dy <= height; dy += 0.5) {
                out.add(new double[]{ cx, baseY + dy, cz });
            }
        }
        return out;
    }

    // -------------------------------------------------------------------------

    @Override
    public String toString() {
        return "Zone{name=" + name + ", world=" + worldName
                + ", corners=" + corners.size()
                + ", y=[" + minY + "," + maxY + "]"
                + (isRestricted() ? ", perm=" + requiredPermission : "")
                + "}";
    }
}

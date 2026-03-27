package net.gravijet.lobby.zone;

import org.bukkit.Location;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable polygon zone defined by 2-D corner points (X, Z) and Y-bounds.
 * Block-placement inside this zone is forbidden for non-build-mode players.
 *
 * Containment uses the ray-casting algorithm: a ray in the +X direction
 * from the test point counts edge crossings. An odd count means inside.
 */
public final class Zone {

    private final String     name;
    private final String     worldName;
    private final int        minY;
    private final int        maxY;
    /** Each element is int[]{x, z}. Minimum 3 entries for a valid polygon. */
    private final List<int[]> corners;

    Zone(String name, String worldName, int minY, int maxY, List<int[]> corners) {
        this.name      = name;
        this.worldName = worldName;
        this.minY      = minY;
        this.maxY      = maxY;
        this.corners   = Collections.unmodifiableList(new ArrayList<>(corners));
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String getName()      { return name; }
    public String getWorldName() { return worldName; }
    public int    getMinY()      { return minY; }
    public int    getMaxY()      { return maxY; }

    /** Unmodifiable list of [x, z] pairs in selection order. */
    public List<int[]> getCorners() { return corners; }

    // -------------------------------------------------------------------------
    // Containment
    // -------------------------------------------------------------------------

    /**
     * Returns true if the given block location is inside this zone.
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

            // Only edges that straddle pz contribute to the crossing count.
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

    /**
     * Returns evenly-spaced points (x, y, z) along all polygon edges at the
     * given Y-level. Used by ZoneManager to render edge particles.
     *
     * @param y      world Y at which to emit particles
     * @param spacing block-unit distance between consecutive points
     */
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

    /**
     * Returns corner-pillar particle points: a short column at each corner
     * going from {@code baseY} up by {@code height} blocks in steps of 0.5.
     */
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

    @Override
    public String toString() {
        return "Zone{name=" + name + ", world=" + worldName
                + ", corners=" + corners.size()
                + ", y=[" + minY + "," + maxY + "]}";
    }
}

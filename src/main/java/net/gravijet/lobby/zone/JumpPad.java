package net.gravijet.lobby.zone;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.util.List;

/**
 * Represents a jump pad region that launches players who enter it.
 * The region is defined by a polygon (X-Z corners) and Y‑bounds, identical to a Zone.
 * Each pad has a strength (velocity multiplier) and a direction vector.
 */
public final class JumpPad {

    private final Zone zone;
    private final double strength;
    private final double directionX;
    private final double directionY;
    private final double directionZ;
    private final long cooldownMillis;

    /**
     * Creates a new jump pad.
     *
     * @param zone            the underlying zone defining the region
     * @param strength        multiplier for the launch velocity (e.g., 1.5)
     * @param directionX      X component of the direction vector (will be normalised)
     * @param directionY      Y component of the direction vector (will be normalised)
     * @param directionZ      Z component of the direction vector (will be normalised)
     * @param cooldownMillis  minimum time between consecutive launches for the same player (ms)
     */
    public JumpPad(Zone zone, double strength,
                   double directionX, double directionY, double directionZ,
                   long cooldownMillis) {
        this.zone = zone;
        this.strength = strength;

        // Normalise the direction vector
        double len = Math.sqrt(directionX * directionX + directionY * directionY + directionZ * directionZ);
        if (len == 0) {
            // Default direction is straight up
            this.directionX = 0.0;
            this.directionY = 1.0;
            this.directionZ = 0.0;
        } else {
            this.directionX = directionX / len;
            this.directionY = directionY / len;
            this.directionZ = directionZ / len;
        }
        this.cooldownMillis = cooldownMillis;
    }

    /**
     * Creates a jump pad with a default cooldown of 1000 ms.
     */
    public JumpPad(Zone zone, double strength,
                   double directionX, double directionY, double directionZ) {
        this(zone, strength, directionX, directionY, directionZ, 1000L);
    }

    /**
     * Creates a vertical‑up jump pad with the given strength.
     */
    public JumpPad(Zone zone, double strength) {
        this(zone, strength, 0.0, 1.0, 0.0, 1000L);
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public Zone getZone()               { return zone; }
    public double getStrength()         { return strength; }
    public double getDirectionX()       { return directionX; }
    public double getDirectionY()       { return directionY; }
    public double getDirectionZ()       { return directionZ; }
    public long getCooldownMillis()     { return cooldownMillis; }

    public String getName()             { return zone.getName(); }
    public String getWorldName()        { return zone.getWorldName(); }
    public int getMinY()                { return zone.getMinY(); }
    public int getMaxY()                { return zone.getMaxY(); }
    public List<int[]> getCorners()     { return zone.getCorners(); }

    // -------------------------------------------------------------------------
    // Containment check
    // -------------------------------------------------------------------------

    /**
     * Returns {@code true} if the given location lies inside this jump pad.
     */
    public boolean contains(Location loc) {
        return zone.contains(loc);
    }

    /**
     * Returns {@code true} if the given player position is inside this pad.
     */
    public boolean containsPoint(String worldName, double x, double y, double z) {
        return zone.containsPoint(worldName, x, y, z);
    }

    // -------------------------------------------------------------------------
    // Launch
    // -------------------------------------------------------------------------

    /**
     * Applies the jump‑pad velocity to the player.
     * The resulting velocity is direction * strength.
     */
    public void apply(Player player) {
        Vector velocity = new Vector(directionX, directionY, directionZ).multiply(strength);
        player.setVelocity(velocity);
    }

    @Override
    public String toString() {
        return "JumpPad{name=" + getName() + ", world=" + getWorldName()
                + ", corners=" + getCorners().size()
                + ", y=[" + getMinY() + "," + getMaxY() + "]"
                + ", strength=" + strength
                + ", direction=(" + directionX + "," + directionY + "," + directionZ + ")"
                + "}";
    }
}
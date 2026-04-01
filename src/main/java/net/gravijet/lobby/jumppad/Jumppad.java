package net.gravijet.lobby.jumppad;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class Jumppad {

    private final String      name;
    private final Set<String> blockKeys = new HashSet<>();
    private double velX;
    private double velY;
    private double velZ;

    // Optional target landing location
    private boolean hasTarget  = false;
    private String  targetWorld;
    private double  targetX;
    private double  targetY;
    private double  targetZ;

    Jumppad(String name, double velX, double velY, double velZ) {
        this.name = name;
        this.velX = velX;
        this.velY = velY;
        this.velZ = velZ;
    }

    public String      getName()      { return name; }
    public Set<String> getBlockKeys() { return Collections.unmodifiableSet(blockKeys); }
    public int         getBlockCount(){ return blockKeys.size(); }
    public boolean     isEmpty()      { return blockKeys.isEmpty(); }
    public double      getVelX()      { return velX; }
    public double      getVelY()      { return velY; }
    public double      getVelZ()      { return velZ; }

    public boolean hasTarget()    { return hasTarget; }
    public String  getTargetWorld() { return targetWorld; }
    public double  getTargetX()   { return targetX; }
    public double  getTargetY()   { return targetY; }
    public double  getTargetZ()   { return targetZ; }

    void addBlockKey(String key)    { blockKeys.add(key); }
    void removeBlockKey(String key) { blockKeys.remove(key); }

    void setVelocity(double x, double y, double z) {
        this.velX = x;
        this.velY = y;
        this.velZ = z;
    }

    /**
     * Scales the entire velocity vector so its magnitude equals {@code strength}.
     * If the current vector is zero (no direction set yet), only velY is set.
     */
    void setStrength(double strength) {
        double mag = Math.sqrt(velX * velX + velY * velY + velZ * velZ);
        if (mag < 1e-6) {
            this.velY = strength;
        } else {
            double scale = strength / mag;
            this.velX *= scale;
            this.velY *= scale;
            this.velZ *= scale;
        }
    }

    void setTarget(String world, double x, double y, double z) {
        this.targetWorld = world;
        this.targetX     = x;
        this.targetY     = y;
        this.targetZ     = z;
        this.hasTarget   = true;
    }

    void clearTarget() {
        this.hasTarget   = false;
        this.targetWorld = null;
    }
}

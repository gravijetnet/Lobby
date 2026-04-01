package net.gravijet.lobby.jumppad;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public final class Jumppad {

    private final String name;
    private final Set<String> blockKeys = new HashSet<>();
    private double baseStrength;
    private double heightMultiplier;

    Jumppad(String name, double baseStrength, double heightMultiplier) {
        this.name = name;
        this.baseStrength = baseStrength;
        this.heightMultiplier = heightMultiplier;
    }

    public String getName() {
        return name;
    }

    public Set<String> getBlockKeys() {
        return Collections.unmodifiableSet(blockKeys);
    }

    public int getBlockCount() {
        return blockKeys.size();
    }

    public boolean isEmpty() {
        return blockKeys.isEmpty();
    }

    public double getBaseStrength() {
        return baseStrength;
    }

    public double getHeightMultiplier() {
        return heightMultiplier;
    }

    void addBlockKey(String key) {
        blockKeys.add(key);
    }

    void removeBlockKey(String key) {
        blockKeys.remove(key);
    }

    void setBaseStrength(double baseStrength) {
        this.baseStrength = baseStrength;
    }

    void setHeightMultiplier(double heightMultiplier) {
        this.heightMultiplier = heightMultiplier;
    }
}

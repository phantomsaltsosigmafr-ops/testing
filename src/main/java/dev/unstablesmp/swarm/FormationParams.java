package dev.unstablesmp.swarm;

import org.bukkit.configuration.ConfigurationSection;

/** Shape parameters for {@link SwarmFormation}. Plain fields so the layout math stays allocation-free and testable. */
public final class FormationParams {
    public double haloRadius = 1.8;
    public double haloHeight = 2.7;
    public double crownRadius = 9;
    public double crownHeight = 15;
    public double wedgeSpacing = 1.1;
    public double wedgeDistance = 3;
    public double wallSpacing = 0.85;
    public double wallDistance = 3;
    public double vortexRadius = 3.2;
    public double vortexHeight = 4.5;
    public double domeRadius = 4.5;
    public double pincerRadius = 5.5;
    public double rainHeight = 11;
    public double rainSpacing = 1.15;
    public double shieldRadius = 3.0;
    public double arrowHeight = 9;
    public double arrowBack = 7;
    public double arrowSpacing = 0.9;
    public double rotationSpeed = 1.0;
    public double breathe = 0.06;

    public static FormationParams load(ConfigurationSection c) {
        FormationParams p = new FormationParams();
        if (c == null) return p;
        p.haloRadius = c.getDouble("halo.radius", p.haloRadius);
        p.haloHeight = c.getDouble("halo.height", p.haloHeight);
        p.crownRadius = c.getDouble("sky-crown.radius", p.crownRadius);
        p.crownHeight = c.getDouble("sky-crown.height", p.crownHeight);
        p.wedgeSpacing = c.getDouble("v-wedge.spacing", p.wedgeSpacing);
        p.wedgeDistance = c.getDouble("v-wedge.distance", p.wedgeDistance);
        p.wallSpacing = c.getDouble("phalanx-wall.spacing", p.wallSpacing);
        p.wallDistance = c.getDouble("phalanx-wall.distance", p.wallDistance);
        p.vortexRadius = c.getDouble("spiral-vortex.radius", p.vortexRadius);
        p.vortexHeight = c.getDouble("spiral-vortex.height", p.vortexHeight);
        p.domeRadius = c.getDouble("dome-cage.radius", p.domeRadius);
        p.pincerRadius = c.getDouble("pincer.radius", p.pincerRadius);
        p.rainHeight = c.getDouble("rain.height", p.rainHeight);
        p.rainSpacing = c.getDouble("rain.spacing", p.rainSpacing);
        p.shieldRadius = c.getDouble("orbit-shield.radius", p.shieldRadius);
        p.arrowHeight = c.getDouble("arrowhead-dive.height", p.arrowHeight);
        p.arrowBack = c.getDouble("arrowhead-dive.back", p.arrowBack);
        p.arrowSpacing = c.getDouble("arrowhead-dive.spacing", p.arrowSpacing);
        p.rotationSpeed = c.getDouble("rotation-speed", p.rotationSpeed);
        p.breathe = c.getDouble("breathe", p.breathe);
        return p;
    }
}

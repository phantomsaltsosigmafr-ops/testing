package dev.unstablesmp.swarm;

import java.util.List;

/**
 * Boids steering (separation, alignment, cohesion) blended with formation-slot seeking. Pure math on the bots'
 * primitive fields; no allocation.
 */
final class SwarmSteering {
    private SwarmSteering() {
    }

    /**
     * Moves one bot one tick toward (tx, ty, tz).
     *
     * @param maxSpeed  speed cap for this bot this tick (dive speed while diving)
     */
    static void step(Vexbot b, List<Vexbot> flock, double tx, double ty, double tz, double maxSpeed,
                     SwarmSettings s, int now) {
        double dx = tx - b.x, dy = ty - b.y, dz = tz - b.z;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double desiredX = 0, desiredY = 0, desiredZ = 0;
        if (dist > 1e-4) {
            double speed = Math.min(maxSpeed, dist * s.slotGain + (b.state == Vexbot.DIVE ? maxSpeed : 0));
            desiredX = dx / dist * speed;
            desiredY = dy / dist * speed;
            desiredZ = dz / dist * speed;
        }
        double sepX = 0, sepY = 0, sepZ = 0;
        double aliX = 0, aliY = 0, aliZ = 0;
        double cohX = 0, cohY = 0, cohZ = 0;
        int count = 0;
        double sepSq = s.sepRadius * s.sepRadius;
        double nbSq = s.neighbourRadius * s.neighbourRadius;
        for (int i = 0, n = flock.size(); i < n; i++) {
            Vexbot o = flock.get(i);
            if (o == b || o.state == Vexbot.DYING) continue;
            double ox = b.x - o.x, oy = b.y - o.y, oz = b.z - o.z;
            double d2 = ox * ox + oy * oy + oz * oz;
            if (d2 > nbSq) continue;
            if (d2 < sepSq && d2 > 1e-6) {
                sepX += ox / d2;
                sepY += oy / d2;
                sepZ += oz / d2;
            }
            aliX += o.vx;
            aliY += o.vy;
            aliZ += o.vz;
            cohX += o.x;
            cohY += o.y;
            cohZ += o.z;
            count++;
        }
        double steerX = desiredX - b.vx, steerY = desiredY - b.vy, steerZ = desiredZ - b.vz;
        double sepW = b.state == Vexbot.DIVE ? s.sepWeight * 0.3 : s.sepWeight;
        steerX += sepX * sepW * 0.1;
        steerY += sepY * sepW * 0.1;
        steerZ += sepZ * sepW * 0.1;
        if (count > 0 && b.state == Vexbot.FOLLOW) {
            steerX += (aliX / count - b.vx) * s.aliWeight + (cohX / count - b.x) * s.cohWeight;
            steerY += (aliY / count - b.vy) * s.aliWeight + (cohY / count - b.y) * s.cohWeight;
            steerZ += (aliZ / count - b.vz) * s.aliWeight + (cohZ / count - b.z) * s.cohWeight;
        }
        // Gentle organic wander.
        steerX += s.wander * Math.sin(now * 0.07 + b.phase * 13);
        steerY += s.wander * Math.sin(now * 0.05 + b.phase * 7) * 0.6;
        steerZ += s.wander * Math.cos(now * 0.06 + b.phase * 11);

        double force = b.state == Vexbot.DIVE ? s.maxForce * 3 : s.maxForce;
        double sl = Math.sqrt(steerX * steerX + steerY * steerY + steerZ * steerZ);
        if (sl > force) {
            double k = force / sl;
            steerX *= k;
            steerY *= k;
            steerZ *= k;
        }
        b.vx += steerX;
        b.vy += steerY;
        b.vz += steerZ;
        double vl = Math.sqrt(b.vx * b.vx + b.vy * b.vy + b.vz * b.vz);
        if (vl > maxSpeed) {
            double k = maxSpeed / vl;
            b.vx *= k;
            b.vy *= k;
            b.vz *= k;
        }
        b.x += b.vx;
        b.y += b.vy;
        b.z += b.vz;
    }
}

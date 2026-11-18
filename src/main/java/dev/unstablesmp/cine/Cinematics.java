package dev.unstablesmp.cine;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.core.Ticker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/** Screen effects for recordings: fade to/from black, camera shake, slow motion (server tick rate) and titles. */
public final class Cinematics implements Ticker.Tickable {
    private record Shake(UUID id, int until, float strength) {
    }

    private final UnstableSMP plugin;
    private final List<Shake> shakes = new ArrayList<>();
    private int slowmoUntil = -1;

    public Cinematics(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    public void fadeOut(List<Player> ps, int ticks) {
        for (Player p : ps) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, ticks + 40, 0, false, false, false));
            p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, ticks + 40, 0, false, false, false));
        }
    }

    public void fadeIn(List<Player> ps) {
        for (Player p : ps) {
            p.removePotionEffect(PotionEffectType.BLINDNESS);
            p.removePotionEffect(PotionEffectType.DARKNESS);
        }
    }

    public void shake(List<Player> ps, int ticks, float strength) {
        int until = Bukkit.getCurrentTick() + ticks;
        for (Player p : ps) shakes.add(new Shake(p.getUniqueId(), until, strength));
    }

    /** Slows the whole server's tick rate (true slow motion), restored after {@code ticks} slowed ticks. */
    public void slowmo(float rate, int ticks) {
        Bukkit.getServerTickManager().setTickRate(Math.max(1f, Math.min(20f, rate)));
        slowmoUntil = Bukkit.getCurrentTick() + Math.max(1, ticks);
    }

    public void resetTickRate() {
        Bukkit.getServerTickManager().setTickRate(20f);
        slowmoUntil = -1;
    }

    public void title(List<Player> ps, Component title, Component sub, int in, int stay, int out) {
        Title t = Title.title(title, sub, Title.Times.times(Duration.ofMillis(in * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(out * 50L)));
        for (Player p : ps) p.showTitle(t);
    }

    @Override
    public void tick(int now) {
        if (slowmoUntil >= 0 && now >= slowmoUntil) resetTickRate();
        if (shakes.isEmpty()) return;
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = shakes.size() - 1; i >= 0; i--) {
            Shake s = shakes.get(i);
            Player p = Bukkit.getPlayer(s.id());
            if (p == null || now >= s.until()) {
                shakes.remove(i);
                continue;
            }
            if (plugin.camera().watching(p)) continue;
            Location l = p.getLocation();
            l.setYaw(l.getYaw() + (float) r.nextGaussian() * s.strength());
            l.setPitch(Math.max(-90, Math.min(90, l.getPitch() + (float) r.nextGaussian() * s.strength())));
            p.teleport(l);
        }
    }

    @Override
    public String name() {
        return "cinematics";
    }
}

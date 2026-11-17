package dev.unstablesmp.faction;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.core.Ground;
import dev.unstablesmp.core.Messages;
import dev.unstablesmp.core.Ticker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

/** Faction-vs-faction battles: both armies spawn facing each other and fight until one side is wiped out. */
public final class BattleManager implements Ticker.Tickable {

    public static final class Battle {
        public final Faction a, b;
        final Location centre;
        final int startTick;
        int endedAt = -1;

        Battle(Faction a, Faction b, Location centre, int startTick) {
            this.a = a;
            this.b = b;
            this.centre = centre;
            this.startTick = startTick;
        }
    }

    private final UnstableSMP plugin;
    private final List<Battle> battles = new ArrayList<>();
    private final Random rnd = new Random();
    private double gap = 22;
    private double distance = 18;
    private int maxSize = 60;
    private int defaultSize = 10;
    private int cleanupTicks = 400;
    private double announceRange = 160;
    private final List<String> presets = new ArrayList<>();

    public BattleManager(UnstableSMP plugin) {
        this.plugin = plugin;
    }

    public void reload(ConfigurationSection c) {
        presets.clear();
        if (c == null) return;
        gap = c.getDouble("gap", 22);
        distance = c.getDouble("distance-in-front", 18);
        maxSize = Math.max(1, c.getInt("max-size", 60));
        defaultSize = Math.max(1, Math.min(maxSize, c.getInt("default-size", 10)));
        cleanupTicks = Math.max(0, c.getInt("cleanup-after-seconds", 20) * 20);
        announceRange = c.getDouble("announce-range", 160);
        presets.addAll(c.getStringList("presets"));
    }

    public List<String> presets() {
        return presets;
    }

    public int maxSize() {
        return maxSize;
    }

    public int defaultSize() {
        return defaultSize;
    }

    public List<Battle> running() {
        return battles;
    }

    /**
     * Starts a battle in front of {@code at} (facing at's yaw). Returns null on success, or a message key explaining
     * why it could not start.
     */
    public String start(Location at, Faction a, Faction b, int size) {
        if (a == null || b == null || a == b) return "battle.pick-two";
        if (a.battle != null || b.battle != null) return "battle.already-fighting";
        size = Math.max(1, Math.min(maxSize, size));
        World w = at.getWorld();
        double yaw = Math.toRadians(at.getYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        Location c = Ground.groundAt(w, at.getX() + fx * distance, at.getY(), at.getZ() + fz * distance);
        if (!plugin.regions().canRunEvent(c)) return "event.region-denied";
        double sx = -Math.cos(yaw), sz = -Math.sin(yaw); // armies line up left vs right of the viewer
        double half = gap / 2.0;
        Location ca = Ground.groundAt(w, c.getX() - sx * half, c.getY(), c.getZ() - sz * half);
        Location cb = Ground.groundAt(w, c.getX() + sx * half, c.getY(), c.getZ() + sz * half);
        ca.setDirection(cb.toVector().subtract(ca.toVector()));
        cb.setDirection(ca.toVector().subtract(cb.toVector()));
        int na = deploy(a, ca, size);
        int nb = deploy(b, cb, size);
        if (na == 0 || nb == 0) {
            plugin.units().disband(a);
            plugin.units().disband(b);
            return "battle.cap";
        }
        Battle bt = new Battle(a, b, c, org.bukkit.Bukkit.getCurrentTick());
        a.battle = bt;
        b.battle = bt;
        battles.add(bt);
        Component msg = plugin.messages().get("battle.start", Messages.c("side_a", a.display), Messages.c("side_b", b.display),
                Messages.p("size", Integer.toString(size)));
        Title title = Title.title(plugin.messages().get("battle.start-title"),
                plugin.messages().get("battle.start-subtitle", Messages.c("side_a", a.display), Messages.c("side_b", b.display)),
                Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2200), Duration.ofMillis(500)));
        for (Player x : nearby(c)) {
            x.sendMessage(msg);
            x.showTitle(title);
            x.playSound(x.getLocation(), Sound.EVENT_RAID_HORN, 1f, 0.8f);
        }
        return null;
    }

    private int deploy(Faction f, Location at, int size) {
        f.follow = false;
        f.anchor = at.clone();
        f.anchorYaw = at.getYaw();
        f.focus = null;
        f.mode = Faction.Mode.AGGRESSIVE;
        f.fightFactions = true;
        int n = 0;
        UnitTemplate lt = plugin.factions().template(f.leaderTemplate);
        if (lt != null && plugin.units().spawn(f, lt, at.clone()) != null) n++;
        double yaw = Math.toRadians(at.getYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        double[] o = new double[2];
        for (int i = 0; i < size; i++) {
            String id = f.pickArmy(rnd);
            UnitTemplate t = plugin.factions().template(id);
            if (t == null) continue;
            f.formation.offset(i, size, f.spacing, o);
            Location l = Ground.groundAt(at.getWorld(), at.getX() + rx * o[0] - fx * (o[1] + 2),
                    at.getY(), at.getZ() + rz * o[0] - fz * (o[1] + 2));
            if (t.flying) l.add(0, 3, 0);
            l.setYaw(at.getYaw());
            if (plugin.units().spawn(f, t, l) != null) n++;
        }
        return n;
    }

    @Override
    public void tick(int now) {
        if (battles.isEmpty() || now % 20 != 0) return;
        for (int i = battles.size() - 1; i >= 0; i--) {
            Battle bt = battles.get(i);
            if (bt.endedAt >= 0) {
                if (now - bt.endedAt >= cleanupTicks) {
                    plugin.units().disband(bt.a);
                    plugin.units().disband(bt.b);
                    battles.remove(i);
                }
                continue;
            }
            if (now - bt.startTick < 100) continue; // give both armies time to spawn in
            int na = plugin.units().count(bt.a), nb = plugin.units().count(bt.b);
            if (na == 0 || nb == 0) finish(bt, na == 0 && nb == 0 ? null : (na == 0 ? bt.b : bt.a), now);
        }
    }

    private void finish(Battle bt, Faction winner, int now) {
        bt.endedAt = now;
        bt.a.battle = null;
        bt.b.battle = null;
        Component msg;
        Title title;
        if (winner == null) {
            msg = plugin.messages().get("battle.draw", Messages.c("side_a", bt.a.display), Messages.c("side_b", bt.b.display));
            title = Title.title(plugin.messages().get("battle.draw-title"), Component.empty());
        } else {
            Faction loser = winner == bt.a ? bt.b : bt.a;
            msg = plugin.messages().get("battle.victory", Messages.c("winner", winner.display), Messages.c("loser", loser.display));
            title = Title.title(plugin.messages().get("battle.victory-title", Messages.c("winner", winner.display)),
                    plugin.messages().get("battle.victory-subtitle", Messages.c("loser", loser.display)));
            winner.mode = Faction.Mode.DEFEND;
        }
        plugin.getComponentLogger().info(msg);
        for (Player p : nearby(bt.centre)) {
            p.sendMessage(msg);
            p.showTitle(title);
            p.playSound(p.getLocation(), winner == null ? Sound.UI_BUTTON_CLICK : Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        }
    }

    /** Ends every battle now and removes both armies. */
    public int endAll() {
        int n = 0;
        for (Battle bt : battles) {
            bt.a.battle = null;
            bt.b.battle = null;
            plugin.units().disband(bt.a);
            plugin.units().disband(bt.b);
            n++;
        }
        battles.clear();
        return n;
    }

    private List<Player> nearby(Location c) {
        List<Player> out = new ArrayList<>();
        double r = announceRange * announceRange;
        for (Player p : c.getWorld().getPlayers()) if (p.getLocation().distanceSquared(c) < r) out.add(p);
        return out;
    }

    @Override
    public String name() {
        return "battles";
    }
}

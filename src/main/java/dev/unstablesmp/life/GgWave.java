package dev.unstablesmp.life;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** /uss gg: every bot online spams a gg (GG, gg, gG, Gg...) over a few seconds, some twice, like a real lobby. */
public final class GgWave {
    private GgWave() {
    }

    /** Returns how many bots were set off. {@code isBot} decides who counts (residents, bandits, fake players). */
    public static int run(Plugin plugin, LifeConfig cfg, Predicate<Player> isBot) {
        List<Player> bots = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (isBot.test(p)) bots.add(p);
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        for (Player p : bots) {
            long first = 1 + rnd.nextInt(cfg.ggSpreadTicks);
            say(plugin, p, cfg.ggLines.get(rnd.nextInt(cfg.ggLines.size())), first);
            if (rnd.nextDouble() < cfg.ggDoubleChance) {
                say(plugin, p, cfg.ggLines.get(rnd.nextInt(cfg.ggLines.size())), first + 10 + rnd.nextInt(30));
            }
        }
        return bots.size();
    }

    private static void say(Plugin plugin, Player p, String line, long delay) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) p.chat(line);
        }, delay);
    }
}

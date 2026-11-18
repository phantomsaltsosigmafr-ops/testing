package dev.unstablesmp.cine;

import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.bot.Bot;
import dev.unstablesmp.bot.BotGroup;
import dev.unstablesmp.bot.BotSpawner;
import dev.unstablesmp.bot.PvpMode;
import dev.unstablesmp.bot.SkillTier;
import dev.unstablesmp.core.Ticker;
import dev.unstablesmp.core.Text;
import dev.unstablesmp.game.GameService;
import dev.unstablesmp.kit.Kit;
import dev.unstablesmp.map.MapService;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * Scripted scenes for recordings (plugins/UnstableSMP/scenes/*.yml): a timeline of steps (titles, fake chat,
 * joins, bot spawns and orders, camera paths, slow motion, fades, sounds, console commands, games) played for a
 * set of players. Perfect for non-canon POV skits.
 */
public final class SceneService implements Ticker.Tickable {
    private record Step(int at, Map<String, Object> data) {
    }

    private static final class Run {
        final String name;
        final List<Step> steps;
        final List<Player> viewers;
        final int start;
        int index;

        Run(String name, List<Step> steps, List<Player> viewers, int start) {
            this.name = name;
            this.steps = steps;
            this.viewers = viewers;
            this.start = start;
        }
    }

    private final UnstableSMP plugin;
    private final File dir;
    private final Map<String, List<Step>> scenes = new LinkedHashMap<>();
    private final List<Run> runs = new ArrayList<>();

    public SceneService(UnstableSMP plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "scenes");
    }

    public void reload() {
        scenes.clear();
        if (!dir.exists() && dir.mkdirs()) {
            for (String example : new String[] {"null_invasion.yml", "law_arrest.yml"}) {
                try (InputStream in = plugin.getResource("scenes/" + example)) {
                    if (in != null) Files.copy(in, new File(dir, example).toPath());
                } catch (IOException ex) {
                    plugin.getLogger().warning("Could not write example scene " + example + ": " + ex.getMessage());
                }
            }
        }
        File[] files = dir.listFiles((d, n) -> n.endsWith(".yml"));
        if (files == null) return;
        for (File f : files) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            List<Step> steps = new ArrayList<>();
            for (Map<?, ?> m : y.getMapList("steps")) {
                Map<String, Object> data = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : m.entrySet()) data.put(String.valueOf(e.getKey()), e.getValue());
                Object at = data.get("at");
                steps.add(new Step(at instanceof Number n ? n.intValue() : 0, data));
            }
            steps.sort(Comparator.comparingInt(Step::at));
            scenes.put(f.getName().substring(0, f.getName().length() - 4).toLowerCase(Locale.ROOT), steps);
        }
    }

    public List<String> names() {
        return new ArrayList<>(scenes.keySet());
    }

    public boolean play(String name, List<Player> viewers) {
        List<Step> steps = scenes.get(name.toLowerCase(Locale.ROOT));
        if (steps == null) return false;
        runs.add(new Run(name, steps, new ArrayList<>(viewers), Bukkit.getCurrentTick()));
        return true;
    }

    public int stopAll() {
        int n = runs.size();
        runs.clear();
        plugin.cinematics().resetTickRate();
        return n;
    }

    @Override
    public void tick(int now) {
        if (runs.isEmpty()) return;
        for (int i = runs.size() - 1; i >= 0; i--) {
            Run r = runs.get(i);
            int t = now - r.start;
            while (r.index < r.steps.size() && r.steps.get(r.index).at() <= t) {
                Step s = r.steps.get(r.index++);
                try {
                    run(r, s.data());
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("Scene " + r.name + " step at " + s.at() + " failed: " + ex.getMessage());
                }
            }
            if (r.index >= r.steps.size()) runs.remove(i);
        }
    }

    private static String str(Map<String, Object> d, String k, String def) {
        Object o = d.get(k);
        return o == null ? def : String.valueOf(o);
    }

    private static int num(Map<String, Object> d, String k, int def) {
        Object o = d.get(k);
        return o instanceof Number n ? n.intValue() : def;
    }

    private void run(Run r, Map<String, Object> d) {
        String action = str(d, "action", "").toLowerCase(Locale.ROOT);
        List<Player> who = r.viewers;
        switch (action) {
            case "title" -> plugin.cinematics().title(who, Text.mm(str(d, "text", "")), Text.mm(str(d, "sub", "")),
                    num(d, "in", 10), num(d, "stay", 60), num(d, "out", 10));
            case "actionbar" -> {
                Component c = Text.mm(str(d, "text", ""));
                for (Player p : who) p.sendActionBar(c);
            }
            case "message" -> {
                Component c = Text.mm(str(d, "text", ""));
                for (Player p : who) p.sendMessage(c);
            }
            case "chat" -> plugin.fakeChat().broadcast(plugin.fakeChat().chatLine(Text.mm(str(d, "name", "Player")), Text.mm(str(d, "message", ""))));
            case "join" -> plugin.fakeChat().broadcast(plugin.fakeChat().joinLine(Text.mm(str(d, "name", "Player"))));
            case "leave" -> plugin.fakeChat().broadcast(plugin.fakeChat().leaveLine(Text.mm(str(d, "name", "Player"))));
            case "death" -> plugin.fakeChat().broadcast(plugin.fakeChat().deathLine(Text.mm(str(d, "victim", "Player")),
                    d.containsKey("killer") ? Text.mm(str(d, "killer", "")) : null));
            case "sound" -> {
                Sound s = Sound.sound(Key.key(str(d, "sound", "minecraft:entity.wither.spawn")), Sound.Source.MASTER,
                        ((Number) d.getOrDefault("volume", 1.0)).floatValue(), ((Number) d.getOrDefault("pitch", 1.0)).floatValue());
                for (Player p : who) p.playSound(s);
            }
            case "fade" -> {
                if ("in".equalsIgnoreCase(str(d, "mode", "in"))) plugin.cinematics().fadeIn(who);
                else plugin.cinematics().fadeOut(who, num(d, "ticks", 40));
            }
            case "shake" -> plugin.cinematics().shake(who, num(d, "ticks", 20), ((Number) d.getOrDefault("strength", 1.5)).floatValue());
            case "slowmo" -> plugin.cinematics().slowmo(((Number) d.getOrDefault("rate", 6)).floatValue(), num(d, "ticks", 60));
            case "camera" -> plugin.camera().play(str(d, "path", ""), who);
            case "spawn" -> spawn(d);
            case "target" -> {
                String g = str(d, "group", "");
                Player p = who.isEmpty() ? null : who.get(0);
                if (p != null) for (Bot b : plugin.bots().all()) if (b.group.id.equalsIgnoreCase(g)) b.forcedTarget = p;
            }
            case "clear" -> {
                String g = str(d, "group", "all");
                plugin.bots().clear(b -> g.equalsIgnoreCase("all") || b.group.id.equalsIgnoreCase(g), d.get("announce") == Boolean.TRUE);
            }
            case "tp" -> {
                for (Player p : who) plugin.games().sendToMap(p, str(d, "map", ""), str(d, "spawn", "players"), str(d, "kit", ""), str(d, "team", ""));
            }
            case "kit" -> {
                Kit k = plugin.kits().get(str(d, "kit", ""));
                if (k != null) for (Player p : who) k.apply(p, true);
            }
            case "team" -> {
                for (Player p : who) plugin.teams().join(p, str(d, "team", "players"));
            }
            case "game" -> {
                GameService.Options o = new GameService.Options();
                o.group = str(d, "group", o.group);
                o.groupB = str(d, "group-b", o.groupB);
                o.count = num(d, "count", o.count);
                o.waves = num(d, "waves", o.waves);
                o.increment = num(d, "increment", o.increment);
                try {
                    plugin.games().start(GameService.Type.valueOf(str(d, "type", "WAVES").toUpperCase(Locale.ROOT)), str(d, "map", ""), o, who);
                } catch (IllegalArgumentException ex) {
                    plugin.getLogger().warning("Scene " + r.name + ": unknown game type " + str(d, "type", ""));
                }
            }
            case "command" -> {
                String cmd = str(d, "command", "");
                if (cmd.contains("{player}")) {
                    for (Player p : who) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName()));
                } else {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
                }
            }
            default -> plugin.getLogger().warning("Scene " + r.name + ": unknown action '" + action + "'");
        }
    }

    private void spawn(Map<String, Object> d) {
        BotGroup g = plugin.bots().group(str(d, "group", "random"));
        if (g == null) return;
        Location at = null;
        String mapName = str(d, "map", "");
        if (!mapName.isEmpty()) {
            MapService.GameMap m = plugin.maps().get(mapName);
            if (m != null) at = m.spawn(str(d, "spawn", "bots"));
        }
        if (at == null && !runs.isEmpty() && !runs.get(0).viewers.isEmpty()) at = runs.get(0).viewers.get(0).getLocation();
        if (at == null) return;
        BotSpawner.Request r = new BotSpawner.Request();
        r.group = g;
        r.at = at;
        r.mode = d.containsKey("mode") ? PvpMode.parse(str(d, "mode", "")) : null;
        r.tier = d.containsKey("tier") && !str(d, "tier", "").contains("-") ? SkillTier.pick(str(d, "tier", "")) : null;
        r.team = d.containsKey("team") ? str(d, "team", "") : null;
        r.announce = d.get("announce") != Boolean.FALSE;
        plugin.botSpawner().spawnBatch(r, num(d, "count", 1), d.get("leaders") != Boolean.FALSE, num(d, "spread", 4));
    }

    @Override
    public String name() {
        return "scenes";
    }
}

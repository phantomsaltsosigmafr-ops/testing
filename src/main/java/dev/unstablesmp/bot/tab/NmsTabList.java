package dev.unstablesmp.bot.tab;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import dev.unstablesmp.UnstableSMP;
import dev.unstablesmp.bot.Bot;
import dev.unstablesmp.bot.skin.NameGenerator;
import dev.unstablesmp.bot.skin.SkinHeads;
import io.papermc.paper.adventure.PaperAdventure;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.level.GameType;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

/**
 * Fake tab-list entries for bots, built from the server's own player-info packets. Changes are batched: every bot
 * added or removed during a tick goes out as a single packet, so spawning 500 bots costs one packet per player.
 * Pings follow the bot's tier (HT1 low, LT6 laggy) and fluctuate like real players; bots with a {@code user:}
 * skin get their real signed skin (and head icon) looked up once and cached.
 */
public final class NmsTabList implements TabList {
    private static final EnumSet<ClientboundPlayerInfoUpdatePacket.Action> ADD = EnumSet.of(
            ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LISTED,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME, ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LIST_ORDER,
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_HAT);
    private static final EnumSet<ClientboundPlayerInfoUpdatePacket.Action> LATENCY =
            EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.UPDATE_LATENCY);

    private final UnstableSMP plugin;
    /** Bots currently listed, by tab id (insertion ordered for stable resend). */
    private final Map<UUID, Bot> listed = new LinkedHashMap<>();
    private final List<Bot> pendingAdd = new ArrayList<>();
    private final List<UUID> pendingRemove = new ArrayList<>();
    private final Map<String, Property> textures = new HashMap<>();
    /** Signed head textures for skins the client can't look up itself (e.g. {@code pack:null}), from config. */
    private final Map<String, Property> headTextures = new HashMap<>();
    private final Set<String> fetching = new HashSet<>();
    private boolean enabled = true;
    private int maxListed = 400;
    private int pingBest = 12, pingWorst = 190;
    private int pingEvery = 100;

    public NmsTabList(UnstableSMP plugin) {
        this.plugin = plugin;
        // Build each packet once now so a server-internals mismatch fails here (and the plugin falls back to no tab
        // entries) instead of mid-game.
        UUID probe = UUID.randomUUID();
        new ClientboundPlayerInfoUpdatePacket(ADD, List.of(new ClientboundPlayerInfoUpdatePacket.Entry(probe,
                new GameProfile(probe, "probe", PropertyMap.EMPTY), true, 0, GameType.SURVIVAL,
                PaperAdventure.asVanilla(net.kyori.adventure.text.Component.empty()), true, 0, null)));
        new ClientboundPlayerInfoRemovePacket(List.of(probe));
        try {
            net.minecraft.server.network.ServerGamePacketListenerImpl.class.getMethod("send", Packet.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("connection.send(Packet) not found", e);
        }
    }

    public void reload(ConfigurationSection c) {
        if (c == null) return;
        boolean was = enabled;
        enabled = c.getBoolean("enabled", true);
        maxListed = Math.max(0, c.getInt("max-listed", 400));
        pingBest = Math.max(0, c.getInt("ping-best", 12));
        pingWorst = Math.max(pingBest, c.getInt("ping-worst", 190));
        pingEvery = Math.max(20, c.getInt("ping-update-ticks", 100));
        headTextures.clear();
        ConfigurationSection ht = c.getConfigurationSection("head-textures");
        if (ht != null) {
            for (String spec : ht.getKeys(false)) {
                Property p = parseTexture(ht.getString(spec, ""));
                if (p != null) headTextures.put(spec.trim().toLowerCase(Locale.ROOT), p);
            }
        }
        if (was && !enabled) clearAll();
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public void add(Bot b) {
        if (!enabled || listed.size() + pendingAdd.size() >= maxListed) return;
        b.ping = rollPing(b);
        pendingAdd.add(b);
        String user = userSkin(b);
        if (user != null && !textures.containsKey(user)) fetch(user);
    }

    @Override
    public void remove(Bot b) {
        if (listed.remove(b.tabId) != null) pendingRemove.add(b.tabId);
        pendingAdd.remove(b);
    }

    @Override
    public void sendAll(Player p) {
        if (!enabled || listed.isEmpty()) return;
        List<ClientboundPlayerInfoUpdatePacket.Entry> batch = new ArrayList<>();
        for (Bot b : listed.values()) {
            batch.add(entry(b));
            if (batch.size() >= 150) {
                send(p, new ClientboundPlayerInfoUpdatePacket(ADD, batch));
                batch = new ArrayList<>();
            }
        }
        if (!batch.isEmpty()) send(p, new ClientboundPlayerInfoUpdatePacket(ADD, batch));
    }

    @Override
    public void clearAll() {
        List<UUID> ids = new ArrayList<>(listed.keySet());
        ids.addAll(pendingRemove);
        listed.clear();
        pendingAdd.clear();
        pendingRemove.clear();
        if (!ids.isEmpty()) broadcast(new ClientboundPlayerInfoRemovePacket(ids));
    }

    @Override
    public void tick(int now) {
        if (!pendingRemove.isEmpty()) {
            broadcast(new ClientboundPlayerInfoRemovePacket(new ArrayList<>(pendingRemove)));
            pendingRemove.clear();
        }
        if (!pendingAdd.isEmpty()) {
            List<ClientboundPlayerInfoUpdatePacket.Entry> entries = new ArrayList<>(pendingAdd.size());
            for (Bot b : pendingAdd) {
                if (!b.valid()) continue;
                listed.put(b.tabId, b);
                entries.add(entry(b));
            }
            pendingAdd.clear();
            if (!entries.isEmpty()) broadcast(new ClientboundPlayerInfoUpdatePacket(ADD, entries));
        }
        if (now % pingEvery == 0 && !listed.isEmpty()) jitterPings();
    }

    // ------------------------------------------------------------------ entries

    private ClientboundPlayerInfoUpdatePacket.Entry entry(Bot b) {
        Property tex = texture(b);
        PropertyMap props = tex == null ? PropertyMap.EMPTY : new PropertyMap(ImmutableMultimap.of("textures", tex));
        GameProfile gp = new GameProfile(b.tabId, NameGenerator.profileSafe(b.plainName), props);
        int order = b.leader ? 20 : b.group.leader != null && b.plainName.equals(b.group.leader.name()) ? 20 : 0;
        return new ClientboundPlayerInfoUpdatePacket.Entry(b.tabId, gp, true, b.ping, GameType.SURVIVAL,
                PaperAdventure.asVanilla(b.name), true, order, null);
    }

    private int rollPing(Bot b) {
        double s = b.tier.skill();
        double base = pingWorst - (pingWorst - pingBest) * s;
        return (int) Math.max(1, base * (0.7 + ThreadLocalRandom.current().nextDouble() * 0.6));
    }

    private void jitterPings() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<ClientboundPlayerInfoUpdatePacket.Entry> changed = new ArrayList<>();
        for (Bot b : listed.values()) {
            if (r.nextInt(5) != 0) continue; // a fifth of bots change ping each update, like real connections
            int target = rollPing(b);
            b.ping = (b.ping * 2 + target) / 3 + (r.nextInt(20) == 0 ? r.nextInt(150) : 0); // occasional lag spike
            changed.add(entry(b));
        }
        if (!changed.isEmpty()) broadcast(new ClientboundPlayerInfoUpdatePacket(LATENCY, changed));
    }

    /** The signed textures property for the bot's head icon, or null for the client's default skin. */
    private Property texture(Bot b) {
        String spec = b.skinSpec == null ? "" : b.skinSpec.trim();
        if (spec.regionMatches(true, 0, "texture:", 0, 8)) return parseTexture(spec.substring(8));
        Property configured = headTextures.get(spec.toLowerCase(Locale.ROOT));
        if (configured != null) return configured;
        String pack = SkinHeads.packId(spec);
        if (pack != null) {
            SkinHeads.Texture t = plugin.skinHeads().texture(pack);
            return t == null ? null : new Property("textures", t.value(), t.signature());
        }
        String user = userSkin(b);
        return user == null ? null : textures.get(user);
    }

    /** {@code <base64 value>[:<signature>]} as used by {@code texture:} skins and MineSkin. */
    static Property parseTexture(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String v = raw.trim();
        int i = v.indexOf(':');
        String value = i < 0 ? v : v.substring(0, i);
        String sig = i < 0 ? null : v.substring(i + 1);
        if (value.isBlank()) return null;
        return new Property("textures", value, sig == null || sig.isBlank() ? null : sig);
    }

    private static String userSkin(Bot b) {
        String spec = b.skinSpec;
        if (spec == null) return null;
        String s = spec.trim();
        if (s.regionMatches(true, 0, "user:", 0, 5)) return s.substring(5).toLowerCase(Locale.ROOT);
        if (s.indexOf(':') < 0 && !s.isBlank()) return s.toLowerCase(Locale.ROOT);
        return null;
    }

    /** Looks up a real (signed) skin once, then re-sends the entries that use it so their head icon updates. */
    private void fetch(String user) {
        if (!fetching.add(user)) return;
        PlayerProfile profile = Bukkit.createProfile(NameGenerator.profileSafe(user));
        profile.update().whenComplete((done, err) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                fetching.remove(user);
                if (err != null || done == null) return;
                for (ProfileProperty pp : done.getProperties()) {
                    if (!pp.getName().equals("textures")) continue;
                    textures.put(user, new Property("textures", pp.getValue(), pp.getSignature()));
                    resend(b -> user.equals(userSkin(b)));
                    return;
                }
            });
        });
    }

    @Override
    public void refreshPack(String id) {
        resend(b -> id.equals(SkinHeads.packId(b.skinSpec)));
    }

    /** Removes and re-adds matching entries so clients pick up their new head texture. */
    private void resend(java.util.function.Predicate<Bot> which) {
        List<UUID> ids = new ArrayList<>();
        List<ClientboundPlayerInfoUpdatePacket.Entry> entries = new ArrayList<>();
        for (Bot b : listed.values()) {
            if (!which.test(b)) continue;
            ids.add(b.tabId);
            entries.add(entry(b));
        }
        if (ids.isEmpty()) return;
        broadcast(new ClientboundPlayerInfoRemovePacket(ids));
        broadcast(new ClientboundPlayerInfoUpdatePacket(ADD, entries));
    }

    // ------------------------------------------------------------------ sending

    private static void send(Player p, Packet<?> packet) {
        ((CraftPlayer) p).getHandle().connection.send(packet);
    }

    private static void broadcast(Packet<?> packet) {
        for (Player p : Bukkit.getOnlinePlayers()) send(p, packet);
    }

    @Override
    public String name() {
        return "tablist";
    }
}

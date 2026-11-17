package dev.nullportals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

/** The command GUI, the faction horns and faction-vs-faction battles. */
final class CommandCenter implements Listener {
    private static final String P = NullPortalsPlugin.PREFIX;

    private final NullPortalsPlugin plugin;
    private final UnitManager mgr;
    private final NamespacedKey hornKey;
    private final Map<UUID, String> selected = new HashMap<>();
    private final Map<UUID, String> battleA = new HashMap<>();
    private final Map<UUID, String> battleB = new HashMap<>();
    private final Map<UUID, Integer> battleSize = new HashMap<>();
    private final Map<UUID, Long> hornCooldown = new HashMap<>();
    private final List<Battle> battles = new ArrayList<>();

    CommandCenter(NullPortalsPlugin plugin, UnitManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
        this.hornKey = new NamespacedKey(plugin, "faction_horn");
    }

    static final class Holder implements InventoryHolder {
        final String page;
        Inventory inv;

        Holder(String page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    // ================================================================ helpers

    private static ItemStack icon(String mat, String name, String... lore) {
        Material m = Material.matchMaterial(mat);
        ItemStack is = new ItemStack(m == null ? Material.STONE : m);
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            List<String> l = new ArrayList<>();
            for (String s : lore) l.add("§7" + s);
            meta.setLore(l);
            is.setItemMeta(meta);
        }
        return is;
    }

    private static ItemStack glint(ItemStack is, boolean on) {
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.setEnchantmentGlintOverride(on ? Boolean.TRUE : null);
            is.setItemMeta(meta);
        }
        return is;
    }

    private static String onOff(boolean b) {
        return b ? "§aON" : "§cOFF";
    }

    private Faction selectedFaction(Player p) {
        String id = selected.get(p.getUniqueId());
        Faction f = id == null ? null : mgr.factions.get(id);
        if (f == null && !mgr.factions.isEmpty()) {
            f = mgr.factions.values().iterator().next();
            selected.put(p.getUniqueId(), f.id);
        }
        return f;
    }

    private ItemStack factionIcon(Faction f, boolean sel, String... extra) {
        ItemStack is = icon("LEATHER_CHESTPLATE", f.display, extra);
        ItemMeta meta = is.getItemMeta();
        if (meta instanceof LeatherArmorMeta lam) {
            lam.setColor(f.color);
            is.setItemMeta(lam);
        }
        return glint(is, sel);
    }

    private Location inFront(Player p, double dist) {
        Location l = p.getLocation();
        double yaw = Math.toRadians(l.getYaw());
        Location at = UnitManager.groundAt(l.getWorld(), l.getX() - Math.sin(yaw) * dist, l.getY(), l.getZ() + Math.cos(yaw) * dist);
        at.setYaw(l.getYaw());
        return at;
    }

    private void click(Player p) {
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.2f);
    }

    // ================================================================ pages

    void openMain(Player p) {
        Holder h = new Holder("main");
        h.inv = Bukkit.createInventory(h, 54, "§5§lNull Command");
        renderMain(p, h.inv);
        p.openInventory(h.inv);
    }

    private void renderMain(Player p, Inventory inv) {
        for (int i = 0; i < 54; i++) inv.setItem(i, null);
        Faction f = selectedFaction(p);
        int i = 0;
        for (Faction x : mgr.factions.values()) {
            if (i >= 9) break;
            inv.setItem(i++, factionIcon(x, x == f, "Units: §f" + mgr.count(x), "Mode: §f" + x.mode, "Click to select"));
        }
        if (f == null) return;
        int n = mgr.count(f);
        String target = "none";
        if (f.focus != null) {
            Entity e = Bukkit.getEntity(f.focus);
            if (e != null) target = e.getName();
        }
        inv.setItem(9, icon("BOOK", f.display + " §7Overview", "Units: §f" + n, "Mode: §f" + f.mode,
                "Focus target: §f" + target, "Formation: §f" + f.formation.label + " §7(spacing " + f.spacing + ")",
                "Following: §f" + (f.follow ? "commander" : "holding position"),
                "You are " + (f.members.contains(p.getUniqueId()) ? "§aa member" : "§cnot a member")));
        inv.setItem(10, icon("ZOMBIE_SPAWN_EGG", "§aSpawn Units...", "Pick exactly which units to spawn"));
        UnitTemplate lt = mgr.templates.get(f.leaderTemplate);
        inv.setItem(11, icon("NETHER_STAR", "§6Spawn Leader", lt == null ? "No leader set" : NullPortalsPlugin.color(lt.display)));
        inv.setItem(12, icon("IRON_SWORD", "§aSpawn Army x5", "Random mix from this faction's army"));
        inv.setItem(13, icon("DIAMOND_SWORD", "§aSpawn Army x10"));
        inv.setItem(14, icon("GOAT_HORN", "§eGet " + f.display + " §eHorn", "Right-click: attack what you look at", "  or march to the block you look at",
                "Left-click: STAND DOWN", "Sneak + right-click: this menu"));
        boolean member = f.members.contains(p.getUniqueId());
        inv.setItem(15, icon("TOTEM_OF_UNDYING", member ? "§cLeave Faction" : "§aJoin Faction", "Members are never attacked", "by this faction's units"));
        inv.setItem(16, icon("ENDER_PEARL", "§bTeleport Units To Me"));
        inv.setItem(17, icon("BARRIER", "§cDisband All Units", "Removes every unit of this faction"));

        inv.setItem(18, icon("WHITE_BANNER", "§f§lSTAND DOWN", "Stop fighting everyone, clear target,", "regroup in formation on you"));
        inv.setItem(19, glint(icon("LIME_DYE", "§aPassive", "Never start fights"), f.mode == Faction.Mode.PASSIVE));
        inv.setItem(20, glint(icon("SHIELD", "§eDefend", "Fight enemies that come near the formation"), f.mode == Faction.Mode.DEFEND));
        inv.setItem(21, glint(icon("NETHERITE_SWORD", "§cAggressive", "Hunt down every enemy in range"), f.mode == Faction.Mode.AGGRESSIVE));
        inv.setItem(22, icon("MILK_BUCKET", "§fClear Focus Target"));
        inv.setItem(23, glint(icon("LEAD", "§aFollow Me", "Formation forms up behind you"), f.follow));
        inv.setItem(24, glint(icon("ANVIL", "§eHold Here", "Formation holds at your position"), !f.follow));
        inv.setItem(25, icon("CROSSBOW", "§cAttack Outsider Players: " + onOff(f.attackOutsiders), "Attack players who aren't members"));
        inv.setItem(26, icon("RED_BANNER", "§cFight Other Factions: " + onOff(f.fightFactions)));

        int s = 27;
        for (Formation fm : Formation.values()) {
            inv.setItem(s++, glint(icon(fm.icon, "§bFormation: " + fm.label), f.formation == fm));
        }
        inv.setItem(34, icon("REDSTONE", "§cSpacing -", "Now: " + f.spacing));
        inv.setItem(35, icon("GLOWSTONE_DUST", "§aSpacing +", "Now: " + f.spacing));

        inv.setItem(36, icon("BRICKS", "§6Building: " + onOff(f.build), "Builders pillar up, bridge gaps,", "tower over walls"));
        inv.setItem(37, icon("IRON_PICKAXE", "§6Breaking: " + onOff(f.breakBlocks), "Breakers dig through walls,", "floors and ceilings"));
        inv.setItem(38, icon("STONE_BRICK_WALL", "§6Fortify: " + onOff(f.fortify), "Builders raise a wall in front", "of the formation"));
        inv.setItem(39, icon("BRUSH", "§6Clear Builds", "Removes blocks this faction placed (" + f.placed.size() + ")"));
        inv.setItem(40, icon("NAME_TAG", "§fShow Names: " + onOff(f.showNames)));
        inv.setItem(41, icon("SPECTRAL_ARROW", "§fGlowing: " + onOff(f.glow)));

        inv.setItem(45, icon("CRYING_OBSIDIAN", "§5Start Null Portal Event Here", "Mass portals + nulls (uses config)"));
        inv.setItem(46, icon("BARRIER", "§cStop All Null Events"));
        inv.setItem(49, icon("TNT", "§c§lBattles...", "Faction vs faction fights", "e.g. Null vs LAW"));
        inv.setItem(53, icon("OAK_DOOR", "§7Close"));
    }

    void openSpawn(Player p) {
        Holder h = new Holder("spawn");
        h.inv = Bukkit.createInventory(h, 54, "§5§lSpawn Units");
        renderSpawn(p, h.inv);
        p.openInventory(h.inv);
    }

    private List<UnitTemplate> spawnList(Faction f) {
        List<UnitTemplate> l = new ArrayList<>();
        UnitTemplate lt = mgr.templates.get(f.leaderTemplate);
        if (lt != null) l.add(lt);
        for (String id : f.army) {
            UnitTemplate t = mgr.templates.get(id);
            if (t != null && !l.contains(t)) l.add(t);
        }
        for (UnitTemplate t : mgr.templates.values()) if (!l.contains(t)) l.add(t);
        return l;
    }

    private void renderSpawn(Player p, Inventory inv) {
        for (int i = 0; i < 54; i++) inv.setItem(i, null);
        Faction f = selectedFaction(p);
        if (f == null) return;
        List<UnitTemplate> l = spawnList(f);
        for (int i = 0; i < l.size() && i < 45; i++) {
            UnitTemplate t = l.get(i);
            boolean own = t.id.equals(f.leaderTemplate) || f.army.contains(t.id);
            String egg = t.leader ? "NETHER_STAR" : t.entity + "_SPAWN_EGG";
            List<String> lore = new ArrayList<>();
            lore.add("Health §f" + (int) t.health + "§7  Damage §f" + t.damage + "§7  " + t.entity.toLowerCase());
            lore.add("Traits: §f" + String.join(", ", t.traits));
            lore.add(own ? "§aPart of " + f.display + "§a's army" : "§8(from another faction - joins " + f.display + "§8)");
            lore.add("Left: 1  Right: 5  Shift: 10");
            ItemStack is = icon(egg, "§" + f.colorCode + NullPortalsPlugin.color(t.display), lore.toArray(new String[0]));
            inv.setItem(i, glint(is, own));
        }
        inv.setItem(49, icon("ARROW", "§7Back"));
    }

    void openBattle(Player p) {
        Holder h = new Holder("battle");
        h.inv = Bukkit.createInventory(h, 54, "§c§lBattles");
        renderBattle(p, h.inv);
        p.openInventory(h.inv);
    }

    private void renderBattle(Player p, Inventory inv) {
        for (int i = 0; i < 54; i++) inv.setItem(i, null);
        UUID id = p.getUniqueId();
        String a = battleA.get(id), b = battleB.get(id);
        int size = battleSize.getOrDefault(id, 10);
        int i = 0;
        for (Faction x : mgr.factions.values()) {
            if (i >= 18) break;
            String side = x.id.equals(a) ? "§c§lSIDE A" : x.id.equals(b) ? "§9§lSIDE B" : "";
            inv.setItem(i++, factionIcon(x, x.id.equals(a) || x.id.equals(b), "Left-click: side A", "Right-click: side B", side));
        }
        Faction fa = a == null ? null : mgr.factions.get(a), fb = b == null ? null : mgr.factions.get(b);
        inv.setItem(20, icon("REDSTONE", "§cArmy size -", "Per side: " + size));
        inv.setItem(22, icon("PAPER", "§fArmy size per side: §e" + size, "Leaders are added on top"));
        inv.setItem(24, icon("GLOWSTONE_DUST", "§aArmy size +", "Per side: " + size));
        inv.setItem(31, icon("TNT", "§c§lSTART BATTLE",
                (fa == null ? "?" : fa.display) + " §7vs " + (fb == null ? "?" : fb.display),
                "Both armies spawn in front of you", "and fight until one side is wiped out"));

        List<String> presets = plugin.getConfig().getStringList("battles.presets");
        int s = 36;
        for (String pr : presets) {
            if (s > 44) break;
            String[] parts = pr.split(":");
            if (parts.length < 2) continue;
            Faction x = mgr.factions.get(parts[0].trim()), y = mgr.factions.get(parts[1].trim());
            if (x == null || y == null) continue;
            inv.setItem(s++, icon("GOLDEN_SWORD", x.display + " §7vs " + y.display, "Preset - click to start now"));
        }
        inv.setItem(46, icon("BARRIER", "§cEnd All Battles", "Removes both armies of every battle"));
        inv.setItem(49, icon("ARROW", "§7Back"));
        inv.setItem(52, icon("BOOK", "§fRunning battles: §e" + battles.size()));
    }

    // ================================================================ clicks

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= 54) return;
        if (!p.hasPermission("nullportal.command")) return;
        ClickType ct = e.getClick();
        if (h.page.equals("main")) clickMain(p, h, slot);
        else if (h.page.equals("spawn")) clickSpawn(p, h, slot, ct);
        else if (h.page.equals("battle")) clickBattle(p, h, slot, ct);
    }

    private void clickMain(Player p, Holder h, int slot) {
        Faction f = selectedFaction(p);
        if (slot < 9) {
            int i = 0;
            for (Faction x : mgr.factions.values()) {
                if (i++ == slot) {
                    selected.put(p.getUniqueId(), x.id);
                    click(p);
                }
            }
            renderMain(p, h.inv);
            return;
        }
        if (f == null) return;
        boolean rerender = true;
        switch (slot) {
            case 10 -> {
                openSpawn(p);
                rerender = false;
            }
            case 11 -> {
                UnitTemplate lt = mgr.templates.get(f.leaderTemplate);
                if (lt == null) p.sendMessage(P + "This faction has no leader set in config.yml.");
                else spawnFor(p, f, lt, 1);
            }
            case 12 -> spawnArmy(p, f, 5);
            case 13 -> spawnArmy(p, f, 10);
            case 14 -> {
                p.getInventory().addItem(makeHorn(f));
                p.sendMessage(P + "Here's the " + f.display + " §7horn.");
                f.members.add(p.getUniqueId());
                f.commander = p.getUniqueId();
            }
            case 15 -> {
                if (!f.members.remove(p.getUniqueId())) {
                    f.members.add(p.getUniqueId());
                    f.grudges.remove(p.getUniqueId());
                }
            }
            case 16 -> mgr.teleportTo(f, p.getLocation());
            case 17 -> mgr.disband(f);
            case 18 -> {
                mgr.standDown(f, p);
                p.sendMessage(P + f.display + " §f§lSTAND DOWN §7- regrouping on you.");
            }
            case 19 -> f.mode = Faction.Mode.PASSIVE;
            case 20 -> f.mode = Faction.Mode.DEFEND;
            case 21 -> f.mode = Faction.Mode.AGGRESSIVE;
            case 22 -> f.focus = null;
            case 23 -> {
                f.follow = true;
                f.commander = p.getUniqueId();
            }
            case 24 -> mgr.hold(f, p.getLocation());
            case 25 -> f.attackOutsiders = !f.attackOutsiders;
            case 26 -> f.fightFactions = !f.fightFactions;
            case 34 -> f.spacing = Math.max(1.0, f.spacing - 0.5);
            case 35 -> f.spacing = Math.min(8.0, f.spacing + 0.5);
            case 36 -> f.build = !f.build;
            case 37 -> f.breakBlocks = !f.breakBlocks;
            case 38 -> f.fortify = !f.fortify;
            case 39 -> mgr.clearBuilds(f);
            case 40 -> {
                f.showNames = !f.showNames;
                mgr.applyNames(f);
            }
            case 41 -> {
                f.glow = !f.glow;
                mgr.applyGlow(f);
            }
            case 45 -> p.sendMessage(P + plugin.startEvent(p.getLocation(), p.getName()));
            case 46 -> p.sendMessage(P + "Stopped " + plugin.stopEvents() + " event(s).");
            case 49 -> {
                openBattle(p);
                rerender = false;
            }
            case 53 -> {
                p.closeInventory();
                rerender = false;
            }
            default -> {
                if (slot >= 27 && slot < 27 + Formation.values().length) f.formation = Formation.values()[slot - 27];
                else return;
            }
        }
        click(p);
        if (rerender) renderMain(p, h.inv);
    }

    private void clickSpawn(Player p, Holder h, int slot, ClickType ct) {
        Faction f = selectedFaction(p);
        if (f == null) return;
        if (slot == 49) {
            openMain(p);
            return;
        }
        List<UnitTemplate> l = spawnList(f);
        if (slot >= l.size() || slot >= 45) return;
        int n = ct.isShiftClick() ? 10 : ct.isRightClick() ? 5 : 1;
        spawnFor(p, f, l.get(slot), n);
        click(p);
    }

    private void clickBattle(Player p, Holder h, int slot, ClickType ct) {
        UUID id = p.getUniqueId();
        if (slot < 18) {
            int i = 0;
            for (Faction x : mgr.factions.values()) {
                if (i++ == slot) {
                    if (ct.isRightClick()) battleB.put(id, x.id);
                    else battleA.put(id, x.id);
                }
            }
        } else if (slot == 20) {
            battleSize.put(id, Math.max(1, battleSize.getOrDefault(id, 10) - (ct.isShiftClick() ? 10 : 2)));
        } else if (slot == 24) {
            battleSize.put(id, Math.min(60, battleSize.getOrDefault(id, 10) + (ct.isShiftClick() ? 10 : 2)));
        } else if (slot == 31) {
            Faction a = mgr.factions.get(battleA.getOrDefault(id, "")), b = mgr.factions.get(battleB.getOrDefault(id, ""));
            if (a == null || b == null || a == b) {
                p.sendMessage(P + "Pick two different factions (left-click = side A, right-click = side B).");
            } else {
                startBattle(p, a, b, battleSize.getOrDefault(id, 10));
                p.closeInventory();
                return;
            }
        } else if (slot >= 36 && slot <= 44) {
            int s = 36;
            for (String pr : plugin.getConfig().getStringList("battles.presets")) {
                String[] parts = pr.split(":");
                if (parts.length < 2) continue;
                Faction x = mgr.factions.get(parts[0].trim()), y = mgr.factions.get(parts[1].trim());
                if (x == null || y == null) continue;
                if (s++ == slot) {
                    startBattle(p, x, y, battleSize.getOrDefault(id, 10));
                    p.closeInventory();
                    return;
                }
            }
        } else if (slot == 46) {
            for (Battle b : new ArrayList<>(battles)) b.end(null);
        } else if (slot == 49) {
            openMain(p);
            return;
        } else {
            return;
        }
        click(p);
        renderBattle(p, h.inv);
    }

    // ================================================================ spawning

    private void spawnFor(Player p, Faction f, UnitTemplate t, int n) {
        int ok = 0;
        for (int i = 0; i < n; i++) {
            Location at = inFront(p, 3 + Math.random() * 2);
            at.add(Math.random() * 3 - 1.5, t.flying() ? 2 : 0, Math.random() * 3 - 1.5);
            if (mgr.spawn(f, t, at) != null) ok++;
        }
        if (f.commander == null) f.commander = p.getUniqueId();
        f.members.add(p.getUniqueId());
        if (ok < n) p.sendMessage(P + "Unit cap reached (units.max-per-faction).");
    }

    private void spawnArmy(Player p, Faction f, int n) {
        java.util.Random r = new java.util.Random();
        for (int i = 0; i < n; i++) {
            String id = f.pickArmy(r);
            UnitTemplate t = id == null ? null : mgr.templates.get(id);
            if (t != null) spawnFor(p, f, t, 1);
        }
    }

    // ================================================================ horn

    ItemStack makeHorn(Faction f) {
        ItemStack is = icon("GOAT_HORN", "§" + f.colorCode + "§l" + strip(f.display) + " Horn",
                "§eRight-click§7: attack what you look at", "  (or march to the block you look at,", "   or rally on you if looking at sky)",
                "§eLeft-click§7: STAND DOWN", "§eSneak + right-click§7: command menu");
        ItemMeta meta = is.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(hornKey, PersistentDataType.STRING, f.id);
            meta.setEnchantmentGlintOverride(Boolean.TRUE);
            is.setItemMeta(meta);
        }
        return is;
    }

    private String hornFaction(ItemStack it) {
        if (it == null || it.getType() != Material.GOAT_HORN) return null;
        ItemMeta meta = it.getItemMeta();
        if (meta == null) return null;
        return meta.getPersistentDataContainer().get(hornKey, PersistentDataType.STRING);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        String fid = hornFaction(e.getItem());
        if (fid == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.hasPermission("nullportal.command")) return;
        long t = System.currentTimeMillis();
        Long last = hornCooldown.get(p.getUniqueId());
        if (last != null && t - last < 300) return;
        hornCooldown.put(p.getUniqueId(), t);

        Faction f = mgr.factions.get(fid);
        if (f == null) {
            p.sendMessage(P + "That faction no longer exists in config.yml.");
            return;
        }
        f.members.add(p.getUniqueId());
        f.commander = p.getUniqueId();
        Action a = e.getAction();
        if (a == Action.LEFT_CLICK_AIR || a == Action.LEFT_CLICK_BLOCK) {
            mgr.standDown(f, p);
            p.getWorld().playSound(p.getLocation(), Sound.ITEM_GOAT_HORN_SOUND_7, 3f, 1f);
            p.sendMessage(P + f.display + " §f§lSTAND DOWN §7- all units stop fighting and form up on you.");
            return;
        }
        if (a != Action.RIGHT_CLICK_AIR && a != Action.RIGHT_CLICK_BLOCK) return;
        if (p.isSneaking()) {
            selected.put(p.getUniqueId(), f.id);
            openMain(p);
            return;
        }
        Entity target = p.getTargetEntity(96);
        if (target instanceof LivingEntity le && target != p) {
            Unit tu = mgr.unit(le);
            if (tu != null && tu.f == f) {
                p.sendMessage(P + "That's one of your own units.");
                return;
            }
            mgr.focus(f, le);
            p.getWorld().playSound(p.getLocation(), Sound.EVENT_RAID_HORN, 4f, 1f);
            p.sendMessage(P + f.display + " §c§lATTACK §f" + le.getName());
            if (!le.isGlowing()) {
                le.setGlowing(true);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (le.isValid() && !mgr.isUnit(le)) le.setGlowing(false);
                }, 60L);
            }
            return;
        }
        Block b = p.getTargetBlockExact(160);
        if (b != null) {
            Location to = b.getLocation().add(0.5, 1, 0.5);
            mgr.march(f, to, p.getLocation().getYaw());
            p.getWorld().playSound(p.getLocation(), Sound.ITEM_GOAT_HORN_SOUND_0, 3f, 1f);
            p.sendMessage(P + f.display + " §emarching to §f" + to.getBlockX() + " " + to.getBlockY() + " " + to.getBlockZ());
        } else {
            f.follow = true;
            f.focus = null;
            p.getWorld().playSound(p.getLocation(), Sound.ITEM_GOAT_HORN_SOUND_0, 3f, 1.3f);
            p.sendMessage(P + f.display + " §arally on you.");
        }
    }

    // ================================================================ battles

    final class Battle implements Runnable {
        final Faction a, b;
        final Location centre;
        BukkitTask task;
        final boolean[] savedFight = new boolean[2];

        Battle(Faction a, Faction b, Location centre) {
            this.a = a;
            this.b = b;
            this.centre = centre;
        }

        @Override
        public void run() {
            int na = mgr.count(a), nb = mgr.count(b);
            if (na == 0 || nb == 0) end(na == 0 && nb == 0 ? null : (na == 0 ? b : a));
        }

        void end(Faction winner) {
            if (task != null) task.cancel();
            battles.remove(this);
            String msg = winner == null ? P + "Battle " + a.display + " §7vs " + b.display + " §7ended."
                    : P + "§6§lVICTORY: " + winner.display + " §7wins the battle against "
                            + (winner == a ? b.display : a.display) + "§7!";
            for (Player p : centre.getWorld().getPlayers()) {
                if (p.getLocation().distanceSquared(centre) < 160 * 160) {
                    p.sendMessage(msg);
                    p.playSound(p.getLocation(), winner == null ? Sound.UI_BUTTON_CLICK : Sound.EVENT_RAID_HORN, 1f, 1.2f);
                }
            }
            if (winner == null) {
                mgr.disband(a);
                mgr.disband(b);
            } else {
                winner.mode = Faction.Mode.DEFEND;
            }
        }
    }

    private void startBattle(Player p, Faction a, Faction b, int size) {
        Location c = inFront(p, 18);
        World w = c.getWorld();
        double yaw = Math.toRadians(p.getLocation().getYaw());
        double sx = -Math.cos(yaw), sz = -Math.sin(yaw); // sideways axis: armies line up left vs right
        double gap = plugin.getConfig().getDouble("battles.gap", 22) / 2.0;
        Location ca = UnitManager.groundAt(w, c.getX() - sx * gap, c.getY(), c.getZ() - sz * gap);
        Location cb = UnitManager.groundAt(w, c.getX() + sx * gap, c.getY(), c.getZ() + sz * gap);
        // A faces B and B faces A
        ca.setDirection(cb.toVector().subtract(ca.toVector()));
        cb.setDirection(ca.toVector().subtract(cb.toVector()));
        deploy(a, ca, size);
        deploy(b, cb, size);
        Battle bt = new Battle(a, b, c);
        bt.task = Bukkit.getScheduler().runTaskTimer(plugin, bt, 100L, 20L);
        battles.add(bt);
        String msg = P + "§c§lBATTLE! " + a.display + " §7vs " + b.display + " §7(" + size + " each)";
        for (Player x : w.getPlayers()) {
            if (x.getLocation().distanceSquared(c) < 160 * 160) {
                x.sendMessage(msg);
                x.playSound(x.getLocation(), Sound.EVENT_RAID_HORN, 1f, 0.8f);
            }
        }
    }

    private void deploy(Faction f, Location at, int size) {
        f.follow = false;
        f.anchor = at.clone();
        f.anchorYaw = at.getYaw();
        f.focus = null;
        f.mode = Faction.Mode.AGGRESSIVE;
        f.fightFactions = true;
        java.util.Random r = new java.util.Random();
        UnitTemplate lt = mgr.templates.get(f.leaderTemplate);
        if (lt != null) mgr.spawn(f, lt, at.clone());
        double yaw = Math.toRadians(at.getYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw), rx = -Math.cos(yaw), rz = -Math.sin(yaw);
        for (int i = 0; i < size; i++) {
            String id = f.pickArmy(r);
            UnitTemplate t = id == null ? null : mgr.templates.get(id);
            if (t == null) continue;
            double[] o = f.formation.offset(i, size, f.spacing);
            Location l = UnitManager.groundAt(at.getWorld(), at.getX() + rx * o[0] - fx * (o[1] + 2),
                    at.getY(), at.getZ() + rz * o[0] - fz * (o[1] + 2));
            if (t.flying()) l.add(0, 3, 0);
            l.setYaw(at.getYaw());
            mgr.spawn(f, t, l);
        }
    }

    void shutdown() {
        for (Battle b : new ArrayList<>(battles)) if (b.task != null) b.task.cancel();
        battles.clear();
    }

    static String strip(String s) {
        return s.replaceAll("\u00a7.", "");
    }
}

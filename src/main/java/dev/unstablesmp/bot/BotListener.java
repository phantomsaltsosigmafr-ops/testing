package dev.unstablesmp.bot;

import dev.unstablesmp.UnstableSMP;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/** Bot reactions: getting hit (retaliate, jump-reset), totem pops, deaths (vanilla-style lines), pearls, taunts. */
public final class BotListener implements Listener {
    private final UnstableSMP plugin;
    private final BotManager mgr;

    public BotListener(UnstableSMP plugin, BotManager mgr) {
        this.plugin = plugin;
        this.mgr = mgr;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHurt(EntityDamageByEntityEvent e) {
        Entity cause = e.getDamageSource().getCausingEntity();
        Bot victim = mgr.bot(e.getEntity());
        int now = Bukkit.getCurrentTick();
        if (victim != null) {
            victim.lastHurt = now;
            victim.comboHits = 0;
            if (ThreadLocalRandom.current().nextDouble() < victim.s.jumpReset) victim.jumpResetQueued = true;
            if (cause instanceof LivingEntity attacker && mgr.isEnemy(victim, attacker)
                    && (victim.target == null || ThreadLocalRandom.current().nextDouble() < 0.35)
                    && victim.group.behaviour != BotGroup.Behaviour.ORGANISED) {
                victim.target = attacker;
                victim.targetSince = now;
            }
            if (victim.blockUntil > now && e.getFinalDamage() <= 0) {
                victim.body.getWorld().playSound(victim.body.getLocation(), org.bukkit.Sound.ITEM_SHIELD_BLOCK, 1f, 1f);
            }
        }
        if (e.getEntity() instanceof Player p && mgr.isBot(cause)) plugin.combatTags().tag(p, null);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent e) {
        if (e.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        Bot b = mgr.bot(e.getEntity());
        if (b != null && (b.airborne || b.mode == PvpMode.ELYTRA_MACE)) e.setDamage(e.getDamage() * 0.5);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTotem(EntityResurrectEvent e) {
        Bot b = mgr.bot(e.getEntity());
        if (b != null) b.retotemAt = Bukkit.getCurrentTick() + b.s.retotem;
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        Bot b = mgr.bot(e.getEntity());
        if (b == null) return;
        if (!mgr.dropItems) e.getDrops().clear();
        e.setDroppedExp(0);
        Player killerPlayer = e.getEntity().getKiller();
        Entity cause = e.getDamageSource().getCausingEntity();
        Bot killerBot = mgr.bot(cause);
        Component killer = killerPlayer != null ? killerPlayer.name() : killerBot != null ? killerBot.name : null;
        if (mgr.deathMessages) plugin.fakeChat().broadcast(plugin.fakeChat().deathLine(b.name, killer));
        if (killerBot != null) {
            killerBot.kills++;
            taunt(killerBot);
        }
        mgr.remove(b, false);
        plugin.games().onBotDeath(b, killerPlayer);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent e) {
        Entity cause = e.getDamageSource().getCausingEntity();
        Bot b = mgr.bot(cause);
        if (b != null) {
            b.kills++;
            if (b.target == e.getEntity()) b.target = null;
            taunt(b);
        }
    }

    private void taunt(Bot b) {
        if (b.group.killLines.isEmpty()) return;
        if (ThreadLocalRandom.current().nextDouble() >= b.group.chatChance * mgr.chatChance) return;
        String line = b.group.killLines.get(ThreadLocalRandom.current().nextInt(b.group.killLines.size()));
        Bukkit.getScheduler().runTaskLater(plugin, () -> plugin.botSpawner().say(b, line), 10 + ThreadLocalRandom.current().nextInt(30));
    }

    @EventHandler
    public void onPearl(ProjectileHitEvent e) {
        Bot b = mgr.pearlOwner(e.getEntity());
        if (b == null) return;
        Location hit = e.getEntity().getLocation();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!b.valid()) return;
            if (b.body.getLocation().distanceSquared(hit) > 9) {
                hit.setYaw(b.lookYaw);
                b.body.teleport(hit);
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent e) {
        if (mgr.isBot(e.getRightClicked()) && e.getPlayer().getInventory().getItemInMainHand().getType() != Material.LEAD) e.setCancelled(true);
    }
}

package dev.unstablesmp.bot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.unstablesmp.bot.ai.BotCombat;
import dev.unstablesmp.bot.skin.NameGenerator;
import org.junit.jupiter.api.Test;

class BotLogicTest {

    @Test
    void tiersGetStrictlyWorseFromHt1ToLt6() {
        SkillTier.load(null);
        SkillTier[] t = SkillTier.values();
        assertEquals(12, t.length);
        assertEquals(SkillTier.HT1, t[0]);
        assertEquals(SkillTier.LT6, t[11]);
        for (int i = 1; i < t.length; i++) {
            SkillTier.Stats better = t[i - 1].stats(), worse = t[i].stats();
            assertTrue(worse.reaction >= better.reaction, t[i] + " reaction");
            assertTrue(worse.aimError > better.aimError, t[i] + " aim");
            assertTrue(worse.critRate < better.critRate, t[i] + " crit");
            assertTrue(worse.miss > better.miss, t[i] + " miss");
            assertTrue(worse.reach < better.reach, t[i] + " reach");
        }
        assertEquals(1, SkillTier.HT1.stats().reaction);
        assertTrue(SkillTier.LT6.stats().critRate < 0.01);
    }

    @Test
    void tierRangesPickInsideTheRange() {
        for (int i = 0; i < 200; i++) {
            SkillTier t = SkillTier.pick("HT2-LT3");
            assertTrue(t.ordinal() >= SkillTier.HT2.ordinal() && t.ordinal() <= SkillTier.LT3.ordinal(), t.name());
        }
        assertEquals(SkillTier.HT4, SkillTier.pick("ht4"));
        assertNull(SkillTier.parse("HT9"));
    }

    @Test
    void modesParseCommonNames() {
        assertEquals(PvpMode.ELYTRA_MACE, PvpMode.parse("ely_mace"));
        assertEquals(PvpMode.ELYTRA_MACE, PvpMode.parse("elytra"));
        assertEquals(PvpMode.NETHPOT, PvpMode.parse("nethpot"));
        assertEquals(PvpMode.AXE, PvpMode.parse("ace"));
        assertEquals(PvpMode.SPEAR, PvpMode.parse("spear"));
        assertNull(PvpMode.parse("banana"));
        assertTrue(PvpMode.parse("random") != null);
    }

    @Test
    void namesAreProfileSafe() {
        for (int i = 0; i < 100; i++) {
            String s = NameGenerator.scrambled(6, 12);
            assertTrue(s.length() >= 6 && s.length() <= 12 && s.matches("[A-Za-z0-9_]+"), s);
            String g = NameGenerator.gamer();
            assertTrue(g.length() <= 16 && g.matches("[!-~]+"), g);
        }
        assertTrue(NameGenerator.underscore().matches("____[A-Z]____"));
        assertEquals("KingLettuceK", NameGenerator.profileSafe("King LettuceK"));
    }

    @Test
    void maceDamageFollowsVanillaCurve() {
        assertEquals(0, BotCombat.maceBonus(0, 0), 1e-9);
        assertEquals(12, BotCombat.maceBonus(3, 0), 1e-9);
        assertEquals(22, BotCombat.maceBonus(8, 0), 1e-9);
        assertEquals(24, BotCombat.maceBonus(10, 0), 1e-9);
        assertTrue(BotCombat.maceBonus(10, 5) > BotCombat.maceBonus(10, 0), "density adds damage");
    }
}

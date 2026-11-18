package dev.unstablesmp.bot;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/** PvP styles a bot can fight with. Each has its own tactic and default loadout. */
public enum PvpMode {
    SWORD, AXE, MACE, ELYTRA_MACE, UHC, NETHPOT, SPEAR;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static PvpMode random() {
        PvpMode[] v = values();
        return v[ThreadLocalRandom.current().nextInt(v.length)];
    }

    /** Parses a mode; "random"/null gives a random mode; accepts "ely_mace", "elytra", "pot", "nethop". */
    public static PvpMode parse(String s) {
        if (s == null || s.isBlank() || s.equalsIgnoreCase("random")) return random();
        String n = s.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        switch (n) {
            case "ELY_MACE", "ELYTRA", "ELYMACE", "ELYTRAMACE" -> {
                return ELYTRA_MACE;
            }
            case "POT", "NETHOP", "NETH_POT", "NETHERITE_POT" -> {
                return NETHPOT;
            }
            case "ACE" -> {
                return AXE;
            }
            default -> {
                try {
                    return valueOf(n);
                } catch (IllegalArgumentException ex) {
                    return null;
                }
            }
        }
    }
}

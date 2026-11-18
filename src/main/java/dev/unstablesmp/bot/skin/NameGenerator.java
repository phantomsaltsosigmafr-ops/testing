package dev.unstablesmp.bot.skin;

import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bot names. NULL members have scrambled, unreadable names (obfuscated text) or the underscore form used for their
 * commanders (____D____). Other bots get believable gamer names.
 */
public final class NameGenerator {
    public enum Style { SCRAMBLED, UNDERSCORE, LIST, GAMER }

    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_";
    private static final String[] PRE = {"Shadow", "Dark", "Frost", "Blaze", "Void", "Toxic", "Swift", "Iron", "Night", "Storm",
        "Crimson", "Silent", "Lucky", "Rapid", "Ghost", "Cyber", "Hyper", "Mega", "Turbo", "Lazy", "Sneaky", "Crazy", "Epic",
        "Pixel", "Noob", "Pro", "Ender", "Nether", "Creep", "Diamond", "Emerald", "Golden", "Wither", "Mace", "Crit", "Tap"};
    private static final String[] SUF = {"Gamer", "Slayer", "Wolf", "Fox", "Ninja", "King", "Lord", "Knight", "Hunter", "Bot",
        "Master", "God", "Boy", "Girl", "Dude", "PvP", "Clicker", "Warrior", "Sniper", "Tank", "Main", "Sweat", "Legend", "YT"};

    private NameGenerator() {
    }

    /** Random letters/digits of the given length, usable as a profile name (ASCII, &lt;= 16). */
    public static String scrambled(int min, int max) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int len = Math.max(1, Math.min(16, min + r.nextInt(Math.max(1, max - min + 1))));
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(ALNUM.charAt(r.nextInt(ALNUM.length())));
        return sb.toString();
    }

    /** "____D____" style. */
    public static String underscore() {
        char c = (char) ('A' + ThreadLocalRandom.current().nextInt(26));
        return "____" + c + "____";
    }

    public static String gamer() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String name = switch (r.nextInt(5)) {
            case 0 -> PRE[r.nextInt(PRE.length)] + SUF[r.nextInt(SUF.length)];
            case 1 -> PRE[r.nextInt(PRE.length)] + "_" + SUF[r.nextInt(SUF.length)] + r.nextInt(100);
            case 2 -> "x" + PRE[r.nextInt(PRE.length)] + "x";
            case 3 -> SUF[r.nextInt(SUF.length)] + (2000 + r.nextInt(26));
            default -> PRE[r.nextInt(PRE.length)].toLowerCase(Locale.ROOT) + SUF[r.nextInt(SUF.length)].toLowerCase(Locale.ROOT) + r.nextInt(10);
        };
        return name.length() > 16 ? name.substring(0, 16) : name;
    }

    /** A profile-safe version of any display name (ASCII printable, no spaces, max 16). */
    public static String profileSafe(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) if (c >= '!' && c <= '~') sb.append(c);
        String out = sb.length() > 16 ? sb.substring(0, 16) : sb.toString();
        return out.isEmpty() ? scrambled(6, 10) : out;
    }

    public static Style parse(String s) {
        try {
            return Style.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            return Style.GAMER;
        }
    }
}

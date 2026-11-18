package dev.unstablesmp.bot.skin;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import java.util.Locale;
import net.kyori.adventure.key.Key;
import org.bukkit.profile.PlayerTextures;

/**
 * Turns a skin spec into a mannequin profile.
 * <ul>
 *   <li>{@code pack:<id>} - a skin from the UnstableSMP resource pack (generated black NULL skin, or any PNG in
 *       plugins/UnstableSMP/skins). Add {@code :slim} for the slim model.</li>
 *   <li>{@code user:<name>} - that Minecraft account's skin (resolved by the client).</li>
 *   <li>{@code texture:<base64>[:<signature>]} - a raw textures property (e.g. from MineSkin).</li>
 *   <li>{@code default:<steve|alex|ari|efe|kai|makena|noor|sunny|zuri>} - a built-in skin.</li>
 * </ul>
 */
public final class SkinService {
    private final PackServer pack;

    public SkinService(PackServer pack) {
        this.pack = pack;
    }

    /** Builds a profile; {@code fallback} is used when a pack skin can't be shown (pack disabled/missing). */
    public ResolvableProfile profile(String spec, String profileName, String fallback) {
        String s = spec == null ? "" : spec.trim();
        int colon = s.indexOf(':');
        String kind = colon < 0 ? "user" : s.substring(0, colon).toLowerCase(Locale.ROOT);
        String value = colon < 0 ? s : s.substring(colon + 1);
        String safeName = NameGenerator.profileSafe(profileName);
        switch (kind) {
            case "pack" -> {
                boolean slim = value.toLowerCase(Locale.ROOT).endsWith(":slim");
                String id = (slim ? value.substring(0, value.length() - 5) : value).toLowerCase(Locale.ROOT);
                if (!pack.active() || !pack.hasSkin(id)) {
                    return fallback == null || fallback.startsWith("pack:") ? builtin("steve", safeName, false) : profile(fallback, profileName, null);
                }
                return ResolvableProfile.resolvableProfile().name(safeName)
                        .skinPatch(p -> p.body(Key.key(PackServer.NAMESPACE, "entity/skins/" + id))
                                .model(slim ? PlayerTextures.SkinModel.SLIM : PlayerTextures.SkinModel.CLASSIC))
                        .build();
            }
            case "texture" -> {
                String[] parts = value.split(":", 2);
                return ResolvableProfile.resolvableProfile().name(safeName)
                        .addProperty(new ProfileProperty("textures", parts[0], parts.length > 1 ? parts[1] : null)).build();
            }
            case "default" -> {
                return builtin(value.toLowerCase(Locale.ROOT), safeName, false);
            }
            default -> { // user
                String user = NameGenerator.profileSafe(value.isBlank() ? profileName : value);
                return ResolvableProfile.resolvableProfile().name(user).build();
            }
        }
    }

    private static ResolvableProfile builtin(String name, String profileName, boolean slim) {
        String n = name.matches("steve|alex|ari|efe|kai|makena|noor|sunny|zuri") ? name : "steve";
        return ResolvableProfile.resolvableProfile().name(profileName)
                .skinPatch(p -> p.body(Key.key("minecraft", "entity/player/" + (slim ? "slim/" : "wide/") + n))
                        .model(slim ? PlayerTextures.SkinModel.SLIM : PlayerTextures.SkinModel.CLASSIC))
                .build();
    }
}

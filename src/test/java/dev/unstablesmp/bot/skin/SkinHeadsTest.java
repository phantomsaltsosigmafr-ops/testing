package dev.unstablesmp.bot.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class SkinHeadsTest {

    private static YamlConfiguration bundled() {
        var in = SkinHeadsTest.class.getClassLoader().getResourceAsStream("skin-heads.yml");
        assertNotNull(in, "skin-heads.yml is bundled");
        return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
    }

    @Test
    void defaultNullSkinHasABundledSignedCopy() {
        // Must match resource-pack.null-skin defaults; if the generator changes, re-sign the skin.
        String hash = SkinHeads.pixelHash(PackServer.nullSkin("none", 0x0B0B0B));
        YamlConfiguration y = bundled();
        assertTrue(y.isConfigurationSection(hash), "bundled texture matches the generated NULL skin");
        String value = y.getString(hash + ".value");
        assertNotNull(y.getString(hash + ".signature"));
        String json = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        String url = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("textures")
                .getAsJsonObject("SKIN").get("url").getAsString();
        assertTrue(url.startsWith("http://textures.minecraft.net/texture/"), url);
    }

    @Test
    void pixelHashIgnoresTransparentColourAndSeesChanges() {
        var a = PackServer.nullSkin("none", 0x0B0B0B);
        var b = PackServer.nullSkin("none", 0x0B0B0B);
        b.setRGB(63, 63, 0x00FF00FF); // fully transparent, different colour bits
        assertEquals(SkinHeads.pixelHash(a), SkinHeads.pixelHash(b));
        assertNotEquals(SkinHeads.pixelHash(a), SkinHeads.pixelHash(PackServer.nullSkin("red", 0x0B0B0B)));
    }

    @Test
    void packIdAndMineSkinParsing() {
        assertEquals("null", SkinHeads.packId("pack:null"));
        assertEquals("null", SkinHeads.packId("PACK:Null:slim"));
        assertNull(SkinHeads.packId("user:JamatoP"));
        var ok = JsonParser.parseString("{\"success\":true,\"skin\":{\"texture\":{\"data\":{\"value\":\"v\",\"signature\":\"s\"}}}}").getAsJsonObject();
        assertEquals(new SkinHeads.Texture("v", "s"), SkinHeads.parse(ok));
        var bad = JsonParser.parseString("{\"success\":false,\"errors\":[{\"message\":\"rate limited\"}]}").getAsJsonObject();
        assertNull(SkinHeads.parse(bad));
    }
}

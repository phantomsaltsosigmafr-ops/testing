package dev.unstablesmp.portal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SpawnCommandTest {

    @Test
    void formatsTheVexBotCommand() {
        Map<String, String> v = SpawnCommand.values(101, 64, -40, 101.5, 64.0, -39.5, 3, "world", 2, 7, "south");
        assertEquals("vexbot nullportalspawn 101 64 -40 3",
                SpawnCommand.format("vexbot nullportalspawn {x} {y} {z} {count}", v));
        assertEquals("tp 101.5 64.0 -39.5 world south 0.0 #2/7",
                SpawnCommand.format("tp {xd} {yd} {zd} {world} {facing} {yaw} #{portal}/{invasion}", v));
    }

    @Test
    void unknownPlaceholdersAndBracesStay() {
        Map<String, String> v = Map.of("x", "1");
        assertEquals("say {nope} {x 1 {", SpawnCommand.format("say {nope} {x {x} {", v));
    }

    @Test
    void label() {
        assertEquals("vexbot", SpawnCommand.label("/VexBot nullportalspawn 1 2 3 4"));
        assertEquals("vexbot", SpawnCommand.label("vexbot"));
    }
}

package dev.unstablesmp.cine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CameraSplineTest {
    @Test
    void catmullRomPassesThroughKeyframes() {
        assertEquals(10, CameraService.cr(0, 10, 20, 30, 0), 1e-9);
        assertEquals(20, CameraService.cr(0, 10, 20, 30, 1), 1e-9);
        assertEquals(15, CameraService.cr(0, 10, 20, 30, 0.5), 1e-9);
    }
}

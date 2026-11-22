package com.hingebot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class ConfigTest {

    @Test
    public void testDefaultsAppliedForMissingKeys() {
        Config config = Config.load(null, Map.of(), new ArrayList<>());
        assertEquals("localhost:5555", config.adbTarget());
        assertEquals(1, config.cycles());
        assertEquals(40, config.maxTotalActions());
        assertEquals(8, config.maxLikes());
        assertTrue(config.dryRun());
        assertEquals(15, config.startDelayS());
        assertArrayEquals(new int[]{2500, 6000}, config.delayMs());
        assertArrayEquals(new int[]{4000, 9000}, config.cyclePauseMs());
        assertEquals(1200, config.postActionSettleMs());
        assertEquals("co.hinge.app", config.appPackage());
        assertFalse(config.useFallbackRatios());
        assertTrue(config.stopFile().endsWith("hingebot.stop"));
        assertEquals("logs", config.logDir());
        assertEquals(List.of(Action.LIKE, Action.LIKE, Action.SKIP, Action.SKIP, Action.SKIP), config.sequence());
        assertNotNull(config.rawSequence());
        assertNotNull(config.labelsLike());
        assertNotNull(config.labelsSkip());
        assertNotNull(config.labelsSendLike());
        assertNotNull(config.labelsOutOfLikes());
        assertNotNull(config.fallbackLike());
        assertNotNull(config.fallbackSkip());
        assertNotNull(config.fallbackSendLike());
    }

    @Test
    public void testExpandUserHomeEdgeCases() {
        assertNull(Config.expandUserHome(null));
        String expandedTilde = Config.expandUserHome("~");
        assertNotNull(expandedTilde);
        assertFalse(expandedTilde.startsWith("~"));

        String normalPath = Config.expandUserHome("/tmp/test");
        assertEquals("/tmp/test", normalPath);
    }

    @Test
    public void testLoadAllPropertiesFromFile(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.properties");
        String content = """
            adb_target=localhost:1234
            sequence=LIKE,SKIP
            cycles=3
            max_total_actions=20
            max_likes=5
            dry_run=false
            start_delay_s=10
            delay_ms=2000,4000
            cycle_pause_ms=5000,8000
            post_action_settle_ms=1500
            app_package=co.hinge.app.test
            use_fallback_ratios=true
            stop_file=/tmp/hingebot.stop
            log_dir=my_logs
            labels.like=Heart,Up
            labels.skip=Pass,Next
            labels.send_like=Send,Confirm
            labels.out_of_likes=no_more
            fallback.like=0.8,0.7
            fallback.skip=0.2,0.9
            fallback.send_like=0.5,0.8
            """;
        Files.writeString(configFile, content);

        List<String> warnings = new ArrayList<>();
        Config config = Config.load(configFile, Map.of(), warnings);

        assertEquals("localhost:1234", config.adbTarget());
        assertEquals(List.of(Action.LIKE, Action.SKIP), config.sequence());
        assertEquals(3, config.cycles());
        assertEquals(20, config.maxTotalActions());
        assertEquals(5, config.maxLikes());
        assertFalse(config.dryRun());
        assertEquals(10, config.startDelayS());
        assertArrayEquals(new int[]{2000, 4000}, config.delayMs());
        assertArrayEquals(new int[]{5000, 8000}, config.cyclePauseMs());
        assertEquals(1500, config.postActionSettleMs());
        assertEquals("co.hinge.app.test", config.appPackage());
        assertTrue(config.useFallbackRatios());
        assertEquals("/tmp/hingebot.stop", config.stopFile());
        assertEquals("my_logs", config.logDir());
        assertEquals(List.of("Heart", "Up"), config.labelsLike());
        assertEquals(List.of("Pass", "Next"), config.labelsSkip());
        assertEquals(List.of("Send", "Confirm"), config.labelsSendLike());
        assertEquals(List.of("no_more"), config.labelsOutOfLikes());
        assertArrayEquals(new double[]{0.8, 0.7}, config.fallbackLike());
        assertArrayEquals(new double[]{0.2, 0.9}, config.fallbackSkip());
        assertArrayEquals(new double[]{0.5, 0.8}, config.fallbackSendLike());
    }

    @Test
    public void testLoadNonExistentFile(@TempDir Path tempDir) {
        Path missing = tempDir.resolve("missing.properties");
        List<String> warnings = new ArrayList<>();
        Config config = Config.load(missing, Map.of(), warnings);
        assertNotNull(config);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Config file not found")));
    }

    @Test
    public void testValidConfigValidationPasses() {
        Config config = Config.defaults();
        List<String> warnings = new ArrayList<>();
        assertDoesNotThrow(() -> ConfigValidator.validate(config, warnings));
    }

    @Test
    public void testFlagOverridesWork() {
        Map<String, String> cliOverrides = Map.of(
            "cycles", "5",
            "adb_target", "127.0.0.1:9999",
            "start_delay_s", "30",
            "dry_run", "false"
        );
        Config config = Config.load(null, cliOverrides, new ArrayList<>());
        assertEquals(5, config.cycles());
        assertEquals("127.0.0.1:9999", config.adbTarget());
        assertEquals(30, config.startDelayS());
        assertFalse(config.dryRun());
    }

    @Test
    public void testUnknownKeyProducesWarning(@TempDir Path tempDir) throws IOException {
        Path configFile = tempDir.resolve("config.properties");
        Files.writeString(configFile, "unknown_custom_key=value\ncycles=2\n");

        List<String> warnings = new ArrayList<>();
        Config config = Config.load(configFile, Map.of(), warnings);

        assertEquals(2, config.cycles());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("Unknown key in config file: unknown_custom_key")));
    }

    @Test
    public void testNonLocalAdbTargetRejected() {
        Config config = new Config(
            "192.168.1.100:5555",
            List.of(Action.LIKE), List.of("LIKE"),
            1, 10, 5, true, 5,
            new int[]{2000, 3000}, new int[]{4000, 5000}, 1000,
            "co.hinge.app", false, "/tmp/stop", "logs",
            List.of("Like"), List.of("Skip"), List.of("Send"), List.of("out"),
            new double[]{0.5, 0.5}, new double[]{0.5, 0.5}, new double[]{0.5, 0.5}
        );

        Errors.ConfigValidationException ex = assertThrows(Errors.ConfigValidationException.class,
            () -> ConfigValidator.validate(config, new ArrayList<>()));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("localhost or 127.0.0.1 ONLY")));
    }

    @Test
    public void testAdbTargetPortValidation() {
        Config configInvalidPort = new Config(
            "localhost:70000",
            List.of(Action.LIKE), List.of("LIKE"),
            1, 10, 5, true, 5,
            new int[]{2000, 3000}, new int[]{4000, 5000}, 1000,
            "co.hinge.app", false, "/tmp/stop", "logs",
            List.of("Like"), List.of("Skip"), List.of("Send"), List.of("out"),
            new double[]{0.5, 0.5}, new double[]{0.5, 0.5}, new double[]{0.5, 0.5}
        );
        Errors.ConfigValidationException ex = assertThrows(Errors.ConfigValidationException.class,
            () -> ConfigValidator.validate(configInvalidPort, new ArrayList<>()));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("port must be between 1 and 65535")));

        Config configAlphaPort = new Config(
            "localhost:abc",
            List.of(Action.LIKE), List.of("LIKE"),
            1, 10, 5, true, 5,
            new int[]{2000, 3000}, new int[]{4000, 5000}, 1000,
            "co.hinge.app", false, "/tmp/stop", "logs",
            List.of("Like"), List.of("Skip"), List.of("Send"), List.of("out"),
            new double[]{0.5, 0.5}, new double[]{0.5, 0.5}, new double[]{0.5, 0.5}
        );
        Errors.ConfigValidationException ex2 = assertThrows(Errors.ConfigValidationException.class,
            () -> ConfigValidator.validate(configAlphaPort, new ArrayList<>()));
        assertTrue(ex2.getErrors().stream().anyMatch(e -> e.contains("port must be a valid number")));
    }

    @Test
    public void testInvalidSequenceItemRejected() {
        Config config = new Config(
            "localhost:5555",
            List.of(Action.LIKE), List.of("LIKE", "INVALID_ACTION"),
            1, 10, 5, true, 5,
            new int[]{2000, 3000}, new int[]{4000, 5000}, 1000,
            "co.hinge.app", false, "/tmp/stop", "logs",
            List.of("Like"), List.of("Skip"), List.of("Send"), List.of("out"),
            new double[]{0.5, 0.5}, new double[]{0.5, 0.5}, new double[]{0.5, 0.5}
        );
        Errors.ConfigValidationException ex = assertThrows(Errors.ConfigValidationException.class,
            () -> ConfigValidator.validate(config, new ArrayList<>()));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("sequence item must be LIKE or SKIP")));
    }

    @Test
    public void testInvalidDelayRangeMinGreaterThanMax() {
        Config config = new Config(
            "localhost:5555",
            List.of(Action.LIKE), List.of("LIKE"),
            1, 10, 5, true, 5,
            new int[]{5000, 2000}, new int[]{4000, 5000}, 1000,
            "co.hinge.app", false, "/tmp/stop", "logs",
            List.of("Like"), List.of("Skip"), List.of("Send"), List.of("out"),
            new double[]{0.5, 0.5}, new double[]{0.5, 0.5}, new double[]{0.5, 0.5}
        );
        Errors.ConfigValidationException ex = assertThrows(Errors.ConfigValidationException.class,
            () -> ConfigValidator.validate(config, new ArrayList<>()));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("cannot be greater than max value")));
    }

    @Test
    public void testInvalidDelayRangeMinLessThan1000() {
        Config config = new Config(
            "localhost:5555",
            List.of(Action.LIKE), List.of("LIKE"),
            1, 10, 5, true, 5,
            new int[]{500, 2000}, new int[]{4000, 5000}, 1000,
            "co.hinge.app", false, "/tmp/stop", "logs",
            List.of("Like"), List.of("Skip"), List.of("Send"), List.of("out"),
            new double[]{0.5, 0.5}, new double[]{0.5, 0.5}, new double[]{0.5, 0.5}
        );
        Errors.ConfigValidationException ex = assertThrows(Errors.ConfigValidationException.class,
            () -> ConfigValidator.validate(config, new ArrayList<>()));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("min value must be >= 1000 ms")));
    }

    @Test
    public void testInvalidFallbackRatioOutOfBounds() {
        Config config = new Config(
            "localhost:5555",
            List.of(Action.LIKE), List.of("LIKE"),
            1, 10, 5, true, 5,
            new int[]{2000, 3000}, new int[]{4000, 5000}, 1000,
            "co.hinge.app", false, "/tmp/stop", "logs",
            List.of("Like"), List.of("Skip"), List.of("Send"), List.of("out"),
            new double[]{1.5, 0.5}, new double[]{0.5, 0.5}, new double[]{0.5, 0.5}
        );
        Errors.ConfigValidationException ex = assertThrows(Errors.ConfigValidationException.class,
            () -> ConfigValidator.validate(config, new ArrayList<>()));
        assertTrue(ex.getErrors().stream().anyMatch(e -> e.contains("must be between 0.0 and 1.0")));
    }

    @Test
    public void testCyclesZeroGeneratesWarning() {
        Config config = new Config(
            "localhost:5555",
            List.of(Action.LIKE), List.of("LIKE"),
            0, 10, 5, true, 5,
            new int[]{2000, 3000}, new int[]{4000, 5000}, 1000,
            "co.hinge.app", false, "/tmp/stop", "logs",
            List.of("Like"), List.of("Skip"), List.of("Send"), List.of("out"),
            new double[]{0.5, 0.5}, new double[]{0.5, 0.5}, new double[]{0.5, 0.5}
        );
        List<String> warnings = new ArrayList<>();
        assertDoesNotThrow(() -> ConfigValidator.validate(config, warnings));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("cycles is set to 0")));
    }

    @Test
    public void testMultipleValidationErrorsCollectedTogether() {
        Config config = new Config(
            "invalid_host:5555",
            List.of(), List.of("INVALID"),
            -1, 0, -1, true, -5,
            new int[]{500, 200}, new int[]{500, 200}, 1000,
            "co.hinge.app", false, "/tmp/stop", "logs",
            List.of("Like"), List.of("Skip"), List.of("Send"), List.of("out"),
            new double[]{2.0, -0.5}, new double[]{0.5, 0.5}, new double[]{0.5, 0.5}
        );

        Errors.ConfigValidationException ex = assertThrows(Errors.ConfigValidationException.class,
            () -> ConfigValidator.validate(config, new ArrayList<>()));
        assertTrue(ex.getErrors().size() >= 5, "Expected multiple validation errors, got: " + ex.getErrors().size());
    }
}

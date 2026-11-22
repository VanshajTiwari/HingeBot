package com.hingebot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class AdbTest {

    @Test
    public void testDryRunSendsNothingForTapAndBack() {
        FakeCommandRunner runner = new FakeCommandRunner();
        FakeSleeper sleeper = new FakeSleeper();
        Adb adb = new Adb(runner, sleeper, "localhost:5555", true);

        adb.tap(100, 200);
        adb.back();

        assertTrue(runner.getExecutedCommands().isEmpty());
    }

    @Test
    public void testMissingBinaryThrowsClearAdbException() {
        FakeCommandRunner runner = new FakeCommandRunner();
        runner.setDefaultResult(new CommandRunner.Result(-1, "", "Failed to execute command: java.io.IOException", false));
        FakeSleeper sleeper = new FakeSleeper();
        Adb adb = new Adb(runner, sleeper, "localhost:5555", false);

        Errors.AdbException ex = assertThrows(Errors.AdbException.class, () -> adb.checkDevice());
        assertTrue(ex.getMessage().contains("adb not found; run: pkg install android-tools"));
    }

    @Test
    public void testTimeoutThrowsAdbException() {
        FakeCommandRunner runner = new FakeCommandRunner();
        runner.setDefaultResult(new CommandRunner.Result(-1, "", "Timed out", true));
        FakeSleeper sleeper = new FakeSleeper();
        Adb adb = new Adb(runner, sleeper, "localhost:5555", false);

        Errors.AdbException ex = assertThrows(Errors.AdbException.class, () -> adb.checkDevice());
        assertTrue(ex.getMessage().contains("timed out"));
    }

    @Test
    public void testRetryCountAndBackoffValues() {
        FakeCommandRunner runner = new FakeCommandRunner();
        runner.setDefaultResult(new CommandRunner.Result(1, "", "Error", false));
        FakeSleeper sleeper = new FakeSleeper();
        Adb adb = new Adb(runner, sleeper, "localhost:5555", false);

        assertThrows(Errors.AdbException.class, () -> adb.retry(() -> {
            adb.runTarget(List.of("get-state"), 1000);
            return null;
        }, 3));

        assertEquals(3, runner.getExecutedCommands().size());
        assertEquals(List.of(1000L, 2000L), sleeper.getSleptTimes());
    }

    @Test
    public void testConnectRetryBackoffAndFailureMessage() {
        FakeCommandRunner runner = new FakeCommandRunner();
        runner.setDefaultResult(new CommandRunner.Result(0, "List of devices attached\n", "", false));
        FakeSleeper sleeper = new FakeSleeper();
        Adb adb = new Adb(runner, sleeper, "localhost:5555", false);

        Errors.AdbException ex = assertThrows(Errors.AdbException.class, () -> adb.connect());
        assertTrue(ex.getMessage().contains("Failed to connect to ADB target 'localhost:5555'"));
        assertTrue(ex.getMessage().contains("Re-pair if necessary with: adb pair"));
        assertEquals(List.of(1000L, 2000L), sleeper.getSleptTimes());
    }

    @Test
    public void testDumpUiStripsTrailerAndRetriesOnBadXml() {
        FakeCommandRunner runner = new FakeCommandRunner();
        // Return bad XML twice, then valid XML
        final int[] calls = {0};
        CommandRunner customRunner = (cmd, timeoutMs) -> {
            runner.getExecutedCommands().add(cmd);
            calls[0]++;
            if (calls[0] < 3) {
                return new CommandRunner.Result(0, "UI hierchary dumped to /dev/tty", "", false);
            }
            return new CommandRunner.Result(0, "<?xml version='1.0'?><hierarchy></hierarchy>\nUI hierchary dumped to /dev/tty", "", false);
        };

        FakeSleeper sleeper = new FakeSleeper();
        Adb adb = new Adb(customRunner, sleeper, "localhost:5555", false);

        String xml = adb.dumpUi();
        assertEquals("<?xml version='1.0'?><hierarchy></hierarchy>", xml);
        assertEquals(3, calls[0]);
        assertEquals(List.of(500L, 500L), sleeper.getSleptTimes());
    }

    @Test
    public void testScreenSizeParsingOverrideAndPhysical() {
        FakeCommandRunner runner = new FakeCommandRunner();
        runner.setDefaultResult(new CommandRunner.Result(0, "Physical size: 1080x2400\nOverride size: 1080x2340\n", "", false));
        FakeSleeper sleeper = new FakeSleeper();
        Adb adb = new Adb(runner, sleeper, "localhost:5555", false);

        int[] size = adb.screenSize();
        assertArrayEquals(new int[]{1080, 2340}, size);
    }

    @Test
    public void testForegroundPackageExtraction() {
        FakeCommandRunner runner = new FakeCommandRunner();
        runner.setDefaultResult(new CommandRunner.Result(0, "  mCurrentFocus=Window{123 u0 co.hinge.app/co.hinge.app.MainActivity}\n", "", false));
        FakeSleeper sleeper = new FakeSleeper();
        Adb adb = new Adb(runner, sleeper, "localhost:5555", false);

        String pkg = adb.foregroundPackage();
        assertEquals("co.hinge.app", pkg);
    }
}

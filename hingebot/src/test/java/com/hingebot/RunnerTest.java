package com.hingebot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class RunnerTest {

    private String loadResource(String name) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(name)) {
            assertNotNull(in, "Resource not found: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            fail("Failed to load resource " + name + ": " + e.getMessage());
            return "";
        }
    }

    private static class TestScriptedAdb extends Adb {
        public int connectCount = 0;
        public boolean connectFails = false;
        public boolean checkDeviceFails = false;
        public boolean screenUnlocked = true;
        public String fgPackage = "co.hinge.app";
        public List<String> uiDumps = new ArrayList<>();
        public int dumpIndex = 0;
        public List<int[]> recordedTaps = new ArrayList<>();
        public int backCount = 0;

        public TestScriptedAdb(boolean dryRun) {
            super(new FakeCommandRunner(), new FakeSleeper(), "localhost:5555", dryRun);
        }

        @Override
        public void connect() {
            connectCount++;
            if (connectFails) {
                throw new Errors.AdbException("Failed to connect");
            }
        }

        @Override
        public void checkDevice() {
            if (checkDeviceFails) {
                throw new Errors.AdbException("Device state disconnected");
            }
        }

        @Override
        public int[] screenSize() {
            return new int[]{1080, 2400};
        }

        @Override
        public String foregroundPackage() {
            return fgPackage;
        }

        @Override
        public boolean isScreenOnAndUnlocked() {
            return screenUnlocked;
        }

        @Override
        public String dumpUi() {
            if (uiDumps.isEmpty()) {
                return "<?xml version='1.0'?><hierarchy></hierarchy>";
            }
            if (dumpIndex >= uiDumps.size()) {
                return uiDumps.get(uiDumps.size() - 1);
            }
            return uiDumps.get(dumpIndex++);
        }

        @Override
        public void tap(int x, int y) {
            if (!isDryRun()) {
                recordedTaps.add(new int[]{x, y});
            }
        }

        @Override
        public void back() {
            if (!isDryRun()) {
                backCount++;
            }
        }
    }

    @Test
    public void testSequenceOrderAndCycles2Cycles10Actions(@TempDir Path tempDir) {
        TestScriptedAdb adb = new TestScriptedAdb(false);

        String profile1 = loadResource("profile.xml");
        String sheet = loadResource("send_like_sheet.xml");
        String profile2 = profile1.replace("Jane, 26", "Mary, 24");
        String profile3 = profile1.replace("Jane, 26", "Sarah, 28");

        for (int i = 0; i < 2; i++) {
            // LIKE 1
            adb.uiDumps.add(profile1);
            adb.uiDumps.add(sheet);
            adb.uiDumps.add(profile2);

            // LIKE 2
            adb.uiDumps.add(profile2);
            adb.uiDumps.add(sheet);
            adb.uiDumps.add(profile3);

            // SKIP 1
            adb.uiDumps.add(profile3);
            adb.uiDumps.add(profile1);

            // SKIP 2
            adb.uiDumps.add(profile1);
            adb.uiDumps.add(profile2);

            // SKIP 3
            adb.uiDumps.add(profile2);
            adb.uiDumps.add(profile3);
        }

        FakeTermux termux = new FakeTermux();
        FakeSleeper sleeper = new FakeSleeper();
        Path stopFile = tempDir.resolve("hingebot.stop");
        RunLog log = new RunLog(tempDir.resolve("logs"), Clock.systemUTC());

        Config config = new Config(
            "localhost:5555",
            List.of(Action.LIKE, Action.LIKE, Action.SKIP, Action.SKIP, Action.SKIP),
            List.of("LIKE", "LIKE", "SKIP", "SKIP", "SKIP"),
            2, 40, 10, false, 0,
            new int[]{1000, 1000}, new int[]{1000, 1000}, 100,
            "co.hinge.app", false, stopFile.toString(), tempDir.resolve("logs").toString(),
            List.of("Like"), List.of("Skip"), List.of("Send like"), List.of("out of likes"),
            new double[]{0.88, 0.70}, new double[]{0.12, 0.92}, new double[]{0.50, 0.90}
        );

        Runner runner = new Runner(adb, termux, sleeper, new Random(42), Clock.systemUTC(), stopFile, config, log);
        StopReason reason = runner.run();

        assertEquals(StopReason.COMPLETED, reason);
        assertEquals(14, adb.recordedTaps.size());
        assertTrue(termux.isWakeLocked());
        assertTrue(termux.isWakeUnlocked());
    }

    @Test
    public void testFallbackRatiosUsedWhenTargetNotFoundAndFallbackEnabled(@TempDir Path tempDir) {
        TestScriptedAdb adb = new TestScriptedAdb(false);
        String blankXml = "<?xml version='1.0'?><hierarchy><node bounds='[0,0][1080,2400]' text='No buttons' package='co.hinge.app'/></hierarchy>";
        String profile2 = loadResource("profile.xml").replace("Jane, 26", "Mary, 24");

        adb.uiDumps.add(blankXml); // SKIP dump
        adb.uiDumps.add(profile2); // after fallback tap dump

        FakeTermux termux = new FakeTermux();
        FakeSleeper sleeper = new FakeSleeper();
        Path stopFile = tempDir.resolve("hingebot.stop");
        RunLog log = new RunLog(tempDir.resolve("logs"), Clock.systemUTC());

        Config config = new Config(
            "localhost:5555",
            List.of(Action.SKIP), List.of("SKIP"),
            1, 10, 5, false, 0,
            new int[]{1000, 1000}, new int[]{1000, 1000}, 100,
            "co.hinge.app", true, stopFile.toString(), tempDir.resolve("logs").toString(),
            List.of("Like"), List.of("Skip"), List.of("Send like"), List.of("out of likes"),
            new double[]{0.88, 0.70}, new double[]{0.12, 0.92}, new double[]{0.50, 0.90}
        );

        Runner runner = new Runner(adb, termux, sleeper, new Random(), Clock.systemUTC(), stopFile, config, log);
        StopReason reason = runner.run();

        assertEquals(StopReason.COMPLETED, reason);
        assertEquals(1, adb.recordedTaps.size());
        int[] tap = adb.recordedTaps.get(0);
        assertEquals((int)(1080 * 0.12), tap[0]);
        assertEquals((int)(2400 * 0.92), tap[1]);
    }

    @Test
    public void testLikeFallbackRatiosUsedWhenLikeNotFoundAndFallbackEnabled(@TempDir Path tempDir) {
        TestScriptedAdb adb = new TestScriptedAdb(false);
        String blankXml = "<?xml version='1.0'?><hierarchy><node bounds='[0,0][1080,2400]' text='No buttons' package='co.hinge.app'/></hierarchy>";
        String sheet = loadResource("send_like_sheet.xml");
        String profile2 = loadResource("profile.xml").replace("Jane, 26", "Mary, 24");

        adb.uiDumps.add(blankXml); // LIKE initial dump -> target not found, fallback ratio tap
        adb.uiDumps.add(sheet);    // sheet dump
        adb.uiDumps.add(profile2); // after send_like dump

        FakeTermux termux = new FakeTermux();
        FakeSleeper sleeper = new FakeSleeper();
        Path stopFile = tempDir.resolve("hingebot.stop");
        RunLog log = new RunLog(tempDir.resolve("logs"), Clock.systemUTC());

        Config config = new Config(
            "localhost:5555",
            List.of(Action.LIKE), List.of("LIKE"),
            1, 10, 5, false, 0,
            new int[]{1000, 1000}, new int[]{1000, 1000}, 100,
            "co.hinge.app", true, stopFile.toString(), tempDir.resolve("logs").toString(),
            List.of("Like"), List.of("Skip"), List.of("Send like"), List.of("out of likes"),
            new double[]{0.88, 0.70}, new double[]{0.12, 0.92}, new double[]{0.50, 0.90}
        );

        Runner runner = new Runner(adb, termux, sleeper, new Random(), Clock.systemUTC(), stopFile, config, log);
        StopReason reason = runner.run();

        assertEquals(StopReason.COMPLETED, reason);
        assertEquals(2, adb.recordedTaps.size());
        int[] firstTap = adb.recordedTaps.get(0);
        assertEquals((int)(1080 * 0.88), firstTap[0]);
        assertEquals((int)(2400 * 0.70), firstTap[1]);
    }

    @Test
    public void testSendLikeRetrySecondAttemptSuccess(@TempDir Path tempDir) {
        TestScriptedAdb adb = new TestScriptedAdb(false);
        String profile1 = loadResource("profile.xml");
        String blankXml = "<?xml version='1.0'?><hierarchy><node bounds='[0,0][1080,2400]' text='Loading sheet' package='co.hinge.app'/></hierarchy>";
        String sheet = loadResource("send_like_sheet.xml");
        String profile2 = profile1.replace("Jane, 26", "Mary, 24");

        adb.uiDumps.add(profile1);  // LIKE initial dump
        adb.uiDumps.add(blankXml);  // sheet dump attempt 1 (not found)
        adb.uiDumps.add(sheet);     // sheet dump attempt 2 (found!)
        adb.uiDumps.add(profile2);  // after send_like tap

        FakeTermux termux = new FakeTermux();
        FakeSleeper sleeper = new FakeSleeper();
        Path stopFile = tempDir.resolve("hingebot.stop");
        RunLog log = new RunLog(tempDir.resolve("logs"), Clock.systemUTC());

        Config config = new Config(
            "localhost:5555",
            List.of(Action.LIKE), List.of("LIKE"),
            1, 10, 5, false, 0,
            new int[]{1000, 1000}, new int[]{1000, 1000}, 100,
            "co.hinge.app", false, stopFile.toString(), tempDir.resolve("logs").toString(),
            List.of("Like"), List.of("Skip"), List.of("Send like"), List.of("out of likes"),
            new double[]{0.88, 0.70}, new double[]{0.12, 0.92}, new double[]{0.50, 0.90}
        );

        Runner runner = new Runner(adb, termux, sleeper, new Random(), Clock.systemUTC(), stopFile, config, log);
        StopReason reason = runner.run();

        assertEquals(StopReason.COMPLETED, reason);
        assertEquals(2, adb.recordedTaps.size());
    }

    @Test
    public void testSendLikeFailsAfter2AttemptsCallsBackAndThrowsActionFailedException(@TempDir Path tempDir) {
        TestScriptedAdb adb = new TestScriptedAdb(false);
        String profile1 = loadResource("profile.xml");
        String blankXml = "<?xml version='1.0'?><hierarchy><node bounds='[0,0][1080,2400]' text='No sheet' package='co.hinge.app'/></hierarchy>";

        adb.uiDumps.add(profile1);  // LIKE initial dump
        adb.uiDumps.add(blankXml);  // sheet dump attempt 1
        adb.uiDumps.add(blankXml);  // sheet dump attempt 2

        FakeTermux termux = new FakeTermux();
        FakeSleeper sleeper = new FakeSleeper();
        Path stopFile = tempDir.resolve("hingebot.stop");
        RunLog log = new RunLog(tempDir.resolve("logs"), Clock.systemUTC());

        Config config = new Config(
            "localhost:5555",
            List.of(Action.LIKE), List.of("LIKE"),
            1, 10, 5, false, 0,
            new int[]{1000, 1000}, new int[]{1000, 1000}, 100,
            "co.hinge.app", false, stopFile.toString(), tempDir.resolve("logs").toString(),
            List.of("Like"), List.of("Skip"), List.of("Send like"), List.of("out of likes"),
            new double[]{0.88, 0.70}, new double[]{0.12, 0.92}, new double[]{0.50, 0.90}
        );

        Runner runner = new Runner(adb, termux, sleeper, new Random(), Clock.systemUTC(), stopFile, config, log);
        assertThrows(Errors.ActionFailedException.class, () -> runner.run());
        assertEquals(1, adb.backCount); // back() called once
    }

    @Test
    public void testLikeFingerprintUnchangedThrowsActionFailedException(@TempDir Path tempDir) {
        TestScriptedAdb adb = new TestScriptedAdb(false);
        String profile1 = loadResource("profile.xml");
        String sheet = loadResource("send_like_sheet.xml");

        adb.uiDumps.add(profile1); // LIKE initial dump
        adb.uiDumps.add(sheet);    // sheet dump
        adb.uiDumps.add(profile1); // after send_like dump (unchanged!)

        FakeTermux termux = new FakeTermux();
        FakeSleeper sleeper = new FakeSleeper();
        Path stopFile = tempDir.resolve("hingebot.stop");
        RunLog log = new RunLog(tempDir.resolve("logs"), Clock.systemUTC());

        Config config = new Config(
            "localhost:5555",
            List.of(Action.LIKE), List.of("LIKE"),
            1, 10, 5, false, 0,
            new int[]{1000, 1000}, new int[]{1000, 1000}, 100,
            "co.hinge.app", false, stopFile.toString(), tempDir.resolve("logs").toString(),
            List.of("Like"), List.of("Skip"), List.of("Send like"), List.of("out of likes"),
            new double[]{0.88, 0.70}, new double[]{0.12, 0.92}, new double[]{0.50, 0.90}
        );

        Runner runner = new Runner(adb, termux, sleeper, new Random(), Clock.systemUTC(), stopFile, config, log);
        assertThrows(Errors.ActionFailedException.class, () -> runner.run());
    }

    @Test
    public void testTargetNotFoundThrowsExceptionWhenFallbackDisabled(@TempDir Path tempDir) {
        TestScriptedAdb adb = new TestScriptedAdb(false);
        String blankXml = "<?xml version='1.0'?><hierarchy><node bounds='[0,0][1080,2400]' text='No buttons' package='co.hinge.app'/></hierarchy>";
        adb.uiDumps.add(blankXml);

        FakeTermux termux = new FakeTermux();
        FakeSleeper sleeper = new FakeSleeper();
        Path stopFile = tempDir.resolve("hingebot.stop");
        RunLog log = new RunLog(tempDir.resolve("logs"), Clock.systemUTC());

        Config config = new Config(
            "localhost:5555",
            List.of(Action.SKIP), List.of("SKIP"),
            1, 10, 5, false, 0,
            new int[]{1000, 1000}, new int[]{1000, 1000}, 100,
            "co.hinge.app", false, stopFile.toString(), tempDir.resolve("logs").toString(),
            List.of("Like"), List.of("Skip"), List.of("Send like"), List.of("out of likes"),
            new double[]{0.88, 0.70}, new double[]{0.12, 0.92}, new double[]{0.50, 0.90}
        );

        Runner runner = new Runner(adb, termux, sleeper, new Random(), Clock.systemUTC(), stopFile, config, log);
        assertThrows(Errors.TargetNotFoundException.class, () -> runner.run());
    }

    @Test
    public void testStopsAtMaxTotalActions(@TempDir Path tempDir) {
        TestScriptedAdb adb = new TestScriptedAdb(false);

        String profile1 = loadResource("profile.xml");
        String profile2 = profile1.replace("Jane, 26", "Mary, 24");
        for (int i = 0; i < 10; i++) {
            adb.uiDumps.add(profile1);
            adb.uiDumps.add(profile2);
        }

        FakeTermux termux = new FakeTermux();
        FakeSleeper sleeper = new FakeSleeper();
        Path stopFile = tempDir.resolve("hingebot.stop");
        RunLog log = new RunLog(tempDir.resolve("logs"), Clock.systemUTC());

        Config config = new Config(
            "localhost:5555",
            List.of(Action.SKIP), List.of("SKIP"),
            10, 3, 10, false, 0,
            new int[]{1000, 1000}, new int[]{1000, 1000}, 100,
            "co.hinge.app", false, stopFile.toString(), tempDir.resolve("logs").toString(),
            List.of("Like"), List.of("Skip"), List.of("Send like"), List.of("out of likes"),
            new double[]{0.88, 0.70}, new double[]{0.12, 0.92}, new double[]{0.50, 0.90}
        );

        Runner runner = new Runner(adb, termux, sleeper, new Random(), Clock.systemUTC(), stopFile, config, log);
        StopReason reason = runner.run();

        assertEquals(StopReason.MAX_TOTAL_ACTIONS, reason);
        assertEquals(3, adb.recordedTaps.size());
    }

    @Test
    public void testStopsAtMaxLikes(@TempDir Path tempDir) {
        TestScriptedAdb adb = new TestScriptedAdb(false);

        String profile1 = loadResource("profile.xml");
        String sheet = loadResource("send_like_sheet.xml");
        String profile2 = profile1.replace("Jane, 26", "Mary, 24");

        for (int i = 0; i < 5; i++) {
            adb.uiDumps.add(profile1);
            adb.uiDumps.add(sheet);
            adb.uiDumps.add(profile2);
        }

        FakeTermux termux = new FakeTermux();
        FakeSleeper sleeper = new FakeSleeper();
        Path stopFile = tempDir.resolve("hingebot.stop");
        RunLog log = new RunLog(tempDir.resolve("logs"), Clock.systemUTC());

        Config config = new Config(
            "localhost:5555",
            List.of(Action.LIKE), List.of("LIKE"),
            10, 40, 2, false, 0,
            new int[]{1000, 1000}, new int[]{1000, 1000}, 100,
            "co.hinge.app", false, stopFile.toString(), tempDir.resolve("logs").toString(),
            List.of("Like"), List.of("Skip"), List.of("Send like"), List.of("out of likes"),
            new double[]{0.88, 0.70}, new double[]{0.12, 0.92}, new double[]{0.50, 0.90}
        );

        Runner runner = new Runner(adb, termux, sleeper, new Random(), Clock.systemUTC(), stopFile, config, log);
        StopReason reason = runner.run();

        assertEquals(StopReason.MAX_LIKES, reason);
        assertEquals(4, adb.recordedTaps.size());
    }

    @Test
    public void testStopsWithCode4OnOutOfLikesFixture(@TempDir Path tempDir) {
        TestScriptedAdb adb = new TestScriptedAdb(false);
        String outOfLikesXml = loadResource("out_of_likes.xml");
        adb.uiDumps.add(outOfLikesXml);

        FakeTermux termux = new FakeTermux();
        FakeSleeper sleeper = new FakeSleeper();
        Path stopFile = tempDir.resolve("hingebot.stop");
        RunLog log = new RunLog(tempDir.resolve("logs"), Clock.systemUTC());

        Config config = new Config(
            "localhost:5555",
            List.of(Action.LIKE), List.of("LIKE"),
            1, 10, 5, false, 0,
            new int[]{1000, 1000}, new int[]{1000, 1000}, 100,
            "co.hinge.app", false, stopFile.toString(), tempDir.resolve("logs").toString(),
            List.of("Like"), List.of("Skip"), List.of("Send like"), List.of("out of likes"),
            new double[]{0.88, 0.70}, new double[]{0.12, 0.92}, new double[]{0.50, 0.90}
        );

        Runner runner = new Runner(adb, termux, sleeper, new Random(), Clock.systemUTC(), stopFile, config, log);
        assertThrows(Errors.OutOfLikesException.class, () -> runner.run());
        assertTrue(termux.isWakeUnlocked());
    }

    @Test
    public void testDisconnectMidRunThrowsAdbException(@TempDir Path tempDir) {
        TestScriptedAdb adb = new TestScriptedAdb(false) {
            private int checkCount = 0;
            @Override
            public void checkDevice() {
                checkCount++;
                if (checkCount >= 2) {
                    throw new Errors.AdbException("Device disconnected");
                }
            }
        };
        String profile1 = loadResource("profile.xml");
        String profile2 = profile1.replace("Jane, 26", "Mary, 24");
        adb.uiDumps.add(profile1);
        adb.uiDumps.add(profile2);

        FakeTermux termux = new FakeTermux();
        FakeSleeper sleeper = new FakeSleeper();
        Path stopFile = tempDir.resolve("hingebot.stop");
        RunLog log = new RunLog(tempDir.resolve("logs"), Clock.systemUTC());

        Config config = new Config(
            "localhost:5555",
            List.of(Action.SKIP, Action.SKIP), List.of("SKIP", "SKIP"),
            1, 10, 5, false, 0,
            new int[]{1000, 1000}, new int[]{1000, 1000}, 100,
            "co.hinge.app", false, stopFile.toString(), tempDir.resolve("logs").toString(),
            List.of("Like"), List.of("Skip"), List.of("Send like"), List.of("out of likes"),
            new double[]{0.88, 0.70}, new double[]{0.12, 0.92}, new double[]{0.50, 0.90}
        );

        Runner runner = new Runner(adb, termux, sleeper, new Random(), Clock.systemUTC(), stopFile, config, log);
        assertThrows(Errors.AdbException.class, () -> runner.run());
        assertTrue(termux.isWakeUnlocked());
    }

    @Test
    public void testForegroundAppChangesThrowsAppLostExceptionAndZeroTapsAfterwards(@TempDir Path tempDir) {
        TestScriptedAdb adb = new TestScriptedAdb(false) {
            private int fgCalls = 0;
            @Override
            public String foregroundPackage() {
                fgCalls++;
                if (fgCalls >= 2) {
                    return "com.other.app";
                }
                return "co.hinge.app";
            }
        };

        String profile1 = loadResource("profile.xml");
        adb.uiDumps.add(profile1);

        FakeTermux termux = new FakeTermux();
        FakeSleeper sleeper = new FakeSleeper();
        Path stopFile = tempDir.resolve("hingebot.stop");
        RunLog log = new RunLog(tempDir.resolve("logs"), Clock.systemUTC());

        Config config = new Config(
            "localhost:5555",
            List.of(Action.SKIP), List.of("SKIP"),
            1, 10, 5, false, 0,
            new int[]{1000, 1000}, new int[]{1000, 1000}, 100,
            "co.hinge.app", false, stopFile.toString(), tempDir.resolve("logs").toString(),
            List.of("Like"), List.of("Skip"), List.of("Send like"), List.of("out of likes"),
            new double[]{0.88, 0.70}, new double[]{0.12, 0.92}, new double[]{0.50, 0.90}
        );

        Runner runner = new Runner(adb, termux, sleeper, new Random(), Clock.systemUTC(), stopFile, config, log);
        assertThrows(Errors.AppLostException.class, () -> runner.run());
        assertTrue(adb.recordedTaps.isEmpty());
    }

    @Test
    public void testSkipWithUnchangedFingerprintRetriesOnceThenFails(@TempDir Path tempDir) {
        TestScriptedAdb adb = new TestScriptedAdb(false);
        String profile1 = loadResource("profile.xml");
        adb.uiDumps.add(profile1);
        adb.uiDumps.add(profile1);
        adb.uiDumps.add(profile1);

        FakeTermux termux = new FakeTermux();
        FakeSleeper sleeper = new FakeSleeper();
        Path stopFile = tempDir.resolve("hingebot.stop");
        RunLog log = new RunLog(tempDir.resolve("logs"), Clock.systemUTC());

        Config config = new Config(
            "localhost:5555",
            List.of(Action.SKIP), List.of("SKIP"),
            1, 10, 5, false, 0,
            new int[]{1000, 1000}, new int[]{1000, 1000}, 100,
            "co.hinge.app", false, stopFile.toString(), tempDir.resolve("logs").toString(),
            List.of("Like"), List.of("Skip"), List.of("Send like"), List.of("out of likes"),
            new double[]{0.88, 0.70}, new double[]{0.12, 0.92}, new double[]{0.50, 0.90}
        );

        Runner runner = new Runner(adb, termux, sleeper, new Random(), Clock.systemUTC(), stopFile, config, log);
        assertThrows(Errors.ActionFailedException.class, () -> runner.run());
        assertEquals(2, adb.recordedTaps.size());
    }

    @Test
    public void testStopFileHaltsAfterCurrentAction(@TempDir Path tempDir) throws Exception {
        Path stopFile = tempDir.resolve("hingebot.stop");
        TestScriptedAdb adb = new TestScriptedAdb(false) {
            @Override
            public void tap(int x, int y) {
                super.tap(x, y);
                try {
                    Files.createFile(stopFile);
                } catch (Exception ignored) {}
            }
        };
        String profile1 = loadResource("profile.xml");
        String profile2 = profile1.replace("Jane, 26", "Mary, 24");
        adb.uiDumps.add(profile1);
        adb.uiDumps.add(profile2);
        adb.uiDumps.add(profile1);

        FakeTermux termux = new FakeTermux();
        FakeSleeper sleeper = new FakeSleeper();
        RunLog log = new RunLog(tempDir.resolve("logs"), Clock.systemUTC());

        Config config = new Config(
            "localhost:5555",
            List.of(Action.SKIP, Action.SKIP), List.of("SKIP", "SKIP"),
            5, 10, 5, false, 0,
            new int[]{1000, 1000}, new int[]{1000, 1000}, 100,
            "co.hinge.app", false, stopFile.toString(), tempDir.resolve("logs").toString(),
            List.of("Like"), List.of("Skip"), List.of("Send like"), List.of("out of likes"),
            new double[]{0.88, 0.70}, new double[]{0.12, 0.92}, new double[]{0.50, 0.90}
        );

        Runner runner = new Runner(adb, termux, sleeper, new Random(), Clock.systemUTC(), stopFile, config, log);
        StopReason reason = runner.run();

        assertEquals(StopReason.STOP_FILE, reason);
        assertEquals(1, adb.recordedTaps.size());
    }

    @Test
    public void testStartDelayCountdownUsesInjectedSleeper(@TempDir Path tempDir) {
        TestScriptedAdb adb = new TestScriptedAdb(true);
        String profile1 = loadResource("profile.xml");
        String profile2 = profile1.replace("Jane, 26", "Mary, 24");
        adb.uiDumps.add(profile1);
        adb.uiDumps.add(profile2);

        FakeTermux termux = new FakeTermux();
        FakeSleeper sleeper = new FakeSleeper();
        Path stopFile = tempDir.resolve("hingebot.stop");
        RunLog log = new RunLog(tempDir.resolve("logs"), Clock.systemUTC());

        Config config = new Config(
            "localhost:5555",
            List.of(Action.SKIP), List.of("SKIP"),
            1, 10, 5, true, 3,
            new int[]{1000, 1000}, new int[]{1000, 1000}, 100,
            "co.hinge.app", false, stopFile.toString(), tempDir.resolve("logs").toString(),
            List.of("Like"), List.of("Skip"), List.of("Send like"), List.of("out of likes"),
            new double[]{0.88, 0.70}, new double[]{0.12, 0.92}, new double[]{0.50, 0.90}
        );

        Runner runner = new Runner(adb, termux, sleeper, new Random(), Clock.systemUTC(), stopFile, config, log);
        runner.run();

        long countdownSleeps = sleeper.getSleptTimes().stream().filter(t -> t == 1000L).count();
        assertTrue(countdownSleeps >= 3);
    }

    @Test
    public void testInfiniteCyclesAndRandomPauseRangesAndCountdownStopFlag(@TempDir Path tempDir) {
        TestScriptedAdb adb = new TestScriptedAdb(true);
        String profile1 = loadResource("profile.xml");
        String profile2 = profile1.replace("Jane, 26", "Mary, 24");
        adb.uiDumps.add(profile1);
        adb.uiDumps.add(profile2);

        FakeTermux termux = new FakeTermux();
        FakeSleeper sleeper = new FakeSleeper();
        Path stopFile = tempDir.resolve("hingebot.stop");
        RunLog log = new RunLog(tempDir.resolve("logs"), Clock.systemUTC());

        // cycles = 0 (infinite), delayMs range min < max, cyclePauseMs range min < max, startDelayS = 10
        Config config = new Config(
            "localhost:5555",
            List.of(Action.SKIP), List.of("SKIP"),
            0, 10, 5, true, 10,
            new int[]{1000, 2000}, new int[]{1000, 2000}, 100,
            "co.hinge.app", false, stopFile.toString(), tempDir.resolve("logs").toString(),
            List.of("Like"), List.of("Skip"), List.of("Send like"), List.of("out of likes"),
            new double[]{0.88, 0.70}, new double[]{0.12, 0.92}, new double[]{0.50, 0.90}
        );

        Runner runner = new Runner(adb, termux, sleeper, new Random(42), Clock.systemUTC(), stopFile, config, log);
        // Set stopFlag during run via a thread or test stopFlag set during countdown
        runner.getStopFlag().set(true);
        StopReason reason = runner.run();

        assertEquals(StopReason.STOP_FLAG, reason);
    }

    @Test
    public void testRandomPauseRangesWithDifferentMinAndMax(@TempDir Path tempDir) {
        TestScriptedAdb adb = new TestScriptedAdb(true);
        String profile1 = loadResource("profile.xml");
        String profile2 = profile1.replace("Jane, 26", "Mary, 24");
        adb.uiDumps.add(profile1);
        adb.uiDumps.add(profile2);

        FakeTermux termux = new FakeTermux();
        FakeSleeper sleeper = new FakeSleeper();
        Path stopFile = tempDir.resolve("hingebot.stop");
        RunLog log = new RunLog(tempDir.resolve("logs"), Clock.systemUTC());

        Config config = new Config(
            "localhost:5555",
            List.of(Action.SKIP), List.of("SKIP"),
            1, 10, 5, true, 0,
            new int[]{1000, 2000}, new int[]{1000, 2000}, 100,
            "co.hinge.app", false, stopFile.toString(), tempDir.resolve("logs").toString(),
            List.of("Like"), List.of("Skip"), List.of("Send like"), List.of("out of likes"),
            new double[]{0.88, 0.70}, new double[]{0.12, 0.92}, new double[]{0.50, 0.90}
        );

        Runner runner = new Runner(adb, termux, sleeper, new Random(42), Clock.systemUTC(), stopFile, config, log);
        StopReason reason = runner.run();

        assertEquals(StopReason.COMPLETED, reason);
    }
}

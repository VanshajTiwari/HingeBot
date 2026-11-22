package com.hingebot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class CliTest {

    @Test
    public void testUnknownFlagReturnsExitCode1() {
        int code = Cli.run(new String[]{"--invalid-flag"}, new FakeCommandRunner(), new FakeSleeper(), Clock.systemDefaultZone());
        assertEquals(1, code);
    }

    @Test
    public void testInvalidConfigValueReturnsExitCode1() {
        int code = Cli.run(new String[]{"--target", "invalid_host:5555"}, new FakeCommandRunner(), new FakeSleeper(), Clock.systemDefaultZone());
        assertEquals(1, code);
    }

    @Test
    public void testAdbExceptionReturnsExitCode2(@TempDir Path tempDir) {
        FakeCommandRunner runner = new FakeCommandRunner();
        runner.setDefaultResult(new CommandRunner.Result(1, "", "Device connection failed", false));
        FakeSleeper sleeper = new FakeSleeper();

        Path configFile = tempDir.resolve("config.properties");
        try {
            java.nio.file.Files.writeString(configFile, "start_delay_s=0\ndry_run=true\n");
        } catch (Exception ignored) {}

        int code = Cli.run(new String[]{"--config", configFile.toString()}, runner, sleeper, Clock.systemDefaultZone());
        assertEquals(2, code);
    }

    @Test
    public void testPreflightExceptionReturnsExitCode3(@TempDir Path tempDir) {
        FakeCommandRunner runner = new FakeCommandRunner();

        // adb connect success, adb devices success, but get-state fails or dumpsys window shows different app
        final int[] calls = {0};
        CommandRunner customRunner = (cmd, timeoutMs) -> {
            String full = String.join(" ", cmd);
            if (full.contains("adb connect")) {
                return new CommandRunner.Result(0, "connected", "", false);
            } else if (full.contains("adb devices")) {
                return new CommandRunner.Result(0, "localhost:5555\tdevice", "", false);
            } else if (full.contains("dumpsys power")) {
                return new CommandRunner.Result(0, "mWakefulness=Awake", "", false);
            } else if (full.contains("dumpsys window")) {
                return new CommandRunner.Result(0, "mCurrentFocus=Window{123 com.other.app}\n", "", false);
            }
            return new CommandRunner.Result(0, "", "", false);
        };

        FakeSleeper sleeper = new FakeSleeper();
        Path configFile = tempDir.resolve("config.properties");
        try {
            java.nio.file.Files.writeString(configFile, "start_delay_s=0\ndry_run=true\n");
        } catch (Exception ignored) {}

        int code = Cli.run(new String[]{"--config", configFile.toString()}, customRunner, sleeper, Clock.systemDefaultZone());
        assertEquals(3, code);
    }

    @Test
    public void testOutOfLikesReturnsExitCode4(@TempDir Path tempDir) {
        CommandRunner customRunner = (cmd, timeoutMs) -> {
            String full = String.join(" ", cmd);
            if (full.contains("adb connect")) {
                return new CommandRunner.Result(0, "connected", "", false);
            } else if (full.contains("adb devices")) {
                return new CommandRunner.Result(0, "localhost:5555\tdevice", "", false);
            } else if (full.contains("dumpsys power")) {
                return new CommandRunner.Result(0, "mWakefulness=Awake", "", false);
            } else if (full.contains("dumpsys window")) {
                return new CommandRunner.Result(0, "mCurrentFocus=Window{123 u0 co.hinge.app/co.hinge.app.MainActivity}\n", "", false);
            } else if (full.contains("wm size")) {
                return new CommandRunner.Result(0, "Physical size: 1080x2400", "", false);
            } else if (full.contains("get-state")) {
                return new CommandRunner.Result(0, "device", "", false);
            } else if (full.contains("uiautomator dump")) {
                String xml = """
                    <?xml version='1.0' ?>
                    <hierarchy>
                      <node bounds="[0,0][100,100]" text="out of likes" clickable="false" package="co.hinge.app"/>
                    </hierarchy>
                    """;
                return new CommandRunner.Result(0, xml + "\nUI hierchary dumped to /dev/tty", "", false);
            }
            return new CommandRunner.Result(0, "", "", false);
        };

        FakeSleeper sleeper = new FakeSleeper();
        Path configFile = tempDir.resolve("config.properties");
        try {
            java.nio.file.Files.writeString(configFile, "start_delay_s=0\ndry_run=true\nsequence=LIKE\n");
        } catch (Exception ignored) {}

        int code = Cli.run(new String[]{"--config", configFile.toString()}, customRunner, sleeper, Clock.systemDefaultZone());
        assertEquals(4, code);
    }
}

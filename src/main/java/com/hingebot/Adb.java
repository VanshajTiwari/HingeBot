package com.hingebot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Adb {
    private final CommandRunner runner;
    private final Sleeper sleeper;
    private final String target;
    private final boolean dryRun;

    private static final Pattern SIZE_PATTERN = Pattern.compile("(\\d+)x(\\d+)");
    private static final Pattern FOCUS_PATTERN = Pattern.compile("([A-Za-z0-9_.]+)/");

    public Adb(CommandRunner runner, Sleeper sleeper, String target, boolean dryRun) {
        this.runner = Objects.requireNonNull(runner, "runner");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.target = Objects.requireNonNull(target, "target");
        this.dryRun = dryRun;
    }

    public String getTarget() {
        return target;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public String runTarget(List<String> args, long timeoutMs) {
        List<String> cmd = new ArrayList<>();
        cmd.add("adb");
        cmd.add("-s");
        cmd.add(target);
        cmd.addAll(args);
        return execute(cmd, timeoutMs);
    }

    private String execute(List<String> cmd, long timeoutMs) {
        CommandRunner.Result result = runner.run(cmd, timeoutMs);
        if (result.exitCode() == -1 && result.stderr().contains("Failed to execute")) {
            throw new Errors.AdbException("adb not found; run: pkg install android-tools");
        }
        if (result.timedOut()) {
            throw new Errors.AdbException("ADB command timed out after " + timeoutMs + "ms: " + cmd);
        }
        if (result.exitCode() != 0) {
            throw new Errors.AdbException("ADB command failed with exit code " + result.exitCode() + " (" + cmd + "): " + result.stderr() + " " + result.stdout());
        }
        return result.stdout();
    }

    public <T> T retry(Supplier<T> supplier, int tries) {
        long[] backoffs = new long[]{1000, 2000, 4000};
        Errors.AdbException lastException = null;
        for (int attempt = 0; attempt < tries; attempt++) {
            try {
                return supplier.get();
            } catch (Errors.AdbException e) {
                lastException = e;
                if (attempt < tries - 1) {
                    long sleepMs = attempt < backoffs.length ? backoffs[attempt] : 4000;
                    sleeper.sleep(sleepMs);
                }
            }
        }
        if (lastException != null) {
            throw lastException;
        }
        throw new Errors.AdbException("Operation failed after " + tries + " attempts");
    }

    public void connect() {
        Supplier<Boolean> supplier = () -> {
            CommandRunner.Result res = runner.run(List.of("adb", "connect", target), 15000);
            if (res.exitCode() == -1 && res.stderr().contains("Failed to execute")) {
                throw new Errors.AdbException("adb not found; run: pkg install android-tools");
            }
            CommandRunner.Result devRes = runner.run(List.of("adb", "devices"), 15000);
            if (devRes.exitCode() == -1 && devRes.stderr().contains("Failed to execute")) {
                throw new Errors.AdbException("adb not found; run: pkg install android-tools");
            }
            String stdout = devRes.stdout();
            if (stdout != null && isTargetConnected(stdout, target)) {
                return true;
            }
            throw new Errors.AdbException("Target " + target + " not listed as device in 'adb devices'");
        };

        try {
            retry(supplier, 3);
        } catch (Errors.AdbException e) {
            throw new Errors.AdbException("Failed to connect to ADB target '" + target + "'. " +
                "Ensure Wireless debugging is enabled on phone and port is correct. " +
                "Re-pair if necessary with: adb pair localhost:<PAIRING_PORT> and adb connect localhost:<CONNECT_PORT>", e);
        }
    }

    public static boolean isTargetConnected(String devicesOutput, String target) {
        if (devicesOutput == null) return false;
        for (String line : devicesOutput.split("\n")) {
            line = line.trim();
            if (line.startsWith(target)) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2 && "device".equalsIgnoreCase(parts[1])) {
                    return true;
                }
            }
        }
        return false;
    }

    public void checkDevice() {
        String state = runTarget(List.of("get-state"), 5000).trim();
        if (!"device".equalsIgnoreCase(state)) {
            throw new Errors.AdbException("Device state is '" + state + "', expected 'device'");
        }
    }

    public int[] screenSize() {
        String output = runTarget(List.of("shell", "wm", "size"), 5000);
        String overrideLine = null;
        String physicalLine = null;
        for (String line : output.split("\n")) {
            if (line.contains("Override size:")) {
                overrideLine = line;
            } else if (line.contains("Physical size:")) {
                physicalLine = line;
            }
        }
        String targetLine = overrideLine != null ? overrideLine : physicalLine;
        if (targetLine == null) {
            targetLine = output;
        }
        Matcher matcher = SIZE_PATTERN.matcher(targetLine);
        if (matcher.find()) {
            int w = Integer.parseInt(matcher.group(1));
            int h = Integer.parseInt(matcher.group(2));
            return new int[]{w, h};
        }
        throw new Errors.AdbException("Could not parse screen size from: " + output);
    }

    public String foregroundPackage() {
        String output = runTarget(List.of("shell", "dumpsys", "window"), 10000);
        for (String line : output.split("\n")) {
            if (line.contains("mCurrentFocus") || line.contains("mFocusedApp")) {
                Matcher matcher = FOCUS_PATTERN.matcher(line);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        }
        return "";
    }

    public boolean isScreenOnAndUnlocked() {
        String powerOutput = runTarget(List.of("shell", "dumpsys", "power"), 5000);
        boolean screenOn = powerOutput.contains("mWakefulness=Awake")
            || powerOutput.contains("mWakefulness=Awakened")
            || powerOutput.contains("Display Power: state=ON")
            || powerOutput.contains("mHoldingDisplaySuspendBlocker=true");

        String windowOutput = runTarget(List.of("shell", "dumpsys", "window"), 5000);
        boolean keyguardShowing = windowOutput.contains("isKeyguardShowing=true")
            || windowOutput.contains("mShowingLockscreen=true")
            || windowOutput.contains("mKeyguardShowing=true");

        return screenOn && !keyguardShowing;
    }

    public String dumpUi() {
        Supplier<String> supplier = () -> {
            String raw = runTarget(List.of("exec-out", "uiautomator", "dump", "/dev/tty"), 15000);
            String cleaned = stripUiDumpTrailer(raw);
            if (cleaned.isEmpty() || (!cleaned.startsWith("<?xml") && !cleaned.startsWith("<hierarchy"))) {
                throw new Errors.AdbException("UI dump output was empty or malformed XML: " + cleaned);
            }
            return cleaned;
        };

        Errors.AdbException lastException = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return supplier.get();
            } catch (Errors.AdbException e) {
                lastException = e;
                if (attempt < 2) {
                    sleeper.sleep(500);
                }
            }
        }
        throw lastException != null ? lastException : new Errors.AdbException("Failed to dump UI XML");
    }

    public static String stripUiDumpTrailer(String raw) {
        if (raw == null) return "";
        int idx = raw.indexOf("UI hierchary dumped to");
        if (idx < 0) {
            idx = raw.indexOf("UI hierarchy dumped to");
        }
        if (idx >= 0) {
            raw = raw.substring(0, idx);
        }
        return raw.trim();
    }

    public void tap(int x, int y) {
        if (dryRun) {
            System.out.println("[dry-run] tap " + x + "," + y);
            return;
        }
        retry(() -> {
            runTarget(List.of("shell", "input", "tap", String.valueOf(x), String.valueOf(y)), 5000);
            return null;
        }, 3);
    }

    public void back() {
        if (dryRun) {
            System.out.println("[dry-run] back keyevent");
            return;
        }
        retry(() -> {
            runTarget(List.of("shell", "input", "keyevent", "4"), 5000);
            return null;
        }, 3);
    }
}

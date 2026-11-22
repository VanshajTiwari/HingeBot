package com.hingebot;

import java.util.List;

public class Termux {
    private final CommandRunner runner;

    public Termux(CommandRunner runner) {
        this.runner = runner;
    }

    public void wakeLock() {
        try {
            CommandRunner.Result result = runner.run(List.of("termux-wake-lock"), 5000);
            if (result.exitCode() != 0) {
                System.err.println("Warning: termux-wake-lock returned non-zero exit code: " + result.stderr());
            }
        } catch (Exception e) {
            System.err.println("Warning: termux-wake-lock failed: " + e.getMessage());
        }
    }

    public void wakeUnlock() {
        try {
            CommandRunner.Result result = runner.run(List.of("termux-wake-unlock"), 5000);
            if (result.exitCode() != 0) {
                System.err.println("Warning: termux-wake-unlock returned non-zero exit code: " + result.stderr());
            }
        } catch (Exception e) {
            System.err.println("Warning: termux-wake-unlock failed: " + e.getMessage());
        }
    }

    public void notify(String title, String content) {
        try {
            CommandRunner.Result result = runner.run(List.of("termux-notification", "--title", title, "--content", content), 5000);
            if (result.exitCode() != 0) {
                System.err.println("Warning: termux-notification returned non-zero exit code: " + result.stderr());
            }
        } catch (Exception e) {
            System.err.println("Warning: termux-notification failed: " + e.getMessage());
        }
    }
}

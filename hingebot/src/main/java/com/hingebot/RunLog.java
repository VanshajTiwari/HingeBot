package com.hingebot;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

public class RunLog {
    private final Path logDir;
    private final Clock clock;
    private BufferedWriter writer;

    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final DateTimeFormatter FILE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    public RunLog(Path logDir) {
        this(logDir, Clock.systemDefaultZone());
    }

    public RunLog(Path logDir, Clock clock) {
        this.logDir = Objects.requireNonNull(logDir, "logDir");
        this.clock = Objects.requireNonNull(clock, "clock");
        initWriter();
    }

    private void initWriter() {
        try {
            Files.createDirectories(logDir);
            LocalDateTime now = LocalDateTime.now(clock);
            String filename = "run-" + now.format(FILE_FORMATTER) + ".jsonl";
            Path logFile = logDir.resolve(filename);
            writer = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Warning: Failed to initialize JSONL log writer: " + e.getMessage());
            writer = null;
        }
    }

    public synchronized void logEvent(int cycle, int step, int totalSteps, Action action, String status, int total, int likes, int skips, boolean dryRun) {
        LocalDateTime now = LocalDateTime.now(clock);
        String timestamp = now.format(ISO_FORMATTER);

        String consoleLine = String.format("%s cycle=%d step=%d/%d action=%s status=%s total=%d likes=%d",
            timestamp, cycle, step, totalSteps, action.name(), status, total, likes);
        System.out.println(consoleLine);

        if (writer != null) {
            String jsonStr = String.format(
                "{\"timestamp\":\"%s\",\"cycle\":%d,\"step\":%d,\"total_steps\":%d,\"action\":\"%s\",\"status\":\"%s\",\"total\":%d,\"likes\":%d,\"skips\":%d,\"dry_run\":%b}\n",
                escapeJson(timestamp), cycle, step, totalSteps, action.name(), escapeJson(status), total, likes, skips, dryRun
            );
            try {
                writer.write(jsonStr);
                writer.flush();
            } catch (IOException e) {
                System.err.println("Warning: Failed to write event to JSONL log: " + e.getMessage());
            }
        }
    }

    public synchronized void logSummary(int cyclesDone, int totalLikes, int totalSkips, long durationMs, String stopReason) {
        double seconds = durationMs / 1000.0;
        String summary = String.format("Run finished: cycles=%d, likes=%d, skips=%d, duration=%.1fs, stop_reason=%s",
            cyclesDone, totalLikes, totalSkips, seconds, stopReason);
        System.out.println(summary);

        if (writer != null) {
            String jsonStr = String.format(
                "{\"event\":\"summary\",\"cycles_done\":%d,\"total_likes\":%d,\"total_skips\":%d,\"duration_ms\":%d,\"stop_reason\":\"%s\"}\n",
                cyclesDone, totalLikes, totalSkips, durationMs, escapeJson(stopReason)
            );
            try {
                writer.write(jsonStr);
                writer.flush();
            } catch (IOException e) {
                System.err.println("Warning: Failed to write summary to JSONL log: " + e.getMessage());
            }
        }
    }

    public synchronized void close() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {}
            writer = null;
        }
    }

    private static String escapeJson(String raw) {
        if (raw == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}

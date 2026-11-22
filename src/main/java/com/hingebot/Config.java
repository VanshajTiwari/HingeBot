package com.hingebot;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public record Config(
    String adbTarget,
    List<Action> sequence,
    List<String> rawSequence,
    int cycles,
    int maxTotalActions,
    int maxLikes,
    boolean dryRun,
    int startDelayS,
    int[] delayMs,
    int[] cyclePauseMs,
    int postActionSettleMs,
    String appPackage,
    boolean useFallbackRatios,
    String stopFile,
    String logDir,
    List<String> labelsLike,
    List<String> labelsSkip,
    List<String> labelsSendLike,
    List<String> labelsOutOfLikes,
    double[] fallbackLike,
    double[] fallbackSkip,
    double[] fallbackSendLike
) {
    public static Config defaults() {
        return new Config(
            "localhost:5555",
            List.of(Action.LIKE, Action.LIKE, Action.SKIP, Action.SKIP, Action.SKIP),
            List.of("LIKE", "LIKE", "SKIP", "SKIP", "SKIP"),
            1,
            40,
            8,
            true,
            15,
            new int[]{2500, 6000},
            new int[]{4000, 9000},
            1200,
            "co.hinge.app",
            false,
            expandUserHome("~/hingebot.stop"),
            "logs",
            List.of("Like", "Heart"),
            List.of("Skip", "Remove", "Pass"),
            List.of("Send like", "Send Like"),
            List.of("out of likes", "no likes left", "run out of likes"),
            new double[]{0.88, 0.70},
            new double[]{0.12, 0.92},
            new double[]{0.50, 0.90}
        );
    }

    public static String expandUserHome(String path) {
        if (path == null) return null;
        if (path.startsWith("~/") || path.equals("~")) {
            String userHome = System.getProperty("user.home", ".");
            return Paths.get(userHome, path.substring(1)).toAbsolutePath().normalize().toString();
        }
        return path;
    }

    public static Config load(Path configPath, Map<String, String> cliOverrides, List<String> warnings) {
        Properties props = new Properties();
        if (configPath != null) {
            if (Files.exists(configPath)) {
                try (InputStream in = Files.newInputStream(configPath)) {
                    props.load(in);
                } catch (Exception e) {
                    warnings.add("Failed to read config file " + configPath + ": " + e.getMessage());
                }
            } else {
                warnings.add("Config file not found: " + configPath);
            }
        }

        Set<String> knownKeys = Set.of(
            "adb_target", "sequence", "cycles", "max_total_actions", "max_likes",
            "dry_run", "start_delay_s", "delay_ms", "cycle_pause_ms", "post_action_settle_ms",
            "app_package", "use_fallback_ratios", "stop_file", "log_dir",
            "labels.like", "labels.skip", "labels.send_like", "labels.out_of_likes",
            "fallback.like", "fallback.skip", "fallback.send_like"
        );

        for (String key : props.stringPropertyNames()) {
            if (!knownKeys.contains(key)) {
                warnings.add("Unknown key in config file: " + key);
            }
        }

        Config def = defaults();

        String adbTarget = cliOverrides.getOrDefault("adb_target", props.getProperty("adb_target", def.adbTarget()));

        String seqStr = props.getProperty("sequence");
        List<Action> sequence = def.sequence();
        List<String> rawSequence = def.rawSequence();
        if (seqStr != null && !seqStr.isBlank()) {
            String[] parts = seqStr.split(",");
            rawSequence = new ArrayList<>();
            sequence = new ArrayList<>();
            for (String p : parts) {
                String token = p.trim();
                if (!token.isEmpty()) {
                    rawSequence.add(token);
                    try {
                        sequence.add(Action.valueOf(token.toUpperCase(Locale.ROOT)));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        }

        int cycles = parseSubInt(cliOverrides.get("cycles"), props.getProperty("cycles"), def.cycles());
        int maxTotalActions = parseSubInt(null, props.getProperty("max_total_actions"), def.maxTotalActions());
        int maxLikes = parseSubInt(null, props.getProperty("max_likes"), def.maxLikes());

        boolean dryRun = def.dryRun();
        if (cliOverrides.containsKey("dry_run")) {
            dryRun = Boolean.parseBoolean(cliOverrides.get("dry_run"));
        } else if (props.containsKey("dry_run")) {
            dryRun = Boolean.parseBoolean(props.getProperty("dry_run"));
        }

        int startDelayS = parseSubInt(cliOverrides.get("start_delay_s"), props.getProperty("start_delay_s"), def.startDelayS());
        int[] delayMs = parseIntArray(props.getProperty("delay_ms"), def.delayMs());
        int[] cyclePauseMs = parseIntArray(props.getProperty("cycle_pause_ms"), def.cyclePauseMs());
        int postActionSettleMs = parseSubInt(null, props.getProperty("post_action_settle_ms"), def.postActionSettleMs());
        String appPackage = props.getProperty("app_package", def.appPackage());
        boolean useFallbackRatios = Boolean.parseBoolean(props.getProperty("use_fallback_ratios", String.valueOf(def.useFallbackRatios())));
        String stopFile = expandUserHome(props.getProperty("stop_file", def.stopFile()));
        String logDir = props.getProperty("log_dir", def.logDir());

        List<String> labelsLike = parseStringList(props.getProperty("labels.like"), def.labelsLike());
        List<String> labelsSkip = parseStringList(props.getProperty("labels.skip"), def.labelsSkip());
        List<String> labelsSendLike = parseStringList(props.getProperty("labels.send_like"), def.labelsSendLike());
        List<String> labelsOutOfLikes = parseStringList(props.getProperty("labels.out_of_likes"), def.labelsOutOfLikes());

        double[] fallbackLike = parseDoubleArray(props.getProperty("fallback.like"), def.fallbackLike());
        double[] fallbackSkip = parseDoubleArray(props.getProperty("fallback.skip"), def.fallbackSkip());
        double[] fallbackSendLike = parseDoubleArray(props.getProperty("fallback.send_like"), def.fallbackSendLike());

        return new Config(
            adbTarget, sequence, rawSequence, cycles, maxTotalActions, maxLikes, dryRun, startDelayS,
            delayMs, cyclePauseMs, postActionSettleMs, appPackage, useFallbackRatios,
            stopFile, logDir, labelsLike, labelsSkip, labelsSendLike, labelsOutOfLikes,
            fallbackLike, fallbackSkip, fallbackSendLike
        );
    }

    private static int parseSubInt(String cliVal, String propVal, int defaultVal) {
        String val = cliVal != null ? cliVal : propVal;
        if (val == null || val.isBlank()) return defaultVal;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static int[] parseIntArray(String propVal, int[] defaultVal) {
        if (propVal == null || propVal.isBlank()) return defaultVal;
        String[] parts = propVal.split(",");
        try {
            int[] res = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                res[i] = Integer.parseInt(parts[i].trim());
            }
            return res;
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static double[] parseDoubleArray(String propVal, double[] defaultVal) {
        if (propVal == null || propVal.isBlank()) return defaultVal;
        String[] parts = propVal.split(",");
        try {
            double[] res = new double[parts.length];
            for (int i = 0; i < parts.length; i++) {
                res[i] = Double.parseDouble(parts[i].trim());
            }
            return res;
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }

    private static List<String> parseStringList(String propVal, List<String> defaultVal) {
        if (propVal == null || propVal.isBlank()) return defaultVal;
        String[] parts = propVal.split(",");
        List<String> list = new ArrayList<>();
        for (String p : parts) {
            if (!p.trim().isEmpty()) {
                list.add(p.trim());
            }
        }
        return list;
    }
}

package com.hingebot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ConfigValidator {

    public static void validate(Config config, List<String> warnings) {
        List<String> errors = new ArrayList<>();

        // 1. Sequence validation
        if (config.rawSequence() == null || config.rawSequence().isEmpty()) {
            errors.add("sequence must be non-empty");
        } else {
            for (String item : config.rawSequence()) {
                String norm = item.trim().toUpperCase(Locale.ROOT);
                if (!norm.equals("LIKE") && !norm.equals("SKIP")) {
                    errors.add("sequence item must be LIKE or SKIP, found: '" + item + "'");
                }
            }
        }

        // 2. Delay ranges validation
        validateDelayRange("delay_ms", config.delayMs(), errors);
        validateDelayRange("cycle_pause_ms", config.cyclePauseMs(), errors);

        // 3. Fallback ratios validation
        validateRatios("fallback.like", config.fallbackLike(), errors);
        validateRatios("fallback.skip", config.fallbackSkip(), errors);
        validateRatios("fallback.send_like", config.fallbackSendLike(), errors);

        // 4. Numeric bounds validation
        if (config.maxTotalActions() < 1) {
            errors.add("max_total_actions must be >= 1, found: " + config.maxTotalActions());
        }
        if (config.maxLikes() < 0) {
            errors.add("max_likes must be >= 0, found: " + config.maxLikes());
        }
        if (config.cycles() < 0) {
            errors.add("cycles must be >= 0, found: " + config.cycles());
        } else if (config.cycles() == 0 && warnings != null) {
            warnings.add("Warning: cycles is set to 0 (infinite running)");
        }
        if (config.startDelayS() < 0) {
            errors.add("start_delay_s must be >= 0, found: " + config.startDelayS());
        }

        // 5. adb_target validation (HOST:PORT, HOST must be localhost or 127.0.0.1 ONLY, port 1-65535)
        String target = config.adbTarget();
        if (target == null || target.isBlank()) {
            errors.add("adb_target must not be empty");
        } else {
            int lastColon = target.lastIndexOf(':');
            if (lastColon <= 0 || lastColon == target.length() - 1) {
                errors.add("adb_target must match HOST:PORT format, found: '" + target + "'");
            } else {
                String host = target.substring(0, lastColon);
                String portStr = target.substring(lastColon + 1);
                if (!host.equalsIgnoreCase("localhost") && !host.equals("127.0.0.1")) {
                    errors.add("adb_target host must be localhost or 127.0.0.1 ONLY, found: '" + host + "'");
                }
                try {
                    int port = Integer.parseInt(portStr);
                    if (port < 1 || port > 65535) {
                        errors.add("adb_target port must be between 1 and 65535, found: " + port);
                    }
                } catch (NumberFormatException e) {
                    errors.add("adb_target port must be a valid number, found: '" + portStr + "'");
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new Errors.ConfigValidationException(errors);
        }
    }

    private static void validateDelayRange(String name, int[] range, List<String> errors) {
        if (range == null || range.length != 2) {
            errors.add(name + " must contain exactly 2 integers [min, max]");
        } else {
            int min = range[0];
            int max = range[1];
            if (min < 1000) {
                errors.add(name + " min value must be >= 1000 ms, found: " + min);
            }
            if (min > max) {
                errors.add(name + " min value (" + min + ") cannot be greater than max value (" + max + ")");
            }
        }
    }

    private static void validateRatios(String name, double[] ratios, List<String> errors) {
        if (ratios == null || ratios.length != 2) {
            errors.add(name + " must contain exactly 2 numbers [x_ratio, y_ratio]");
        } else {
            for (int i = 0; i < ratios.length; i++) {
                double r = ratios[i];
                if (r < 0.0 || r > 1.0) {
                    errors.add(name + " ratio at index " + i + " must be between 0.0 and 1.0, found: " + r);
                }
            }
        }
    }
}

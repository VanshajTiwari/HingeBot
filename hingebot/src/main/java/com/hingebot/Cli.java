package com.hingebot;

import java.nio.file.Path;
import java.time.Clock;
import java.util.*;

public class Cli {

    public static void main(String[] args) {
        int code = run(args);
        System.exit(code);
    }

    public static int run(String[] args) {
        return run(args, new ProcessCommandRunner(), new Sleeper.RealSleeper(), Clock.systemDefaultZone());
    }

    public static int run(String[] args, CommandRunner runner, Sleeper sleeper, Clock clock) {
        String configPathStr = "config.properties";
        Map<String, String> cliOverrides = new HashMap<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--config" -> {
                    if (i + 1 >= args.length) {
                        printUsage("Missing value for --config");
                        return ExitCode.INVALID_CONFIG_OR_USAGE.getCode();
                    }
                    configPathStr = args[++i];
                }
                case "--dry-run" -> cliOverrides.put("dry_run", "true");
                case "--live" -> cliOverrides.put("dry_run", "false");
                case "--cycles" -> {
                    if (i + 1 >= args.length) {
                        printUsage("Missing value for --cycles");
                        return ExitCode.INVALID_CONFIG_OR_USAGE.getCode();
                    }
                    cliOverrides.put("cycles", args[++i]);
                }
                case "--target" -> {
                    if (i + 1 >= args.length) {
                        printUsage("Missing value for --target");
                        return ExitCode.INVALID_CONFIG_OR_USAGE.getCode();
                    }
                    cliOverrides.put("adb_target", args[++i]);
                }
                case "--start-delay" -> {
                    if (i + 1 >= args.length) {
                        printUsage("Missing value for --start-delay");
                        return ExitCode.INVALID_CONFIG_OR_USAGE.getCode();
                    }
                    cliOverrides.put("start_delay_s", args[++i]);
                }
                default -> {
                    printUsage("Unknown argument: " + arg);
                    return ExitCode.INVALID_CONFIG_OR_USAGE.getCode();
                }
            }
        }

        List<String> warnings = new ArrayList<>();
        Config config;
        try {
            Path path = Path.of(configPathStr);
            config = Config.load(path, cliOverrides, warnings);
            ConfigValidator.validate(config, warnings);
        } catch (Errors.ConfigValidationException e) {
            System.err.println(e.getMessage());
            return ExitCode.INVALID_CONFIG_OR_USAGE.getCode();
        } catch (Exception e) {
            System.err.println("Configuration error: " + e.getMessage());
            return ExitCode.INVALID_CONFIG_OR_USAGE.getCode();
        }

        for (String w : warnings) {
            System.err.println("Warning: " + w);
        }

        Adb adb = new Adb(runner, sleeper, config.adbTarget(), config.dryRun());
        Termux termux = new Termux(runner);
        RunLog log = new RunLog(Path.of(config.logDir()), clock);
        Runner r = new Runner(adb, termux, sleeper, new Random(), clock, Path.of(config.stopFile()), config, log);

        try {
            r.run();
            return ExitCode.SUCCESS.getCode();
        } catch (Errors.ConfigValidationException e) {
            System.err.println(e.getMessage());
            return ExitCode.INVALID_CONFIG_OR_USAGE.getCode();
        } catch (Errors.AdbException e) {
            System.err.println("ADB error: " + e.getMessage());
            return ExitCode.ADB_ERROR.getCode();
        } catch (Errors.PreflightException | Errors.AppLostException | Errors.TargetNotFoundException | Errors.ActionFailedException e) {
            System.err.println("Action / preflight error: " + e.getMessage());
            return ExitCode.ACTION_OR_PREFLIGHT_FAILURE.getCode();
        } catch (Errors.OutOfLikesException e) {
            System.err.println("Daily like limit reached: " + e.getMessage());
            return ExitCode.OUT_OF_LIKES.getCode();
        } catch (Throwable t) {
            System.err.println("Unexpected error occurred: " + t.getMessage());
            t.printStackTrace(System.err);
            return ExitCode.UNEXPECTED_ERROR.getCode();
        }
    }

    private static void printUsage(String errorMsg) {
        if (errorMsg != null && !errorMsg.isBlank()) {
            System.err.println("Error: " + errorMsg);
        }
        System.err.println("Usage: hingebot [--config PATH] [--dry-run|--live] [--cycles N] [--target HOST:PORT] [--start-delay SECONDS]");
    }
}

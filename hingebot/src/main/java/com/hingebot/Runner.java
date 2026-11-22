package com.hingebot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class Runner {
    private final Adb adb;
    private final Termux termux;
    private final Sleeper sleeper;
    private final Random random;
    private final Clock clock;
    private final Path stopFilePath;
    private final Config config;
    private final RunLog log;

    private final AtomicBoolean stopFlag = new AtomicBoolean(false);

    public Runner(Adb adb, Termux termux, Sleeper sleeper, Random random, Clock clock, Path stopFilePath, Config config, RunLog log) {
        this.adb = Objects.requireNonNull(adb, "adb");
        this.termux = Objects.requireNonNull(termux, "termux");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.random = Objects.requireNonNull(random, "random");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.stopFilePath = Objects.requireNonNull(stopFilePath, "stopFilePath");
        this.config = Objects.requireNonNull(config, "config");
        this.log = Objects.requireNonNull(log, "log");
    }

    public AtomicBoolean getStopFlag() {
        return stopFlag;
    }

    public StopReason run() {
        long startTime = clock.millis();
        int totalActions = 0;
        int totalLikes = 0;
        int totalSkips = 0;
        int cyclesCompleted = 0;
        StopReason stopReason = null;

        Thread mainThread = Thread.currentThread();
        Thread shutdownHook = new Thread(() -> {
            stopFlag.set(true);
            try {
                mainThread.join(20000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (mainThread.isAlive()) {
                Runtime.getRuntime().halt(130);
            }
        });

        try {
            // Step 0: Start
            try {
                Files.deleteIfExists(stopFilePath);
            } catch (Exception ignored) {}

            termux.wakeLock();

            int countdown = config.startDelayS();
            System.out.println("Switch to Hinge now. Starting in " + countdown + " seconds...");
            if (!config.dryRun()) {
                System.out.println("LIVE MODE: this will send real likes. Stop with Ctrl+C or `touch ~/hingebot.stop`.");
            }

            for (int i = countdown; i > 0; i--) {
                if (stopFlag.get() || Files.exists(stopFilePath)) {
                    break;
                }
                if (i < countdown && (i % 5 == 0 || i <= 3)) {
                    System.out.println("Starting in " + i + " seconds...");
                }
                sleeper.sleep(1000);
            }

            try {
                Runtime.getRuntime().addShutdownHook(shutdownHook);
            } catch (Exception ignored) {}

            // Step 1: Preflight
            adb.connect();

            if (!adb.isScreenOnAndUnlocked()) {
                throw new Errors.PreflightException("Screen is off or locked");
            }

            String fg = adb.foregroundPackage();
            if (!config.appPackage().equalsIgnoreCase(fg)) {
                throw new Errors.PreflightException("Hinge app (" + config.appPackage() + ") is not in foreground. Found: " + fg);
            }

            int[] sz = adb.screenSize();
            int width = sz[0];
            int height = sz[1];

            // Step 2: Loop cycles
            int maxCycles = config.cycles();
            int currentCycle = 0;

            while (true) {
                if (stopFlag.get()) {
                    stopReason = StopReason.STOP_FLAG;
                    break;
                }
                if (Files.exists(stopFilePath)) {
                    stopReason = StopReason.STOP_FILE;
                    break;
                }

                if (maxCycles > 0 && currentCycle >= maxCycles) {
                    stopReason = StopReason.COMPLETED;
                    break;
                }

                currentCycle++;
                List<Action> seq = config.sequence();
                int seqSize = seq.size();

                for (int stepIdx = 0; stepIdx < seqSize; stepIdx++) {
                    Action stepAction = seq.get(stepIdx);

                    if (stopFlag.get()) {
                        stopReason = StopReason.STOP_FLAG;
                        break;
                    }
                    if (Files.exists(stopFilePath)) {
                        stopReason = StopReason.STOP_FILE;
                        break;
                    }
                    if (totalActions >= config.maxTotalActions()) {
                        stopReason = StopReason.MAX_TOTAL_ACTIONS;
                        break;
                    }
                    if (stepAction == Action.LIKE && totalLikes >= config.maxLikes()) {
                        stopReason = StopReason.MAX_LIKES;
                        break;
                    }

                    adb.checkDevice();

                    String fgNow = adb.foregroundPackage();
                    if (!config.appPackage().equalsIgnoreCase(fgNow)) {
                        throw new Errors.AppLostException("Foreground package changed to " + fgNow);
                    }

                    String xml = adb.dumpUi();
                    List<Node> nodes = UiParser.parse(xml);

                    if (stepAction == Action.LIKE && UiParser.containsText(nodes, config.labelsOutOfLikes())) {
                        stopReason = StopReason.OUT_OF_LIKES;
                        log.logEvent(currentCycle, stepIdx + 1, seqSize, stepAction, "out_of_likes", totalActions, totalLikes, totalSkips, config.dryRun());
                        throw new Errors.OutOfLikesException("daily like limit reached");
                    }

                    if (stepAction == Action.SKIP) {
                        Optional<Node> skipNode = UiParser.find(nodes, config.labelsSkip(), true);
                        int tapX, tapY;
                        if (skipNode.isPresent()) {
                            int[] c = UiParser.center(skipNode.get());
                            tapX = c[0];
                            tapY = c[1];
                        } else if (config.useFallbackRatios()) {
                            tapX = (int) (width * config.fallbackSkip()[0]);
                            tapY = (int) (height * config.fallbackSkip()[1]);
                        } else {
                            throw new Errors.TargetNotFoundException("Skip button not found");
                        }

                        String fpBefore = UiParser.fingerprint(nodes);
                        adb.tap(tapX, tapY);
                        sleeper.sleep(config.postActionSettleMs());

                        String xmlAfter = adb.dumpUi();
                        List<Node> nodesAfter = UiParser.parse(xmlAfter);
                        String fpAfter = UiParser.fingerprint(nodesAfter);

                        if (fpBefore.equals(fpAfter)) {
                            // Retry tap ONCE
                            adb.tap(tapX, tapY);
                            sleeper.sleep(config.postActionSettleMs());
                            String xmlRetry = adb.dumpUi();
                            List<Node> nodesRetry = UiParser.parse(xmlRetry);
                            String fpRetry = UiParser.fingerprint(nodesRetry);
                            if (fpBefore.equals(fpRetry)) {
                                throw new Errors.ActionFailedException("SKIP action failed: fingerprint unchanged after retry");
                            }
                        }

                        totalActions++;
                        totalSkips++;
                        log.logEvent(currentCycle, stepIdx + 1, seqSize, Action.SKIP, "ok", totalActions, totalLikes, totalSkips, config.dryRun());

                    } else if (stepAction == Action.LIKE) {
                        Optional<Node> likeNode = UiParser.find(nodes, config.labelsLike(), true);
                        int tapX, tapY;
                        if (likeNode.isPresent()) {
                            int[] c = UiParser.center(likeNode.get());
                            tapX = c[0];
                            tapY = c[1];
                        } else if (config.useFallbackRatios()) {
                            tapX = (int) (width * config.fallbackLike()[0]);
                            tapY = (int) (height * config.fallbackLike()[1]);
                        } else {
                            throw new Errors.TargetNotFoundException("Like button not found");
                        }

                        String fpBefore = UiParser.fingerprint(nodes);
                        adb.tap(tapX, tapY);
                        sleeper.sleep(1000);

                        String sheetXml = adb.dumpUi();
                        List<Node> sheetNodes = UiParser.parse(sheetXml);
                        Optional<Node> sendLikeNode = UiParser.find(sheetNodes, config.labelsSendLike(), true);

                        if (sendLikeNode.isEmpty()) {
                            // 2nd attempt
                            sleeper.sleep(1000);
                            sheetXml = adb.dumpUi();
                            sheetNodes = UiParser.parse(sheetXml);
                            sendLikeNode = UiParser.find(sheetNodes, config.labelsSendLike(), true);
                        }

                        if (sendLikeNode.isEmpty()) {
                            adb.back();
                            throw new Errors.ActionFailedException("Send Like button did not appear");
                        }

                        int sendX, sendY;
                        int[] sendC = UiParser.center(sendLikeNode.get());
                        sendX = sendC[0];
                        sendY = sendC[1];

                        adb.tap(sendX, sendY);
                        sleeper.sleep(config.postActionSettleMs());

                        String xmlAfter = adb.dumpUi();
                        List<Node> nodesAfter = UiParser.parse(xmlAfter);
                        String fpAfter = UiParser.fingerprint(nodesAfter);

                        if (fpBefore.equals(fpAfter)) {
                            throw new Errors.ActionFailedException("LIKE action failed: fingerprint unchanged after send_like");
                        }

                        totalActions++;
                        totalLikes++;
                        log.logEvent(currentCycle, stepIdx + 1, seqSize, Action.LIKE, "ok", totalActions, totalLikes, totalSkips, config.dryRun());
                    }

                    // Inter-step random pause
                    int minD = config.delayMs()[0];
                    int maxD = config.delayMs()[1];
                    int delay = minD == maxD ? minD : minD + random.nextInt(maxD - minD + 1);
                    sleeper.sleep(delay);
                }

                cyclesCompleted = currentCycle;
                if (stopReason != null) {
                    break;
                }

                // Inter-cycle random pause
                int minCP = config.cyclePauseMs()[0];
                int maxCP = config.cyclePauseMs()[1];
                int cyclePause = minCP == maxCP ? minCP : minCP + random.nextInt(maxCP - minCP + 1);
                sleeper.sleep(cyclePause);
            }

        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (Exception ignored) {}

            termux.wakeUnlock();
            String reasonDesc = stopReason != null ? stopReason.getDescription() : "stopped";
            termux.notify("hingebot", reasonDesc + ", likes=" + totalLikes + " skips=" + totalSkips);

            long durationMs = clock.millis() - startTime;
            log.logSummary(cyclesCompleted, totalLikes, totalSkips, durationMs, reasonDesc);
            log.close();
        }

        return stopReason != null ? stopReason : StopReason.COMPLETED;
    }
}

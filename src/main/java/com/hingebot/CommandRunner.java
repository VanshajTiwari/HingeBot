package com.hingebot;

import java.util.List;

public interface CommandRunner {
    record Result(int exitCode, String stdout, String stderr, boolean timedOut) {}
    Result run(List<String> cmd, long timeoutMs);
}

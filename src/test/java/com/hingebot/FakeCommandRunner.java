package com.hingebot;

import java.util.ArrayList;
import java.util.List;

public class FakeCommandRunner implements CommandRunner {
    private final List<List<String>> executedCommands = new ArrayList<>();
    private CommandRunner.Result defaultResult = new CommandRunner.Result(0, "", "", false);

    public void setDefaultResult(CommandRunner.Result result) {
        this.defaultResult = result;
    }

    @Override
    public Result run(List<String> cmd, long timeoutMs) {
        executedCommands.add(cmd);
        return defaultResult;
    }

    public List<List<String>> getExecutedCommands() {
        return executedCommands;
    }
}

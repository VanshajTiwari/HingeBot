package com.hingebot;

import java.util.ArrayList;
import java.util.List;

public class FakeSleeper implements Sleeper {
    private final List<Long> sleptTimes = new ArrayList<>();

    @Override
    public void sleep(long ms) {
        sleptTimes.add(ms);
    }

    public List<Long> getSleptTimes() {
        return sleptTimes;
    }

    public long getTotalSleptMs() {
        return sleptTimes.stream().mapToLong(Long::longValue).sum();
    }
}

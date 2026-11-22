package com.hingebot;

public interface Sleeper {
    void sleep(long ms);

    class RealSleeper implements Sleeper {
        @Override
        public void sleep(long ms) {
            if (ms <= 0) return;
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}

package com.hingebot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ProcessCommandRunner implements CommandRunner {
    @Override
    public Result run(List<String> cmd, long timeoutMs) {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            return new Result(-1, "", "Failed to execute command " + cmd + ": " + e.getMessage(), false);
        }

        ByteArrayOutputStream outStream = new ByteArrayOutputStream();
        ByteArrayOutputStream errStream = new ByteArrayOutputStream();

        Thread outThread = new Thread(() -> copyStream(process.getInputStream(), outStream));
        Thread errThread = new Thread(() -> copyStream(process.getErrorStream(), errStream));

        outThread.start();
        errThread.start();

        boolean finished;
        try {
            finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result(-1, outStream.toString(), errStream.toString() + "\nInterrupted", true);
        }

        if (!finished) {
            process.destroyForcibly();
            try {
                outThread.join(500);
                errThread.join(500);
            } catch (InterruptedException ignored) {}
            return new Result(-1, outStream.toString(), errStream.toString() + "\nCommand timed out after " + timeoutMs + "ms", true);
        }

        try {
            outThread.join(500);
            errThread.join(500);
        } catch (InterruptedException ignored) {}

        return new Result(process.exitValue(), outStream.toString(), errStream.toString(), false);
    }

    private void copyStream(InputStream in, ByteArrayOutputStream out) {
        byte[] buffer = new byte[4096];
        int n;
        try {
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
        } catch (IOException ignored) {}
    }
}

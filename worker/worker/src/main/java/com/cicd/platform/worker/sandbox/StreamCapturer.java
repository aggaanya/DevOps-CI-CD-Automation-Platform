package com.cicd.platform.worker.sandbox;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Bounded async capturer for a process stream. Prevents memory exhaustion
 * from a chatty command while still recording stdout/stderr.
 */
public final class StreamCapturer implements Runnable {

    private final InputStream input;
    private final long maxBytes;
    private final StringBuilder content = new StringBuilder();
    private final Thread thread;

    private StreamCapturer(InputStream input, long maxBytes, String name) {
        this.input = input;
        this.maxBytes = maxBytes;
        this.thread = new Thread(this, name);
        this.thread.setDaemon(true);
    }

    public static StreamCapturer start(InputStream input, long maxBytes, String name) {
        StreamCapturer capturer = new StreamCapturer(input, maxBytes, name);
        capturer.thread.start();
        return capturer;
    }

    @Override
    public void run() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                append(line);
            }
        } catch (IOException e) {
            // stream closed by process termination - acceptable
        }
    }

    private synchronized void append(String line) {
        if (content.length() < maxBytes) {
            content.append(line).append(System.lineSeparator());
        } else if (content.length() < maxBytes + 32) {
            content.append("[output truncated]").append(System.lineSeparator());
        }
    }

    public synchronized String getContent() {
        return content.toString();
    }

    public void join(long millis) throws InterruptedException {
        thread.join(millis);
    }

    public void join() throws InterruptedException {
        thread.join();
    }
}

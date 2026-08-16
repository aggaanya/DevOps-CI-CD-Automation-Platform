package com.cicd.platform.worker.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * Structured result of a single command execution.
 *
 * @param exitCode   process exit code (-1 when the process could not start).
 * @param status     {@link CommandStatus}.
 * @param stdout     captured standard output (bounded).
 * @param stderr     captured standard error (bounded).
 * @param startedAt  execution start instant.
 * @param completedAt execution end instant.
 * @param durationMs wall clock duration in milliseconds.
 * @param timedOut   whether the command was terminated by the timeout guard.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandResult(
        int exitCode,
        CommandStatus status,
        String stdout,
        String stderr,
        Instant startedAt,
        Instant completedAt,
        long durationMs,
        boolean timedOut) {

    public static CommandResult success(int exitCode, String stdout, String stderr,
                                        Instant startedAt, Instant completedAt) {
        return new CommandResult(exitCode, CommandStatus.SUCCESS, stdout, stderr,
                startedAt, completedAt, durationMs(startedAt, completedAt), false);
    }

    public static CommandResult failed(int exitCode, String stdout, String stderr,
                                       Instant startedAt, Instant completedAt) {
        return new CommandResult(exitCode, CommandStatus.FAILED, stdout, stderr,
                startedAt, completedAt, durationMs(startedAt, completedAt), false);
    }

    public static CommandResult timedOut(String stdout, String stderr,
                                         Instant startedAt, Instant completedAt) {
        return new CommandResult(-1, CommandStatus.TIMED_OUT, stdout, stderr,
                startedAt, completedAt, durationMs(startedAt, completedAt), true);
    }

    private static long durationMs(Instant start, Instant end) {
        return end == null || start == null ? 0L : Math.max(0L, end.toEpochMilli() - start.toEpochMilli());
    }
}

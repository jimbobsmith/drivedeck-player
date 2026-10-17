package com.drivedeck.player.spike;

import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** No libVLC/JNA is loaded here. Native failure can only take down the child process. */
final class WorkerSupervisor {
    private static final int RESULT_LIMIT = 64 * 1024;
    private static final int LOG_LIMIT = 1024 * 1024;
    record Outcome(JSONObject report, int exitCode) { }

    static Outcome run(List<String> command, Duration deadline, Path log) throws IOException, InterruptedException {
        var process = new ProcessBuilder(command).start();
        var lastStage = new AtomicReference<>("worker_start");
        var stdout = new ByteArrayOutputStream();
        var readFailure = new AtomicReference<IOException>();
        var resultReader = Thread.ofPlatform().daemon().start(() -> {
            try (var input = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    // Keep draining after the cap so a broken child cannot block the supervisor.
                    if (stdout.size() <= RESULT_LIMIT) stdout.write(buffer, 0, count);
                }
            } catch (IOException failure) { readFailure.set(failure); }
        });
        var logReader = Thread.ofPlatform().daemon().start(() -> {
            try (var input = process.getErrorStream(); var output = Files.newOutputStream(log)) {
                byte[] buffer = new byte[4096];
                int count, written = 0;
                var line = new StringBuilder();
                while ((count = input.read(buffer)) != -1) {
                    if (written < LOG_LIMIT) {
                        int kept = Math.min(count, LOG_LIMIT - written);
                        output.write(buffer, 0, kept);
                        written += kept;
                    }
                    for (int i = 0; i < count; i++) {
                        char c = (char) buffer[i];
                        if (c == '\n') {
                            String value = line.toString().strip();
                            if (value.matches("DD_STAGE [a-z_]{1,40}")) lastStage.set(value.substring(9));
                            line.setLength(0);
                        } else if (line.length() < 256) line.append(c);
                    }
                }
            } catch (IOException failure) { readFailure.set(failure); }
        });
        try {
            if (!process.waitFor(deadline.toMillis(), TimeUnit.MILLISECONDS)) {
                terminate(process);
                return failure("WorkerTimeout", lastStage.get(), process.exitValue());
            }
            resultReader.join(2000);
            logReader.join(2000);
            if (resultReader.isAlive() || logReader.isAlive() || readFailure.get() != null) {
                return failure("WorkerOutputFailure", lastStage.get(), process.exitValue());
            }
            // A worker may print success and then crash in native shutdown. Exit status wins.
            if (process.exitValue() != 0) {
                // Preserve only a well-formed, explicit failure report from the controlled worker.
                if (stdout.size() <= RESULT_LIMIT) {
                    try {
                        var report = parseReport(stdout.toString(StandardCharsets.UTF_8));
                        if ("failed".equals(report.optString("result"))) {
                            report.put("processExitCode", process.exitValue()).put("nativeProcessIsolated", true);
                            return new Outcome(report, 1);
                        }
                    } catch (RuntimeException ignored) { }
                }
                return failure("NativeProcessFailure", lastStage.get(), process.exitValue());
            }
            if (stdout.size() > RESULT_LIMIT) return failure("WorkerProtocolFailure", lastStage.get(), 0);
            try {
                var report = parseReport(stdout.toString(StandardCharsets.UTF_8));
                if (!"passed".equals(report.optString("result")) || !report.optBoolean("cleanShutdown")
                        || !"complete".equals(report.optString("stage"))) {
                    return failure("WorkerProtocolFailure", lastStage.get(), 0);
                }
                report.put("processExitCode", 0).put("nativeProcessIsolated", true);
                return new Outcome(report, 0);
            } catch (RuntimeException invalid) {
                return failure("WorkerProtocolFailure", lastStage.get(), 0);
            }
        } finally {
            // Also handles thread interruption/cancellation by a future UI owner.
            if (process.isAlive()) terminate(process);
            process.getInputStream().close();
            process.getErrorStream().close();
            process.getOutputStream().close();
        }
    }

    private static JSONObject parseReport(String value) {
        var tokens = new JSONTokener(value);
        var report = new JSONObject(tokens);
        if (tokens.nextClean() != 0) throw new IllegalArgumentException("Trailing worker output");
        return report;
    }

    private static Outcome failure(String type, String stage, int processExit) {
        return new Outcome(new JSONObject().put("schemaVersion", 2).put("result", "failed")
                .put("errorType", type).put("stage", stage).put("processExitCode", processExit)
                .put("nativeProcessIsolated", true), 1);
    }

    private static void terminate(Process process) throws InterruptedException {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IllegalStateException("WorkerTerminationFailed");
    }
}

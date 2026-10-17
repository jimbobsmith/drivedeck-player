package com.drivedeck.player.spike;

import org.junit.Test;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class WorkerSupervisorTest {
    private List<String> command(String mode, Path pidFile) throws Exception {
        var classes = Path.of(FakeWorker.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", classes.toString(), FakeWorker.class.getName(), mode, pidFile.toString());
    }
    private WorkerSupervisor.Outcome probe(String mode) throws Exception {
        var scratch = Files.createTempDirectory(Path.of("D:/tmp/dd"), "player-supervisor-test-");
        return WorkerSupervisor.run(command(mode, scratch.resolve("pid")), Duration.ofSeconds(5), scratch.resolve("native.log"));
    }
    @Test public void cleanWorkerCanPass() throws Exception {
        var result = probe("pass");
        assertEquals(0, result.exitCode());
        assertTrue(result.report().getBoolean("nativeProcessIsolated"));
    }
    @Test public void crashCannotPublishSuccess() throws Exception {
        var result = probe("crashAfterSuccess");
        assertEquals(1, result.exitCode());
        assertEquals("NativeProcessFailure", result.report().getString("errorType"));
        assertEquals(37, result.report().getInt("processExitCode"));
    }
    @Test public void malformedResultFails() throws Exception {
        assertEquals("WorkerProtocolFailure", probe("invalid").report().getString("errorType"));
    }
    @Test public void trailingOutputFails() throws Exception {
        assertEquals("WorkerProtocolFailure", probe("trailing").report().getString("errorType"));
    }
    @Test public void missingCleanShutdownFails() throws Exception {
        assertEquals("WorkerProtocolFailure", probe("unclean").report().getString("errorType"));
    }
    @Test public void oversizedResultFailsWithoutDeadlock() throws Exception {
        assertEquals("WorkerProtocolFailure", probe("overflow").report().getString("errorType"));
    }
    @Test public void workerFailureRemainsFailure() throws Exception {
        assertEquals("ProviderFailure", probe("provider").report().getString("errorType"));
    }
    @Test public void deadlineKillsHungWorker() throws Exception {
        var scratch = Files.createTempDirectory(Path.of("D:/tmp/dd"), "player-supervisor-test-");
        var pid = scratch.resolve("pid");
        var result = WorkerSupervisor.run(command("hang", pid), Duration.ofSeconds(2), scratch.resolve("native.log"));
        assertEquals("WorkerTimeout", result.report().getString("errorType"));
        assertFalse(ProcessHandle.of(Long.parseLong(Files.readString(pid))).map(ProcessHandle::isAlive).orElse(false));
    }
    @Test public void cancellationKillsHungWorker() throws Exception {
        var scratch = Files.createTempDirectory(Path.of("D:/tmp/dd"), "player-supervisor-test-");
        var pid = scratch.resolve("pid");
        var command = command("hang", pid);
        var completed = new CountDownLatch(1);
        var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var thread = new Thread(() -> {
            try { WorkerSupervisor.run(command, Duration.ofSeconds(30), scratch.resolve("native.log")); }
            catch (InterruptedException expected) { }
            catch (Throwable unexpected) { failure.set(unexpected); }
            finally { completed.countDown(); }
        });
        thread.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!Files.exists(pid) && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(Files.exists(pid));
        thread.interrupt();
        assertTrue(completed.await(8, TimeUnit.SECONDS));
        assertNull(failure.get());
        assertFalse(ProcessHandle.of(Long.parseLong(Files.readString(pid))).map(ProcessHandle::isAlive).orElse(false));
    }
}

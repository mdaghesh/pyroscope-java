package io.pyroscope.javaagent.ondemand;

import io.pyroscope.javaagent.ProfilerDelegate;
import io.pyroscope.javaagent.config.Config;
import io.pyroscope.javaagent.Snapshot;
import io.pyroscope.javaagent.api.Exporter;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;

public class OnDemandProfilingController {
    private final Config config;
    private final AtomicBoolean isProfileActive = new AtomicBoolean(false);
    private final AtomicReference<ProfilerDelegate> profilerRef = new AtomicReference<>();
    private final AtomicReference<Exporter> exporterRef = new AtomicReference<>();
    private volatile Instant profilingStartTime;
    private volatile CompletableFuture<Void> currentProfilingSession;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public OnDemandProfilingController(Config config) {
        this.config = config;
    }

    public void setProfiler(ProfilerDelegate profiler) {
        this.profilerRef.set(profiler);
    }

    public void setExporter(Exporter exporter) {
        this.exporterRef.set(exporter);
    }

    public synchronized ProfilingResult startProfiling(long durationSeconds) {
        if (isProfileActive.get()) {
            return new ProfilingResult(false, "Profiling session already active");
        }

        ProfilerDelegate profiler = profilerRef.get();
        if (profiler == null) {
            return new ProfilingResult(false, "Profiler not initialized");
        }

        isProfileActive.set(true);
        profilingStartTime = Instant.now();

        profiler.start();

        currentProfilingSession = CompletableFuture.runAsync(() -> {
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(durationSeconds));
                stopAndExport();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                isProfileActive.set(false);
            }
        });

        return new ProfilingResult(true, "Profiling started for " + durationSeconds + " seconds");
    }

    public synchronized ProfilingResult stopProfiling() {
        if (!isProfileActive.get()) {
            return new ProfilingResult(false, "No active profiling session");
        }

        if (currentProfilingSession != null && !currentProfilingSession.isDone()) {
            currentProfilingSession.cancel(true);
        }

        try {
            stopAndExport();
            return new ProfilingResult(true, "Profiling stopped and data sent");
        } catch (Exception e) {
            return new ProfilingResult(false, "Failed to stop profiling: " + e.getMessage());
        } finally {
            isProfileActive.set(false);
        }
    }

    private void stopAndExport() {
        ProfilerDelegate profiler = profilerRef.get();
        Exporter exporter = exporterRef.get();

        if (profiler != null && exporter != null) {
            profiler.stop();
            Instant endTime = Instant.now();
            Snapshot snapshot = profiler.dumpProfile(profilingStartTime, endTime);

            if (snapshot != null) {
                exporter.export(snapshot);
            }
        }
    }

    public boolean isProfilingActive() {
        return isProfileActive.get();
    }

    public void shutdown() {
        scheduler.shutdown();
    }

    public static class ProfilingResult {
        public final boolean success;
        public final String message;

        public ProfilingResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
package io.pyroscope.javaagent.ondemand;

import io.pyroscope.javaagent.ProfilerDelegate;
import io.pyroscope.javaagent.Snapshot;
import io.pyroscope.javaagent.api.Exporter;
import io.pyroscope.javaagent.config.Config;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Controls the lifecycle of an on‑demand profiling session.  It supports
 * starting and stopping the profiler on demand, scheduling a single
 * export at the end of a session or periodic exports at a configurable
 * upload interval.  When a session is started the profiler runs until
 * either the specified duration elapses or {@link #stopProfiling()}
 * is called.  If an upload interval greater than zero is configured,
 * snapshots are exported periodically during the session; otherwise
 * only a single snapshot is exported when the session ends.
 */
public class OnDemandProfilingController {
    private final Config config;
    private final AtomicBoolean isProfileActive = new AtomicBoolean(false);
    private final AtomicReference<ProfilerDelegate> profilerRef = new AtomicReference<>();
    private final AtomicReference<Exporter> exporterRef = new AtomicReference<>();
    private volatile Instant profilingStartTime;
    /**
     * Handle to the scheduled task that will stop the profiling session after
     * the requested duration.  This task runs on the same scheduler
     * thread as the periodic exports to avoid concurrency.
     */
    private volatile ScheduledFuture<?> timeoutTask;

    /**
     * Scheduler used to run periodic export tasks.  It is created lazily
     * when the controller is constructed.
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    /**
     * Handle to the currently scheduled periodic export task.  This is
     * non‑null only while a session is active and periodic exports are
     * enabled.
     */
    private volatile ScheduledFuture<?> periodicTask;
    /**
     * Upload interval in seconds.  When greater than zero, snapshots are
     * exported at this interval during a session.  A value of zero or
     * negative disables periodic exports and only a single snapshot is
     * exported when the session ends.
     */
    private final long uploadIntervalSeconds;

    public OnDemandProfilingController(Config config) {
        this(config, 0);
    }

    /**
     * Constructs a controller with a custom upload interval.  The interval
     * should be specified in seconds.  If the interval is less than or
     * equal to zero, periodic exports are disabled.
     *
     * @param config configuration for the agent
     * @param uploadIntervalSeconds interval between periodic exports in
     *                              seconds, or {@code 0} to disable periodic
     *                              exports
     */
    public OnDemandProfilingController(Config config, long uploadIntervalSeconds) {
        this.config = config;
        this.uploadIntervalSeconds = uploadIntervalSeconds;
    }

    /**
     * Assigns the profiler delegate.  The profiler is not started until
     * {@link #startProfiling(long)} is called.
     */
    public void setProfiler(ProfilerDelegate profiler) {
        profilerRef.set(profiler);
    }

    /**
     * Assigns the exporter used to send snapshots to the server.  Must be
     * non‑null before a profiling session can be started.
     */
    public void setExporter(Exporter exporter) {
        exporterRef.set(exporter);
    }

    /**
     * Starts a profiling session for the given duration.  If a session is
     * already active, returns a {@link ProfilingResult} indicating that
     * profiling is already running.  Otherwise it starts the profiler,
     * records the current time, schedules a task to stop profiling after
     * the requested duration, and optionally schedules periodic exports.
     *
     * @param durationSeconds how long the profiling session should run in
     *                        seconds.  A value less than or equal to zero
     *                        runs indefinitely until {@link #stopProfiling()} is
     *                        called.
     * @return result of the start request
     */
    public synchronized ProfilingResult startProfiling(long durationSeconds) {
        if (isProfileActive.get()) {
            return new ProfilingResult(false, "Profiling session already active");
        }
        ProfilerDelegate profiler = profilerRef.get();
        if (profiler == null) {
            return new ProfilingResult(false, "Profiler not initialized");
        }
        Exporter exporter = exporterRef.get();
        if (exporter == null) {
            return new ProfilingResult(false, "Exporter not initialized");
        }
        isProfileActive.set(true);
        profilingStartTime = Instant.now();
        profiler.start();
        // schedule the session end if duration is positive
        if (durationSeconds > 0) {
            timeoutTask = scheduler.schedule(() -> {
                // call stopProfiling() from the scheduler thread to avoid
                // concurrent stop attempts.
                stopProfiling();
            }, durationSeconds, TimeUnit.SECONDS);
        }
        // schedule periodic exports if configured
        if (uploadIntervalSeconds > 0) {
            periodicTask = scheduler.scheduleAtFixedRate(() -> {
                try {
                    Instant now = Instant.now();
                    profiler.stop();
                    Snapshot snapshot = profiler.dumpProfile(profilingStartTime, now);
                    if (snapshot != null) {
                        System.out.println("OnDemandProfilingController: Exporting snapshot at " + now);
                        exporter.export(snapshot);
                    }
                    profilingStartTime = now;
                    profiler.start();
                } catch (Throwable t) {
                    // swallow and log the exception; periodic exporting should not crash the agent
                    t.printStackTrace();
                }
            }, uploadIntervalSeconds, uploadIntervalSeconds, TimeUnit.SECONDS);
        }
        return new ProfilingResult(true, "Profiling started for " + (durationSeconds > 0 ? durationSeconds + " seconds" : "an indefinite period"));
    }

    /**
     * Stops an active profiling session.  If no session is active, returns
     * a {@link ProfilingResult} indicating there is nothing to stop.
     * Otherwise cancels the scheduled tasks, stops the profiler, exports
     * the final snapshot, and resets the active flag.
     *
     * @return result of the stop request
     */
    public synchronized ProfilingResult stopProfiling() {
        if (!isProfileActive.get()) {
            return new ProfilingResult(false, "No active profiling session");
        }
        // cancel the scheduled timeout task if it exists
        if (timeoutTask != null && !timeoutTask.isDone()) {
            timeoutTask.cancel(false);
            timeoutTask = null;
        }
        ProfilingResult result;
        try {
            stopAndExport();
            result = new ProfilingResult(true, "Profiling stopped and data sent");
        } catch (Exception e) {
            result = new ProfilingResult(false, "Failed to stop profiling: " + e.getMessage());
        } finally {
            isProfileActive.set(false);
        }
        return result;
    }

    /**
     * Helper that stops the profiler, cancels the periodic task, dumps
     * the profile data for the elapsed period, and exports it.  This method
     * is used by both the scheduled timeout task and the explicit stop
     * method.  It assumes that {@link #isProfileActive} has already been
     * set to {@code false} by the caller.
     */
    private void stopAndExport() {
        // cancel periodic exports
        if (periodicTask != null) {
            periodicTask.cancel(false);
            periodicTask = null;
        }
        ProfilerDelegate profiler = profilerRef.get();
        Exporter exporter = exporterRef.get();
        if (profiler != null && exporter != null) {
            profiler.stop();
            Instant endTime = Instant.now();
            Snapshot snapshot = profiler.dumpProfile(profilingStartTime, endTime);
            if (snapshot != null) {
                System.out.println("OnDemandProfilingController: Exporting final snapshot at " + endTime);
                exporter.export(snapshot);
            }
        }
    }

    /**
     * Returns whether a profiling session is currently active.
     */
    public boolean isProfilingActive() {
        return isProfileActive.get();
    }

    /**
     * Shut down the scheduler to free resources when the JVM exits.
     */
    public void shutdown() {
        scheduler.shutdown();
    }

    /** Simple value type returned by start/stop methods. */
    public static class ProfilingResult {
        public final boolean success;
        public final String message;
        public ProfilingResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }
}
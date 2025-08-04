package io.pyroscope.javaagent.ondemand;

import io.pyroscope.javaagent.PyroscopeAgent;
import io.pyroscope.javaagent.ProfilerDelegate;
import io.pyroscope.javaagent.api.Exporter;
import io.pyroscope.javaagent.api.Logger;
import io.pyroscope.javaagent.config.Config;
import io.pyroscope.javaagent.impl.DefaultLogger;
import io.pyroscope.javaagent.impl.OnDemandScheduler;
import io.pyroscope.javaagent.impl.PyroscopeExporter;
import io.pyroscope.javaagent.impl.QueuedExporter;

// extra imports for HTTP and signal triggers
import io.pyroscope.javaagent.ondemand.HttpProfilingServer;
import io.pyroscope.javaagent.ondemand.SignalProfilingHandler;

/**
 * Entry point for on‑demand profiling.  When invoked with a configuration
 * where {@code onDemandMode} is {@code true}, this class constructs a
 * {@link OnDemandScheduler} and passes it to the normal {@link PyroscopeAgent}
 * so that profiling only begins when explicitly triggered.
 */
public class OnDemandPyroscopeAgent {
    /**
     * Use the same log level configured for the agent to respect user settings.
     */
    private static Logger defaultLogger(Config cfg) {
        return new DefaultLogger(cfg.logLevel, System.err);
    }

    /**
     * Starts the agent in on‑demand mode if enabled on the config.  If
     * {@code config.onDemandMode} is false, this method delegates back to
     * {@link PyroscopeAgent#start(Config)} so that the regular continuous
     * scheduler is used.
     *
     * @param config loaded configuration
     */
    public static void start(Config config) {
        // If on‑demand mode is not enabled, delegate back to the standard agent.
        if (!config.onDemandMode) {
            PyroscopeAgent.start(config);
            return;
        }

        // Log the beginning of on‑demand startup.
        Logger logger = defaultLogger(config);
        logger.log(Logger.Level.INFO, "Starting Pyroscope in on‑demand mode");

        // Create controller that will manage profiling sessions
        OnDemandProfilingController controller = new OnDemandProfilingController(config);

        Exporter baseExporter = new PyroscopeExporter(config, logger);
        Exporter exporter = new QueuedExporter(config, baseExporter, logger);
        controller.setExporter(exporter);

        // Scheduler that does not start the profiler until requested
        OnDemandScheduler scheduler = new OnDemandScheduler(controller);

        // Create profiler delegate based off config (no start here)
        ProfilerDelegate profiler = ProfilerDelegate.create(config);

        // Build options with our custom scheduler and exporter
        PyroscopeAgent.Options options = new PyroscopeAgent.Options.Builder(config)
            .setScheduler(scheduler)
            .setExporter(exporter)
            .setLogger(logger)
            .setProfiler(profiler)
            .build();

        // Start the agent; scheduler.start() will only register the profiler
        PyroscopeAgent.start(options);

        // Log that on‑demand mode is active
        logger.log(Logger.Level.INFO, "On‑demand profiling started; waiting for triggers");

        // Start HTTP server for remote triggers if enabled.  Catch Throwable to
        // gracefully handle linkage errors when the HTTP server is missing (e.g. JDK
        // without jdk.httpserver module).
        if (config.httpTriggerEnabled) {
            try {
                HttpProfilingServer httpServer = new HttpProfilingServer(controller, config.httpPort);
                httpServer.start();
                logger.log(Logger.Level.INFO, "HTTP trigger enabled on port %s", config.httpPort);
            } catch (Throwable e) {
                logger.log(Logger.Level.ERROR, "Failed to start HTTP trigger", e);
            }
        } else {
            logger.log(Logger.Level.INFO, "HTTP trigger disabled");
        }

        // Register signal handlers for SIGUSR1/SIGUSR2 if enabled.  Catch Throwable
        // because sun.misc.Signal may be unavailable or unsupported on some
        // platforms.
        if (config.signalTriggerEnabled) {
            try {
                SignalProfilingHandler signalHandler = new SignalProfilingHandler(controller);
                signalHandler.registerSignalHandlers();
                logger.log(Logger.Level.INFO, "Signal trigger enabled (SIGUSR1 to start, SIGUSR2 to stop)");
            } catch (Throwable t) {
                logger.log(Logger.Level.ERROR, "Failed to start signal trigger", t);
            }
        } else {
            logger.log(Logger.Level.INFO, "Signal trigger disabled");
        }

        // Ensure controller shuts down gracefully on JVM exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.log(Logger.Level.INFO, "Shutting down on‑demand profiling agent");
            controller.shutdown();
        }));
    }
}
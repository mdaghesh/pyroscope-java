package io.pyroscope.javaagent.ondemand;

import io.pyroscope.javaagent.PyroscopeAgent;
import io.pyroscope.javaagent.ProfilerDelegate;
import io.pyroscope.javaagent.config.Config;
import io.pyroscope.javaagent.api.Logger;
import io.pyroscope.javaagent.api.Exporter;
import io.pyroscope.javaagent.impl.DefaultLogger;
import io.pyroscope.javaagent.impl.OnDemandScheduler;
import io.pyroscope.javaagent.impl.PyroscopeExporter;
import io.pyroscope.javaagent.impl.QueuedExporter;

public class OnDemandPyroscopeAgent {
    private static final Logger DEFAULT_LOGGER = new DefaultLogger(Logger.Level.INFO, System.err);

    public static void start(Config config) {
        if (!config.onDemandMode) {
            PyroscopeAgent.start(config);
            return;
        }

        OnDemandProfilingController controller = new OnDemandProfilingController(config);

        Logger logger = DEFAULT_LOGGER;
        Exporter baseExporter = new PyroscopeExporter(config, logger);
        Exporter exporter = new QueuedExporter(config, baseExporter, logger);

        controller.setExporter(exporter);

        OnDemandScheduler scheduler = new OnDemandScheduler(controller);

        ProfilerDelegate profiler = ProfilerDelegate.create(config);

        PyroscopeAgent.Options options = new PyroscopeAgent.Options.Builder(config)
            .setScheduler(scheduler)
            .setExporter(exporter)
            .setLogger(logger)
            .setProfiler(profiler)
            .build();

        PyroscopeAgent.start(options);

        if (config.httpTriggerEnabled) {
            try {
                HttpProfilingServer httpServer =
                    new HttpProfilingServer(controller, config.httpPort);
                httpServer.start();
                logger.log(Logger.Level.INFO, "HTTP profiling server started on port %s",
                    config.httpPort);
            } catch (Exception e) {
                logger.log(Logger.Level.ERROR, "Failed to start HTTP server", e);
            }
        }

        if (config.signalTriggerEnabled) {
            SignalProfilingHandler signalHandler =
                new SignalProfilingHandler(controller);
            signalHandler.registerSignalHandlers();
            logger.log(Logger.Level.INFO,
                "Signal handlers registered (SIGUSR1 to start, SIGUSR2 to stop)");
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.log(Logger.Level.INFO, "Shutting down on-demand profiling agent");
            controller.shutdown();
        }));
    }
}
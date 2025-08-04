package io.pyroscope.javaagent;

import io.pyroscope.javaagent.api.Exporter;
import io.pyroscope.javaagent.api.Logger;
import io.pyroscope.javaagent.api.ProfilingScheduler;
import io.pyroscope.javaagent.config.Config;
import io.pyroscope.javaagent.impl.*;
import io.pyroscope.javaagent.ondemand.OnDemandPyroscopeAgent;
import io.pyroscope.labels.v2.ScopedContext;
import org.jetbrains.annotations.NotNull;

import java.lang.instrument.Instrumentation;

import static io.pyroscope.Preconditions.checkNotNull;

/**
 * Modified version of PyroscopeAgent that integrates the on‑demand profiling
 * support. If {@code config.onDemandMode} is enabled, the agent delegates
 * start‑up to {@link OnDemandPyroscopeAgent} which configures an
 * {@link io.pyroscope.javaagent.impl.OnDemandScheduler}.  Otherwise it
 * behaves like the upstream continuous profiling agent.
 */
public class PyroscopeAgent {
    private static final Object sLock = new Object();
    private static Options sOptions = null;

    /**
     * JVM entry point.  Reads configuration and delegates to either the
     * continuous or on‑demand agent based on the {@code onDemandMode} flag.
     */
    public static void premain(final String agentArgs,
        final Instrumentation inst) {
        final Config config;
        try {
            config = Config.build(DefaultConfigurationProvider.INSTANCE);
            DefaultLogger.PRECONFIG_LOGGER.log(Logger.Level.DEBUG, "Config: %s", config);
        } catch (final Throwable e) {
            DefaultLogger.PRECONFIG_LOGGER.log(Logger.Level.ERROR, "Error starting profiler %s", e);
            return;
        }
        if (config.onDemandMode) {
            try {
                OnDemandPyroscopeAgent.start(config);
            } catch (Throwable t) {
                DefaultLogger.PRECONFIG_LOGGER.log(Logger.Level.ERROR,
                    "On‑demand agent failed to start; falling back to continuous mode", t);
                start(config); // fallback
            }
        } else {
            start(config);
        }
    }

    /**
     * Starts the agent with default configuration.  If on‑demand mode is
     * configured, this method redirects to the on‑demand agent instead of
     * starting a continuous scheduler.
     */
    public static void start() {
        start(new Config.Builder().build());
    }

    public static void start(@NotNull Config config) {
        checkNotNull(config, "config");
        // honour on‑demand mode when start() is called directly
        if (config.onDemandMode) {
            try {
                OnDemandPyroscopeAgent.start(config);
            } catch (Throwable t) {
                DefaultLogger.PRECONFIG_LOGGER.log(Logger.Level.ERROR,
                    "On‑demand agent failed to start", t);
            }
            return;
        }
        start(new Options.Builder(config).build());
    }

    public static void start(@NotNull Options options) {
        checkNotNull(options, "options");

        synchronized (sLock) {
            Logger logger = options.logger;

            if (!options.config.agentEnabled) {
                logger.log(Logger.Level.INFO, "Pyroscope agent start disabled by configuration");
                return;
            }

            if (sOptions != null) {
                logger.log(Logger.Level.ERROR, "Failed to start profiling - already started");
                return;
            }
            sOptions = options;
            logger.log(Logger.Level.DEBUG, "Config: %s", options.config);
            try {
                options.scheduler.start(options.profiler);
                ScopedContext.ENABLED.set(true);
                logger.log(Logger.Level.INFO, "Profiling started");
            } catch (final Throwable e) {
                logger.log(Logger.Level.ERROR, "Error starting profiler %s", e);
                sOptions = null;
            }
        }
    }

    public static void stop() {
        ScopedContext.ENABLED.set(false);
        synchronized (sLock) {
            if (sOptions == null) {
                DefaultLogger.PRECONFIG_LOGGER.log(Logger.Level.ERROR, "Error stopping profiler: not started");
                return;
            }
            try {
                sOptions.scheduler.stop();
                sOptions.exporter.stop();
                sOptions.logger.log(Logger.Level.INFO, "Profiling stopped");
            } catch (Throwable e) {
                sOptions.logger.log(Logger.Level.ERROR, "Error stopping profiler %s", e);
            }

            sOptions = null;
        }
    }

    public static boolean isStarted() {
        synchronized (sLock) {
            return sOptions != null;
        }
    }

    /**
     * Options allow to swap pyroscope components:
     *  - {@link io.pyroscope.javaagent.api.ProfilingScheduler}
     *  - {@link Logger}
     *  - {@link Exporter} for {@link ContinuousProfilingScheduler}
     */
    public static class Options {
        final Config config;
        final ProfilingScheduler scheduler;
        final Logger logger;
        final ProfilerDelegate profiler;
        final Exporter exporter;

        private Options(@NotNull Builder b) {
            this.config = b.config;
            this.profiler = b.profiler;
            this.scheduler = b.scheduler;
            this.logger = b.logger;
            this.exporter = b.exporter;
        }

        public static class Builder {
            private final Config config;
            private ProfilerDelegate profiler;
            private Exporter exporter;
            private ProfilingScheduler scheduler;
            private Logger logger;

            public Builder(@NotNull Config config) {
                checkNotNull(config, "config");
                this.config = config;
            }

            public Builder setExporter(@NotNull Exporter exporter) {
                checkNotNull(exporter, "exporter");
                this.exporter = exporter;
                return this;
            }

            public Builder setScheduler(@NotNull ProfilingScheduler scheduler) {
                checkNotNull(scheduler, "scheduler");
                this.scheduler = scheduler;
                return this;
            }

            public Builder setLogger(@NotNull Logger logger) {
                checkNotNull(logger, "logger");
                this.logger = logger;
                return this;
            }

            public Builder setProfiler(@NotNull ProfilerDelegate profiler) {
                checkNotNull(profiler, "logger");
                this.profiler = profiler;
                return this;
            }

            public @NotNull Options build() {
                if (logger == null) {
                    logger = new DefaultLogger(config.logLevel, System.err);
                }

                if (scheduler == null) {
                    if (exporter == null) {
                        exporter = new QueuedExporter(config, new PyroscopeExporter(config, logger), logger);
                    }
                    if (config.samplingDuration == null) {
                        scheduler = new ContinuousProfilingScheduler(config, exporter, logger);
                    } else {
                        scheduler = new SamplingProfilingScheduler(config, exporter, logger);
                    }
                }
                if (profiler == null) {
                    profiler = ProfilerDelegate.create(config);
                }
                return new Options(this);
            }
        }
    }
}
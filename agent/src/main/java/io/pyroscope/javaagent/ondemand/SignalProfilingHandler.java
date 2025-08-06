package io.pyroscope.javaagent.ondemand;

import sun.misc.Signal;
import java.util.logging.Logger;

/**
 * Registers signal handlers for on‑demand profiling.  SIGUSR1 starts
 * profiling for a default duration, and SIGUSR2 stops profiling.  This
 * class is separate from the controller so that signal handling logic
 * remains isolated.
 */
public class SignalProfilingHandler {
    private static final Logger logger = Logger.getLogger(SignalProfilingHandler.class.getName());
    private final OnDemandProfilingController controller;
    private final long defaultDurationSeconds = 90;

    public SignalProfilingHandler(OnDemandProfilingController controller) {
        this.controller = controller;
    }

    /**
     * Registers handlers for SIGUSR1 and SIGUSR2.  On platforms where
     * sun.misc.Signal is unavailable or the signals cannot be registered,
     * any exceptions are logged and ignored.
     */
    public void registerSignalHandlers() {
        // SIGUSR1 to start profiling
        try {
            Signal.handle(new Signal("USR1"), sig -> {
                logger.info("Received SIGUSR1 - Starting profiling");
                OnDemandProfilingController.ProfilingResult result = controller.startProfiling(defaultDurationSeconds);
                logger.info("Profiling start result: " + result.message);
            });
        } catch (Exception e) {
            logger.warning("Failed to register SIGUSR1 handler: " + e.getMessage());
        }
        // SIGUSR2 to stop profiling
        try {
            Signal.handle(new Signal("USR2"), sig -> {
                logger.info("Received SIGUSR2 - Stopping profiling");
                OnDemandProfilingController.ProfilingResult result = controller.stopProfiling();
                logger.info("Profiling stop result: " + result.message);
            });
        } catch (Exception e) {
            logger.warning("Failed to register SIGUSR2 handler: " + e.getMessage());
        }
    }
}
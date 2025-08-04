package io.pyroscope.javaagent.ondemand;

import sun.misc.Signal;
import java.util.logging.Logger;

public class SignalProfilingHandler {
    private static final Logger logger = Logger.getLogger(SignalProfilingHandler.class.getName());
    private final OnDemandProfilingController controller;
    private final long defaultDuration = 90; // seconds

    public SignalProfilingHandler(OnDemandProfilingController controller) {
        this.controller = controller;
    }

    public void registerSignalHandlers() {
        // SIGUSR1 to start profiling
        try {
            Signal.handle(new Signal("USR1"), sig -> {
                logger.info("Received SIGUSR1 - Starting profiling");
                OnDemandProfilingController.ProfilingResult result =
                    controller.startProfiling(defaultDuration);
                logger.info("Profiling start result: " + result.message);
            });
        } catch (Exception e) {
            logger.warning("Failed to register SIGUSR1 handler: " + e.getMessage());
        }

        // SIGUSR2 to stop profiling
        try {
            Signal.handle(new Signal("USR2"), sig -> {
                logger.info("Received SIGUSR2 - Stopping profiling");
                OnDemandProfilingController.ProfilingResult result =
                    controller.stopProfiling();
                logger.info("Profiling stop result: " + result.message);
            });
        } catch (Exception e) {
            logger.warning("Failed to register SIGUSR2 handler: " + e.getMessage());
        }
    }
}
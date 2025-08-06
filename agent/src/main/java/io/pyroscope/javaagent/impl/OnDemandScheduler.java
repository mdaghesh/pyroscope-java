package io.pyroscope.javaagent.impl;

import io.pyroscope.javaagent.ProfilerDelegate;
import io.pyroscope.javaagent.api.ProfilingScheduler;
import io.pyroscope.javaagent.ondemand.OnDemandProfilingController;

/**
 * A {@link ProfilingScheduler} implementation that defers all sampling
 * activity until an on‑demand profiling session is started via the
 * associated {@link OnDemandProfilingController}.  When {@link #start}
 * is invoked the profiler is registered with the controller but no
 * sampling is started.  Actual profiling is controlled entirely by
 * {@code OnDemandProfilingController.startProfiling()} and
 * {@code OnDemandProfilingController.stopProfiling()}.
 */
public class OnDemandScheduler implements ProfilingScheduler {
    private final OnDemandProfilingController controller;

    public OnDemandScheduler(OnDemandProfilingController controller) {
        this.controller = controller;
    }

    @Override
    public void start(ProfilerDelegate profiler) {
        controller.setProfiler(profiler);
    }

    @Override
    public void stop() {
        // nothing to stop here; the controller manages stop via signals/API
    }
}
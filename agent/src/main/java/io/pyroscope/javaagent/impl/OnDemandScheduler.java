package io.pyroscope.javaagent.impl;

import io.pyroscope.javaagent.ProfilerDelegate;
import io.pyroscope.javaagent.api.ProfilingScheduler;
import io.pyroscope.javaagent.ondemand.OnDemandProfilingController;

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
    }
}
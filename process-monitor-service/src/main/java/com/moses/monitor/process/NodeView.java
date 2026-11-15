package com.moses.monitor.process;

import java.time.Instant;

/**
 * A node of the flow diagram as the UI sees it: the service's definition joined with its state
 * in the environment being looked at. A service that has never reported in this environment
 * still appears, as UNKNOWN with no lastSeen.
 */
public record NodeView(String serviceName,
                       String processName,
                       String description,
                       ProcessStatus status,
                       Double cpuPercent,
                       Double memoryMb,
                       Instant lastSeen,
                       String probeUrl,
                       Instant lastProbeAt,
                       Integer lastProbeStatusCode,
                       Long lastProbeLatencyMs,
                       String lastProbeError) {

    static NodeView of(ServiceDefinition definition, ProcessState state) {
        if (state == null) {
            return new NodeView(definition.getName(), definition.getProcessName(), definition.getDescription(),
                    ProcessStatus.UNKNOWN, null, null, null, null, null, null, null, null);
        }
        return new NodeView(definition.getName(), definition.getProcessName(), definition.getDescription(),
                state.getStatus(), state.getCpuPercent(), state.getMemoryMb(), state.getLastSeen(),
                state.getProbeUrl(), state.getLastProbeAt(), state.getLastProbeStatusCode(),
                state.getLastProbeLatencyMs(), state.getLastProbeError());
    }
}

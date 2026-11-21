package com.moses.monitor.process;

/** What tripped an incident. Both are detected by counting, with no AI involved. */
public enum IncidentKind {
    /** A service's ERROR/FATAL logs jumped above its alert rule (see {@link AlertRule}). */
    ERROR_SPIKE,
    /** A service that was alive recently went DOWN (no heartbeat, or its probe fails). */
    SERVICE_DOWN,
    /** Too many requests of an approved {@link TraceFlow} didn't reach where they should, in time. */
    FLOW_BROKEN
}

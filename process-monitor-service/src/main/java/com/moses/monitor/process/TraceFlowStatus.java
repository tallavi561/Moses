package com.moses.monitor.process;

public enum TraceFlowStatus {
    /** Learned from the traces by MoSes; not checked until someone on the team approves it. */
    SUGGESTED,
    /** Approved (or added by hand): checked every minute. */
    ACTIVE,
    /** Temporarily not checked. */
    PAUSED,
    /** Turned down: never suggested again, never checked. */
    REJECTED
}

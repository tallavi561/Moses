package com.moses.monitor.process;

public enum IncidentState {
    /** The condition is still going on (or was within the last quiet window). */
    OPEN,
    /** Back to normal: closed automatically by {@link IncidentDetector}. */
    CLOSED
}

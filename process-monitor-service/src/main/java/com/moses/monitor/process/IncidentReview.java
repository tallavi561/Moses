package com.moses.monitor.process;

/** What the team (or later the agent) concluded about an incident - what makes it a library entry. */
public enum IncidentReview {
    /** Nobody has written anything about it yet. */
    UNREVIEWED,
    /** The cause was found and fixed; {@code resolution} says how. */
    SOLVED,
    /** Investigated, but no fix was found; still worth keeping so the next person starts from here. */
    UNSOLVED,
    /** Not a real problem (expected errors, a planned restart...). */
    FALSE_ALARM
}

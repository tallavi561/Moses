package com.moses.monitor.process;

/**
 * Flow direction of a Connection, relative to its stored sourceService/targetService.
 */
public enum ConnectionDirection {
    SOURCE_TO_TARGET,
    TARGET_TO_SOURCE,
    BIDIRECTIONAL
}

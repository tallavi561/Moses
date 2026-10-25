package com.moses.monitor.model;

public enum LogLevel {
    TRACE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4),
    FATAL(5);

    private final int code;

    LogLevel(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static LogLevel fromCode(int code) {
        for (LogLevel level : values()) {
            if (level.code == code) {
                return level;
            }
        }
        throw new IllegalArgumentException("Unknown log level code: " + code);
    }
}

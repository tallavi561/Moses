package com.moses.monitor.search;

import com.moses.monitor.model.LogEntry;

import java.util.List;

/** One chunk of results, newest first. The next chunk starts after the last log's (timestamp, logId). */
public record LogPage(List<LogEntry> logs, boolean hasMore) {
}

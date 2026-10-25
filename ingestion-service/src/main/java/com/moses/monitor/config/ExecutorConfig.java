package com.moses.monitor.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dedicated executor for ingestion work (tokenizing + buffering), kept
 * separate from the HTTP request threads so a burst of incoming logs never
 * blocks the web tier. Runtime is Java 21, so this uses one virtual thread
 * per submitted task instead of a bounded platform-thread pool: ingestion
 * tasks are short-lived and I/O-light (CPU tokenizing + an in-memory map
 * write), which is exactly the workload virtual threads are cheap for -
 * no pool sizing to tune, and a burst of submissions doesn't queue behind a
 * fixed pool size.
 */
@Configuration
public class ExecutorConfig {

    @Bean
    public ExecutorService ingestionExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

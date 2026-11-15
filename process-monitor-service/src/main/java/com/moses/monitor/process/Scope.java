package com.moses.monitor.process;

/**
 * The headers that say whose data a request is about. api-gateway sets them after checking the
 * caller (a project member, or a service holding the environment's API key) and strips any the
 * caller sent itself. A request without them is rejected with 400.
 */
final class Scope {

    static final String PROJECT = "X-Moses-Project-Id";
    static final String ENVIRONMENT = "X-Moses-Environment";

    private Scope() {
    }
}

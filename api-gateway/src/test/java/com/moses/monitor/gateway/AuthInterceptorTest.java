package com.moses.monitor.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthInterceptorTest {

    @Test
    void signInAndRegisterAreOpen() {
        assertTrue(AuthInterceptor.isPublic("POST", "/api/auth/login"));
        assertTrue(AuthInterceptor.isPublic("POST", "/api/auth/register"));
        assertFalse(AuthInterceptor.isPublic("GET", "/api/auth/me"));
    }

    @Test
    void monitoredServicesCanStillReportIn() {
        assertTrue(AuthInterceptor.isPublic("POST", "/api/logs"));
        assertTrue(AuthInterceptor.isPublic("POST", "/api/process/heartbeat"));
    }

    @Test
    void readingMonitoringDataNeedsASession() {
        assertFalse(AuthInterceptor.isPublic("GET", "/api/search/page"));
        assertFalse(AuthInterceptor.isPublic("GET", "/api/process/state"));
        assertFalse(AuthInterceptor.isPublic("POST", "/api/ai/query"));
        assertFalse(AuthInterceptor.isPublic("PUT", "/api/process/nodes/x"));
        assertFalse(AuthInterceptor.isPublic("DELETE", "/api/process/nodes/x"));
        assertFalse(AuthInterceptor.isPublic("POST", "/api/process/connections"));
    }

    @Test
    void onlyTheInvitePreviewIsOpen() {
        assertTrue(AuthInterceptor.isPublic("GET", "/api/invites/abc123"));
        assertFalse(AuthInterceptor.isPublic("POST", "/api/invites/abc123/accept"));
        assertFalse(AuthInterceptor.isPublic("GET", "/api/invites/abc123/accept"));
        assertFalse(AuthInterceptor.isPublic("GET", "/api/projects"));
        assertFalse(AuthInterceptor.isPublic("POST", "/api/projects"));
    }

    @Test
    void preflightIsOpen() {
        assertTrue(AuthInterceptor.isPublic("OPTIONS", "/api/search/page"));
    }
}

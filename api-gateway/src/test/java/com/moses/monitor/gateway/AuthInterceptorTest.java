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
    void reportingInIsNoLongerOpen() {
        assertFalse(AuthInterceptor.isPublic("POST", "/api/logs"));
        assertFalse(AuthInterceptor.isPublic("POST", "/api/process/heartbeat"));
    }

    @Test
    void monitoringDataIsScopedAndAccountsAreNot() {
        assertTrue(AuthInterceptor.isScoped("/api/logs"));
        assertTrue(AuthInterceptor.isScoped("/api/search/page"));
        assertTrue(AuthInterceptor.isScoped("/api/process/state"));
        assertTrue(AuthInterceptor.isScoped("/api/ai/query"));
        assertFalse(AuthInterceptor.isScoped("/api/projects"));
        assertFalse(AuthInterceptor.isScoped("/api/auth/me"));
        assertFalse(AuthInterceptor.isScoped("/api/processes"));
    }

    @Test
    void anApiKeyOnlySendsLogsAndHeartbeats() {
        assertTrue(AuthInterceptor.acceptsApiKey("POST", "/api/logs"));
        assertTrue(AuthInterceptor.acceptsApiKey("POST", "/api/process/heartbeat"));
        assertFalse(AuthInterceptor.acceptsApiKey("GET", "/api/process/state"));
        assertFalse(AuthInterceptor.acceptsApiKey("GET", "/api/search/page"));
        assertFalse(AuthInterceptor.acceptsApiKey("DELETE", "/api/process/nodes/x"));
        assertFalse(AuthInterceptor.acceptsApiKey("POST", "/api/process/connections"));
    }

    @Test
    void viewersAreStoppedOnChangesOnly() {
        assertTrue(AuthInterceptor.isChange("POST", "/api/process/heartbeat"));
        assertTrue(AuthInterceptor.isChange("PUT", "/api/process/nodes/x"));
        assertTrue(AuthInterceptor.isChange("DELETE", "/api/process/connections/1"));
        assertTrue(AuthInterceptor.isChange("POST", "/api/logs"));
        assertFalse(AuthInterceptor.isChange("GET", "/api/process/state"));
        assertFalse(AuthInterceptor.isChange("GET", "/api/search/page"));
        assertFalse(AuthInterceptor.isChange("POST", "/api/ai/query"));
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

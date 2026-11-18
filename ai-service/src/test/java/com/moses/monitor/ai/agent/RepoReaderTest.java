package com.moses.monitor.ai.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepoReaderTest {

    @Test
    void onlyGithubHttpsRepositoriesAreAccepted() {
        assertTrue(RepoReader.isAcceptedUrl("https://github.com/tallavi561/Moses"));
        assertTrue(RepoReader.isAcceptedUrl("https://github.com/tallavi561/Moses.git"));
        assertTrue(RepoReader.isAcceptedUrl("https://github.com/a-b/c.d_e/"));
        assertFalse(RepoReader.isAcceptedUrl("C:\\Users\\someone\\secrets"));
        assertFalse(RepoReader.isAcceptedUrl("file:///etc"));
        assertFalse(RepoReader.isAcceptedUrl("/home/user/repo"));
        assertFalse(RepoReader.isAcceptedUrl("http://github.com/a/b"));
        assertFalse(RepoReader.isAcceptedUrl("https://github.com.evil.com/a/b"));
        assertFalse(RepoReader.isAcceptedUrl("https://github.com/a/b/c"));
        assertFalse(RepoReader.isAcceptedUrl("https://github.com/-u/b"));
        assertFalse(RepoReader.isAcceptedUrl(null));
    }

    @Test
    void pathsCannotBecomeOptionsOrLeaveTheTree() {
        assertEquals("frontend/src", RepoReader.safePath("./frontend/src"));
        assertEquals("pom.xml", RepoReader.safePath("/pom.xml"));
        assertEquals("a/b.java", RepoReader.safePath("a\\b.java"));
        assertThrows(IllegalArgumentException.class, () -> RepoReader.safePath("--output=x"));
        assertThrows(IllegalArgumentException.class, () -> RepoReader.safePath("../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> RepoReader.safePath("a/../../b"));
        assertThrows(IllegalArgumentException.class, () -> RepoReader.safePath("HEAD:other"));
    }
}

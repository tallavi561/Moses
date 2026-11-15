package com.moses.monitor.auth;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** The membership and role checks every project endpoint starts with. */
@Component
public class ProjectGuard {

    private final MongoTemplate mongoTemplate;

    public ProjectGuard(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    /** 404 (not 403) for a project the user isn't in, so project ids can't be probed. */
    public Project requireMembership(String projectId, User user) {
        Project project = mongoTemplate.findById(projectId, Project.class);
        if (project == null || project.roleOf(user.getId()) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found");
        }
        return project;
    }

    public Project requireOwner(String projectId, User user) {
        Project project = requireMembership(projectId, user);
        if (project.roleOf(user.getId()) != Project.Role.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only the project owner can do this");
        }
        return project;
    }
}

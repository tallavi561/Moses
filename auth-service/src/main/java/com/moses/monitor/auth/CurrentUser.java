package com.moses.monitor.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Resolves the signed-in user from the request's bearer token. The token is verified here too,
 * not only at the gateway: this service has its own public URL, so a header saying "the gateway
 * already checked" could be forged by anyone calling it directly.
 */
@Component
public class CurrentUser {

    private final JwtService jwtService;
    private final MongoTemplate mongoTemplate;

    public CurrentUser(JwtService jwtService, MongoTemplate mongoTemplate) {
        this.jwtService = jwtService;
        this.mongoTemplate = mongoTemplate;
    }

    public User require(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        boolean bearer = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7);
        String userId = jwtService.verify(bearer ? header.substring(7).trim() : null);
        User user = userId == null ? null : mongoTemplate.findById(userId, User.class);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "not signed in");
        }
        return user;
    }
}

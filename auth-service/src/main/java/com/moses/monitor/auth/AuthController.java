package com.moses.monitor.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Locale;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    public record RegisterRequest(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Email @Size(max = 254) String email,
            // bcrypt only reads the first 72 bytes, so longer passwords are refused, not truncated.
            @NotBlank @Size(min = 8, max = 72) String password) { }

    public record LoginRequest(@NotBlank String email, @NotBlank String password) { }

    public record UserView(String id, String name, String email) {
        static UserView of(User user) {
            return new UserView(user.getId(), user.getName(), user.getEmail());
        }
    }

    public record SessionResponse(String token, UserView user) { }

    private final MongoTemplate mongoTemplate;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final CurrentUser currentUser;

    /** Compared against when the email is unknown, so both failure paths cost one bcrypt check. */
    private final String dummyHash;

    public AuthController(MongoTemplate mongoTemplate, PasswordEncoder passwordEncoder,
                          JwtService jwtService, CurrentUser currentUser) {
        this.mongoTemplate = mongoTemplate;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.currentUser = currentUser;
        this.dummyHash = passwordEncoder.encode("not-a-real-password");
    }

    @PostMapping("/register")
    public SessionResponse register(@Valid @RequestBody RegisterRequest request) {
        User user = new User();
        user.setName(request.name().trim());
        user.setEmail(normalizeEmail(request.email()));
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setCreatedAt(Instant.now());
        try {
            mongoTemplate.insert(user);
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "email already registered");
        }
        return new SessionResponse(jwtService.issue(user), UserView.of(user));
    }

    @PostMapping("/login")
    public SessionResponse login(@Valid @RequestBody LoginRequest request) {
        User user = mongoTemplate.findOne(
                new Query(Criteria.where("email").is(normalizeEmail(request.email()))), User.class);
        boolean hasPassword = user != null && user.getPasswordHash() != null;
        boolean matches = passwordEncoder.matches(request.password(), hasPassword ? user.getPasswordHash() : dummyHash);
        if (!hasPassword || !matches) {
            // Same answer for "no such account" and "wrong password".
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "wrong email or password");
        }
        return new SessionResponse(jwtService.issue(user), UserView.of(user));
    }

    @GetMapping("/me")
    public UserView me(HttpServletRequest request) {
        return UserView.of(currentUser.require(request));
    }

    static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}

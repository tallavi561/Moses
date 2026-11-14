package com.moses.monitor.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Projects, their members, and the two ways into a project: the owner adds a registered user
 * by email, or anyone holding an invite link joins by themselves.
 */
@RestController
public class ProjectController {

    public record CreateProjectRequest(@NotBlank @Size(max = 80) String name) { }

    public record AddMemberRequest(@NotBlank String email) { }

    public record ProjectView(String id, String name, Project.Role role, int memberCount) {
        static ProjectView of(Project project, String userId) {
            return new ProjectView(project.getId(), project.getName(), project.roleOf(userId), project.getMembers().size());
        }
    }

    public record MemberView(String userId, String name, String email, Project.Role role, Instant joinedAt) { }

    public record InviteView(String token, Instant expiresAt) { }

    public record InvitePreview(String projectName, Instant expiresAt) { }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MongoTemplate mongoTemplate;
    private final CurrentUser currentUser;
    private final Duration inviteTtl;

    public ProjectController(MongoTemplate mongoTemplate, CurrentUser currentUser,
                             @Value("${moses.invites.ttl-days}") long inviteTtlDays) {
        this.mongoTemplate = mongoTemplate;
        this.currentUser = currentUser;
        this.inviteTtl = Duration.ofDays(inviteTtlDays);
    }

    @GetMapping("/api/projects")
    public List<ProjectView> myProjects(HttpServletRequest request) {
        User user = currentUser.require(request);
        Query query = new Query(Criteria.where("members.user_id").is(user.getId()))
                .with(Sort.by(Sort.Direction.ASC, "created_at"));
        return mongoTemplate.find(query, Project.class).stream()
                .map(p -> ProjectView.of(p, user.getId()))
                .toList();
    }

    @PostMapping("/api/projects")
    public ProjectView create(HttpServletRequest request, @Valid @RequestBody CreateProjectRequest body) {
        User user = currentUser.require(request);
        Project project = new Project();
        project.setName(body.name().trim());
        project.setCreatedAt(Instant.now());
        project.getMembers().add(new Project.Member(user.getId(), Project.Role.OWNER));
        mongoTemplate.insert(project);
        return ProjectView.of(project, user.getId());
    }

    @GetMapping("/api/projects/{projectId}/members")
    public List<MemberView> members(HttpServletRequest request, @PathVariable String projectId) {
        User user = currentUser.require(request);
        return memberViews(requireMembership(projectId, user));
    }

    @PostMapping("/api/projects/{projectId}/members")
    public List<MemberView> addMember(HttpServletRequest request, @PathVariable String projectId,
                                      @Valid @RequestBody AddMemberRequest body) {
        User user = currentUser.require(request);
        requireOwner(projectId, user);

        User added = mongoTemplate.findOne(
                new Query(Criteria.where("email").is(AuthController.normalizeEmail(body.email()))), User.class);
        if (added == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no account with this email");
        }
        join(projectId, added.getId());
        return memberViews(requireMembership(projectId, user));
    }

    @PostMapping("/api/projects/{projectId}/invites")
    public InviteView createInvite(HttpServletRequest request, @PathVariable String projectId) {
        User user = currentUser.require(request);
        requireOwner(projectId, user);

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);

        ProjectInvite invite = new ProjectInvite();
        invite.setToken(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
        invite.setProjectId(projectId);
        invite.setCreatedBy(user.getId());
        invite.setExpiresAt(Instant.now().plus(inviteTtl));
        mongoTemplate.insert(invite);
        return new InviteView(invite.getToken(), invite.getExpiresAt());
    }

    /** Public: lets the join page say which project the link is for before the visitor signs in. */
    @GetMapping("/api/invites/{token}")
    public InvitePreview previewInvite(@PathVariable String token) {
        ProjectInvite invite = requireInvite(token);
        return new InvitePreview(requireInvitedProject(invite).getName(), invite.getExpiresAt());
    }

    @PostMapping("/api/invites/{token}/accept")
    public ProjectView acceptInvite(HttpServletRequest request, @PathVariable String token) {
        User user = currentUser.require(request);
        ProjectInvite invite = requireInvite(token);
        join(invite.getProjectId(), user.getId());
        return ProjectView.of(requireInvitedProject(invite), user.getId());
    }

    private List<MemberView> memberViews(Project project) {
        List<String> ids = project.getMembers().stream().map(Project.Member::getUserId).toList();
        Map<String, User> users = mongoTemplate.find(new Query(Criteria.where("_id").in(ids)), User.class).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));

        return project.getMembers().stream()
                .filter(m -> users.containsKey(m.getUserId()))
                .map(m -> {
                    User u = users.get(m.getUserId());
                    return new MemberView(u.getId(), u.getName(), u.getEmail(), m.getRole(), m.getJoinedAt());
                })
                .toList();
    }

    /** Adds the user as a MEMBER unless already in the project (atomic, so no duplicate entries). */
    private void join(String projectId, String userId) {
        Query notYetMember = new Query(Criteria.where("_id").is(projectId).and("members.user_id").ne(userId));
        Update add = new Update().push("members", new Project.Member(userId, Project.Role.MEMBER));
        mongoTemplate.updateFirst(notYetMember, add, Project.class);
    }

    private ProjectInvite requireInvite(String token) {
        ProjectInvite invite = mongoTemplate.findById(token, ProjectInvite.class);
        // The TTL index removes expired invites, but only about once a minute.
        if (invite == null || invite.getExpiresAt().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "invite not found or expired");
        }
        return invite;
    }

    private Project requireInvitedProject(ProjectInvite invite) {
        Project project = mongoTemplate.findById(invite.getProjectId(), Project.class);
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "invite not found or expired");
        }
        return project;
    }

    /** 404 (not 403) for a project the user isn't in, so project ids can't be probed. */
    private Project requireMembership(String projectId, User user) {
        Project project = mongoTemplate.findById(projectId, Project.class);
        if (project == null || project.roleOf(user.getId()) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "project not found");
        }
        return project;
    }

    private void requireOwner(String projectId, User user) {
        Project project = requireMembership(projectId, user);
        if (project.roleOf(user.getId()) != Project.Role.OWNER) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "only the project owner can do this");
        }
    }
}

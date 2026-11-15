package com.moses.monitor.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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

    /** {@code role} is optional; a new member is an EDITOR unless the owner says otherwise. */
    public record AddMemberRequest(@NotBlank String email, Project.Role role) { }

    public record ChangeRoleRequest(@NotNull Project.Role role) { }

    public record ProjectView(String id, String name, Project.Role role, int memberCount, List<String> environments) {
        static ProjectView of(Project project, String userId) {
            return new ProjectView(project.getId(), project.getName(), project.roleOf(userId),
                    project.getMembers().size(), project.getEnvironments());
        }
    }

    public record MemberView(String userId, String name, String email, Project.Role role, Instant joinedAt) { }

    public record InviteView(String token, Instant expiresAt) { }

    public record InvitePreview(String projectName, Instant expiresAt) { }

    private static final SecureRandom RANDOM = new SecureRandom();

    private final MongoTemplate mongoTemplate;
    private final CurrentUser currentUser;
    private final ProjectGuard guard;
    private final Duration inviteTtl;

    public ProjectController(MongoTemplate mongoTemplate, CurrentUser currentUser, ProjectGuard guard,
                             @Value("${moses.invites.ttl-days}") long inviteTtlDays) {
        this.mongoTemplate = mongoTemplate;
        this.currentUser = currentUser;
        this.guard = guard;
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
        project.setEnvironments(new java.util.ArrayList<>(List.of(Project.DEFAULT_ENVIRONMENT)));
        mongoTemplate.insert(project);
        return ProjectView.of(project, user.getId());
    }

    @GetMapping("/api/projects/{projectId}/members")
    public List<MemberView> members(HttpServletRequest request, @PathVariable String projectId) {
        User user = currentUser.require(request);
        return memberViews(guard.requireMembership(projectId, user));
    }

    @PostMapping("/api/projects/{projectId}/members")
    public List<MemberView> addMember(HttpServletRequest request, @PathVariable String projectId,
                                      @Valid @RequestBody AddMemberRequest body) {
        User user = currentUser.require(request);
        guard.requireOwner(projectId, user);

        User added = mongoTemplate.findOne(
                new Query(Criteria.where("email").is(AuthController.normalizeEmail(body.email()))), User.class);
        if (added == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no account with this email");
        }
        join(projectId, added.getId(), assignable(body.role() == null ? Project.Role.EDITOR : body.role()));
        return memberViews(guard.requireMembership(projectId, user));
    }

    @PutMapping("/api/projects/{projectId}/members/{memberId}")
    public List<MemberView> changeRole(HttpServletRequest request, @PathVariable String projectId,
                                       @PathVariable String memberId, @Valid @RequestBody ChangeRoleRequest body) {
        User user = currentUser.require(request);
        requireOtherMember(guard.requireOwner(projectId, user), memberId);

        Query member = new Query(Criteria.where("_id").is(projectId).and("members.user_id").is(memberId));
        mongoTemplate.updateFirst(member, new Update().set("members.$.role", assignable(body.role())), Project.class);
        return memberViews(guard.requireMembership(projectId, user));
    }

    @DeleteMapping("/api/projects/{projectId}/members/{memberId}")
    public List<MemberView> removeMember(HttpServletRequest request, @PathVariable String projectId,
                                         @PathVariable String memberId) {
        User user = currentUser.require(request);
        requireOtherMember(guard.requireOwner(projectId, user), memberId);

        Update remove = new Update().pull("members", Query.query(Criteria.where("user_id").is(memberId)));
        mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(projectId)), remove, Project.class);
        return memberViews(guard.requireMembership(projectId, user));
    }

    @PostMapping("/api/projects/{projectId}/invites")
    public InviteView createInvite(HttpServletRequest request, @PathVariable String projectId) {
        User user = currentUser.require(request);
        guard.requireOwner(projectId, user);

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
        join(invite.getProjectId(), user.getId(), Project.Role.EDITOR);
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
                    return new MemberView(u.getId(), u.getName(), u.getEmail(), project.roleOf(u.getId()), m.getJoinedAt());
                })
                .toList();
    }

    /** Adds the user with the given role unless already in the project (atomic, so no duplicate entries). */
    private void join(String projectId, String userId, Project.Role role) {
        Query notYetMember = new Query(Criteria.where("_id").is(projectId).and("members.user_id").ne(userId));
        Update add = new Update().push("members", new Project.Member(userId, role));
        mongoTemplate.updateFirst(notYetMember, add, Project.class);
    }

    /** The roles an owner can hand out. A project keeps the one owner who created it. */
    private static Project.Role assignable(Project.Role role) {
        if (role != Project.Role.EDITOR && role != Project.Role.VIEWER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "role must be EDITOR or VIEWER");
        }
        return role;
    }

    /** The target of a role change or removal: someone in the project who is not its owner. */
    private static void requireOtherMember(Project project, String memberId) {
        Project.Role role = project.roleOf(memberId);
        if (role == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "member not found");
        }
        if (role == Project.Role.OWNER) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "the owner's membership cannot be changed");
        }
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
}

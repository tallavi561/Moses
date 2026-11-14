package com.moses.monitor.auth;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A project and the team that belongs to it. Anyone can create one and becomes its owner. */
@Document(collection = "projects")
public class Project {

    public enum Role { OWNER, MEMBER }

    public static class Member {
        @Indexed
        @Field("user_id")
        private String userId;
        private Role role;
        @Field("joined_at")
        private Instant joinedAt;

        public Member() { }

        public Member(String userId, Role role) {
            this.userId = userId;
            this.role = role;
            this.joinedAt = Instant.now();
        }

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }
        public Role getRole() { return role; }
        public void setRole(Role role) { this.role = role; }
        public Instant getJoinedAt() { return joinedAt; }
        public void setJoinedAt(Instant joinedAt) { this.joinedAt = joinedAt; }
    }

    @Id
    private String id;
    private String name;
    @Field("created_at")
    private Instant createdAt;
    private List<Member> members = new ArrayList<>();

    public Role roleOf(String userId) {
        return members.stream()
                .filter(m -> m.getUserId().equals(userId))
                .map(Member::getRole)
                .findFirst()
                .orElse(null);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public List<Member> getMembers() { return members; }
    public void setMembers(List<Member> members) { this.members = members; }
}

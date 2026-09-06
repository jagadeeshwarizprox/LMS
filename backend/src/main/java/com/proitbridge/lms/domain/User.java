package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import java.time.Instant;
import java.util.*;

/** Anyone who can sign in. Premium vs Batch is NOT a role, it lives on Learner. */
@Document("users")
public class User {
    @Id private String id;
    @Indexed(unique = true) private String email;   // one of the two ways in
    /* the other way in: derived from the name, because it is what gets read out loud */
    @Indexed(unique = true, sparse = true) private String loginId;
    private String fullName;
    private String phone;
    private String altPhone;
    private String whatsapp;
    private Role role;
    private String passwordHash;
    private boolean mustChangePassword = true;
    /* when the derived first password was issued; it stops working if never used */
    private Instant defaultPasswordSetAt;
    private boolean active = true;
    private boolean suspended;              // sharing kill switch, drops every session
    private String suspendedReason;
    private Instant lastLoginAt;
    private String reportsToId;                     // hierarchy: mentor -> lead -> cto -> ceo
    private Instant createdAt = Instant.now();

    public enum Role { LEARNER, MENTOR, ADMIN, SUPER_ADMIN }

    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getAltPhone() { return altPhone; }
    public void setAltPhone(String altPhone) { this.altPhone = altPhone; }
    public String getWhatsapp() { return whatsapp; }
    public void setWhatsapp(String whatsapp) { this.whatsapp = whatsapp; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getLoginId() { return loginId; }
    public void setLoginId(String loginId) { this.loginId = loginId; }
    public Instant getDefaultPasswordSetAt() { return defaultPasswordSetAt; }
    public void setDefaultPasswordSetAt(Instant t) { this.defaultPasswordSetAt = t; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public void setMustChangePassword(boolean mustChangePassword) { this.mustChangePassword = mustChangePassword; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public boolean isSuspended() { return suspended; }
    public void setSuspended(boolean suspended) { this.suspended = suspended; }
    public String getSuspendedReason() { return suspendedReason; }
    public void setSuspendedReason(String suspendedReason) { this.suspendedReason = suspendedReason; }
    public Instant getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
    public String getReportsToId() { return reportsToId; }
    public void setReportsToId(String reportsToId) { this.reportsToId = reportsToId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
}

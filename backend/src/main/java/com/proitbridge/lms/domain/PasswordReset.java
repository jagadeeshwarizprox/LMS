package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * A one hour, one use ticket to set a new password.
 *
 * The token is stored hashed, the same way a password is. A reset table full of live
 * tokens is a list of account takeovers waiting for anyone who reads the database, and
 * the person who needs the token already has it in their mail.
 */
@Document("password_resets")
public class PasswordReset {

    @Id private String id;
    @Indexed private String tokenHash;
    @Indexed private String userId;
    private String requestedIp;
    private Instant createdAt = Instant.now();
    private Instant expiresAt;
    private boolean used;
    private Instant usedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getRequestedIp() { return requestedIp; }
    public void setRequestedIp(String requestedIp) { this.requestedIp = requestedIp; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public boolean isUsed() { return used; }
    public void setUsed(boolean used) { this.used = used; }
    public Instant getUsedAt() { return usedAt; }
    public void setUsedAt(Instant usedAt) { this.usedAt = usedAt; }
}

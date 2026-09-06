package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/** One sign in attempt, kept long enough to count. */
@Document("login_attempts")
public class LoginAttempt {

    @Id private String id;
    @Indexed private String email;
    @Indexed private String ip;
    private boolean success;
    private String reason;
    @Indexed private Instant at = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }
}

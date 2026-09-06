package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import java.time.Instant;
import java.util.*;

/**
 * The live session. A token is only accepted while its session row is current,
 * so signing in anywhere drops the previous sign in: two people cannot share one
 * account without constantly knocking each other out.
 */
@Document("sessions")
public class ActiveSession {
    @Id private String id;            // the sid carried in the token
    @Indexed private String userId;
    private String deviceId;
    private String ip;
    private String userAgent;
    private Instant startedAt = Instant.now();
    private Instant lastSeenAt = Instant.now();
    private boolean current = true;
    private String endedReason;       // SIGNED_OUT | REPLACED | SUSPENDED | IDLE

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public boolean isCurrent() { return current; }
    public void setCurrent(boolean current) { this.current = current; }
    public String getEndedReason() { return endedReason; }
    public void setEndedReason(String endedReason) { this.endedReason = endedReason; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
}

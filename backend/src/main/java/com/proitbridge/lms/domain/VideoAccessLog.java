package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import java.time.Instant;
import java.util.*;

/** Every playback token minted, so a leak can be traced to an account and a moment. */
/*
 * Indexes for twenty thousand learners and their logs.
 *
 * The playback log is the fastest growing collection here: one row per grant. The
 * anomaly sweep reads one day of it by time window and then per user, and the admin
 * screen pages it newest first.
 */
@CompoundIndexes({
    @CompoundIndex(name = "user_issued", def = "{'userId': 1, 'issuedAt': -1}"),
    @CompoundIndex(name = "issued", def = "{'issuedAt': -1}"),
    /* one video's grants, newest first (findByVideoId, the library's per-video view) */
    @CompoundIndex(name = "video_issued", def = "{'videoId': 1, 'issuedAt': -1}")
})
@Document("video_access_log")
public class VideoAccessLog {
    @Id private String id;
    private String userId;
    private String learnerId;
    private String videoId;
    private String sessionId;
    private String deviceId;
    private String ip;
    private Instant issuedAt = Instant.now();
    private Instant expiresAt;
    private String outcome = "GRANTED";   // GRANTED | REFUSED
    private String reason;

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getLearnerId() { return learnerId; }
    public void setLearnerId(String learnerId) { this.learnerId = learnerId; }
    public String getVideoId() { return videoId; }
    public void setVideoId(String videoId) { this.videoId = videoId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}

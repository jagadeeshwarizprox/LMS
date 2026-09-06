package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.*;

/** A sharing signal worth a human look. Raised by the anomaly sweep, cleared by an admin. */
@Document("access_flags")
public class AccessFlag {
    @Id private String id;
    private String userId;
    private String userName;
    private String kind;              // MANY_IPS | IMPOSSIBLE_TRAVEL | TOKEN_BURST | DEVICE_CHURN
    private String detail;
    private String severity = "REVIEW";
    private boolean cleared;
    private String clearedBy;
    private Instant createdAt = Instant.now();

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getUserName() { return userName; }
    public void setUserName(String userName) { this.userName = userName; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public boolean isCleared() { return cleared; }
    public void setCleared(boolean cleared) { this.cleared = cleared; }
    public String getClearedBy() { return clearedBy; }
    public void setClearedBy(String clearedBy) { this.clearedBy = clearedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}

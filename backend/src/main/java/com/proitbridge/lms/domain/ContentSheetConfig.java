package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Where the master content sheet lives, and whether the LMS watches it.
 *
 * One document, id "default". The link is a Google Sheet shared as "anyone with the
 * link can view"; the scheduled check exports it as xlsx, and a changed hash is what
 * counts as a change.
 */
@Document("content_sheet_config")
public class ContentSheetConfig {
    public static final String ID = "default";

    @Id private String id = ID;
    private String sheetUrl;
    private boolean autoSync;
    private int intervalMinutes = 10;
    private Instant lastCheckedAt;
    private String lastCheckError;
    private String updatedBy;
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSheetUrl() { return sheetUrl; }
    public void setSheetUrl(String sheetUrl) { this.sheetUrl = sheetUrl; }
    public boolean isAutoSync() { return autoSync; }
    public void setAutoSync(boolean autoSync) { this.autoSync = autoSync; }
    public int getIntervalMinutes() { return intervalMinutes; }
    public void setIntervalMinutes(int intervalMinutes) { this.intervalMinutes = intervalMinutes; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(Instant lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }
    public String getLastCheckError() { return lastCheckError; }
    public void setLastCheckError(String lastCheckError) { this.lastCheckError = lastCheckError; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

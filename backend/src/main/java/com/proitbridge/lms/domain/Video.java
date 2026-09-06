package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.*;

/**
 * A piece of video. The provider's own identifier never leaves the server: the
 * client only ever holds this document's id, and exchanges it for a short lived
 * signed token at the moment of play.
 */
@Document("videos")
public class Video {
    @Id private String id;
    private String title;
    private String provider = "CLOUDFLARE_STREAM";  // CLOUDFLARE_STREAM | YOUTUBE
    private String externalId;                      // Stream UID, or YouTube video id
    private int durationSec;
    private String kind = "TOPIC";                // TOPIC | GUIDE | PREREQ | INDUCTION | RECORDING
    private String moduleId;
    private String batchId;
    private String visibility = "ENROLLED";         // ENROLLED | TRACK_PREMIUM | TRACK_BATCH | BATCH_ONLY
    private Instant recordedOn;
    private String uploadedBy;
    private boolean active = true;
    private Instant createdAt = Instant.now();

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }
    public String getExternalId() { return externalId; }
    public void setExternalId(String externalId) { this.externalId = externalId; }
    public int getDurationSec() { return durationSec; }
    public void setDurationSec(int durationSec) { this.durationSec = durationSec; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getModuleId() { return moduleId; }
    public void setModuleId(String moduleId) { this.moduleId = moduleId; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String visibility) { this.visibility = visibility; }
    public Instant getRecordedOn() { return recordedOn; }
    public void setRecordedOn(Instant recordedOn) { this.recordedOn = recordedOn; }
    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}

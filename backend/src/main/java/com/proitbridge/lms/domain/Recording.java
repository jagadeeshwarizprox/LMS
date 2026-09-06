package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.*;

/**
 * A session that was held, published back.
 *
 * This is not course content. The lectures for Python, statistics and the rest are
 * pre-recorded and live on their topics as materials. This is the second library:
 * what actually happened in a live session, which grows every week and used to have
 * almost nowhere to go.
 *
 * Two shapes matter. A recap belongs to a module rather than a topic and often runs
 * over two or three days, so it is a series with parts. Doubt clearing is combined
 * across every batch, so it is never scoped to one, and scoping it would hide it from
 * most of the people who sat in it.
 */
@Document("recordings")
public class Recording {
    @Id private String id;
    private String title;
    private String videoRef;             // -> Video.id, never a URL
    private String slotId;
    private String moduleId;
    private String batchId;
    private String trackScope = "BOTH";
    /** DOUBT | RECAP | PROJECT | INDUSTRY | INDUCTION | MOCK_DEBRIEF */
    private String kind = "DOUBT";

    /** A topic, when the session was about one specific thing. */
    private String chapterId;

    /**
     * Who can watch. Doubt clearing is combined across batches, so it is ALL and any
     * learner sees it. An induction belongs to one batch and nobody else.
     */
    private String audience = "ALL";     // ALL | BATCH | TRACK

    /** A recap runs over several days, so day two is part two of the same series. */
    private String seriesLabel;
    private int partNumber;
    private int totalParts;

    private int durationMin;
    private String coveredSummary;       // what was actually covered
    private List<String> questionsCovered = new ArrayList<>();
    private String notes;
    /* Slides, datasets and handouts that went with the session. */
    private List<String> fileIds = new ArrayList<>();
    private Instant heldOn;
    private String uploadedBy;
    private Instant createdAt = Instant.now();

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getVideoRef() { return videoRef; }
    public void setVideoRef(String videoRef) { this.videoRef = videoRef; }
    public String getSlotId() { return slotId; }
    public void setSlotId(String slotId) { this.slotId = slotId; }
    public String getModuleId() { return moduleId; }
    public void setModuleId(String moduleId) { this.moduleId = moduleId; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getTrackScope() { return trackScope; }
    public void setTrackScope(String trackScope) { this.trackScope = trackScope; }
    public String getChapterId() { return chapterId; }
    public void setChapterId(String chapterId) { this.chapterId = chapterId; }
    public String getAudience() { return audience; }
    public void setAudience(String audience) { this.audience = audience; }
    public String getSeriesLabel() { return seriesLabel; }
    public void setSeriesLabel(String seriesLabel) { this.seriesLabel = seriesLabel; }
    public int getPartNumber() { return partNumber; }
    public void setPartNumber(int partNumber) { this.partNumber = partNumber; }
    public int getTotalParts() { return totalParts; }
    public void setTotalParts(int totalParts) { this.totalParts = totalParts; }
    public int getDurationMin() { return durationMin; }
    public void setDurationMin(int durationMin) { this.durationMin = durationMin; }
    public String getCoveredSummary() { return coveredSummary; }
    public void setCoveredSummary(String coveredSummary) { this.coveredSummary = coveredSummary; }
    public List<String> getQuestionsCovered() { return questionsCovered; }
    public void setQuestionsCovered(List<String> questionsCovered) { this.questionsCovered = questionsCovered; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public List<String> getFileIds() { return fileIds; }
    public void setFileIds(List<String> fileIds) {
        this.fileIds = fileIds == null ? new ArrayList<>() : fileIds;
    }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public Instant getHeldOn() { return heldOn; }
    public void setHeldOn(Instant heldOn) { this.heldOn = heldOn; }
    public String getUploadedBy() { return uploadedBy; }
    public void setUploadedBy(String uploadedBy) { this.uploadedBy = uploadedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}

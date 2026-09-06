package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.*;
@Document("resumes")
public class ResumeVersion {
    @Id private String id;
    private String learnerId;
    private int version = 1;
    private String filename;
    private String url;
    private boolean reviewedByMentor;
    private Instant uploadedAt = Instant.now();

    public String getLearnerId() { return learnerId; }
    public void setLearnerId(String learnerId) { this.learnerId = learnerId; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public boolean isReviewedByMentor() { return reviewedByMentor; }
    public void setReviewedByMentor(boolean reviewedByMentor) { this.reviewedByMentor = reviewedByMentor; }
    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}

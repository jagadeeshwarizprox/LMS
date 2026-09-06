package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import java.time.Instant;
import java.util.*;

/** One turn in a task: a submission, or a review. Nothing overwrites anything. */
@Document("assignment_events")
public class AssignmentEvent {
    @Id private String id;
    @Indexed private String assignmentId;
    private String kind;              // SUBMITTED|REVIEWED|COMMENT|ASSIGNED|DUE_CHANGED
    private String actorId;
    private String actorName;
    private String actorRole;
    private String body;
    private String submissionUrl;
    private String fileId;
    private java.util.List<String> fileIds = new java.util.ArrayList<>();
    private String status;            // for REVIEWED: APPROVED|CHANGES
    private Double score;
    private Map<String, Double> rubricScores = new LinkedHashMap<>();
    private Instant at = Instant.now();

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public String getActorName() { return actorName; }
    public void setActorName(String actorName) { this.actorName = actorName; }
    public String getActorRole() { return actorRole; }
    public void setActorRole(String actorRole) { this.actorRole = actorRole; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getSubmissionUrl() { return submissionUrl; }
    public void setSubmissionUrl(String submissionUrl) { this.submissionUrl = submissionUrl; }
    public java.util.List<String> getFileIds() { return fileIds; }
    public void setFileIds(java.util.List<String> fileIds) {
        this.fileIds = fileIds == null ? new java.util.ArrayList<>() : fileIds;
    }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public Map<String, Double> getRubricScores() { return rubricScores; }
    public void setRubricScores(Map<String, Double> rubricScores) { this.rubricScores = rubricScores; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getAssignmentId() { return assignmentId; }
    public void setAssignmentId(String assignmentId) { this.assignmentId = assignmentId; }
}

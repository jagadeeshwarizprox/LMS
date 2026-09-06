package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.*;

/** Course project. For batch learners one approved project unlocks the mock interview. */
@Document("projects")
public class ProjectWork {
    @Id private String id;
    private String learnerId;
    private String title;
    private String context;              // ACADEMIC|INTERNSHIP|PROFESSIONAL|SELF
    private String summary;
    private String tools;
    private String repoUrl;
    /* Work that is not in a repository still has to be handed in. */
    private java.util.List<String> fileIds = new java.util.ArrayList<>();
    private String status = "SUBMITTED";  // SUBMITTED|CHANGES|APPROVED
    private String mentorFeedback;
    private Instant submittedAt = Instant.now();
    private Instant reviewedAt;

    public String getLearnerId() { return learnerId; }
    public void setLearnerId(String learnerId) { this.learnerId = learnerId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContext() { return context; }
    public void setContext(String context) { this.context = context; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getTools() { return tools; }
    public void setTools(String tools) { this.tools = tools; }
    public java.util.List<String> getFileIds() { return fileIds; }
    public void setFileIds(java.util.List<String> fileIds) {
        this.fileIds = fileIds == null ? new java.util.ArrayList<>() : fileIds;
    }

    public String getRepoUrl() { return repoUrl; }
    public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMentorFeedback() { return mentorFeedback; }
    public void setMentorFeedback(String mentorFeedback) { this.mentorFeedback = mentorFeedback; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}

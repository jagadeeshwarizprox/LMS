package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.*;
@Document("assignments")
public class Assignment {
    @Id private String id;
    private String learnerId;
    private String chapterId;
    private String title;
    private String submissionUrl;
    private String notes;
    private String status = "SUBMITTED";     // SUBMITTED|CHANGES|APPROVED
    private Double score;
    private String mentorFeedback;
    private Instant submittedAt = Instant.now();
    private Instant reviewedAt;
    private Instant dueAt;
    private String brief;                 // what the mentor actually asked for
    private String origin = "CHAPTER";    // CHAPTER|ADHOC
    private String assignedBy;
    private String rubricId;
    private Map<String, Double> rubricScores = new LinkedHashMap<>();
    private String fileId;                // the first one, kept so old rows still read
    /* a submission is usually a notebook plus the data it reads plus a chart */
    private List<String> fileIds = new ArrayList<>();
    private int submissionCount;
    /** What the score is out of. Copied from the chapter so review does not have to guess. */
    private int maxMarks = 20;

    public String getLearnerId() { return learnerId; }
    public void setLearnerId(String learnerId) { this.learnerId = learnerId; }
    public String getChapterId() { return chapterId; }
    public void setChapterId(String chapterId) { this.chapterId = chapterId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSubmissionUrl() { return submissionUrl; }
    public void setSubmissionUrl(String submissionUrl) { this.submissionUrl = submissionUrl; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public String getMentorFeedback() { return mentorFeedback; }
    public void setMentorFeedback(String mentorFeedback) { this.mentorFeedback = mentorFeedback; }
    public Instant getSubmittedAt() { return submittedAt; }
    public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
    public Instant getReviewedAt() { return reviewedAt; }
    public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }
    public String getBrief() { return brief; }
    public void setBrief(String brief) { this.brief = brief; }
    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }
    public String getAssignedBy() { return assignedBy; }
    public void setAssignedBy(String assignedBy) { this.assignedBy = assignedBy; }
    public String getRubricId() { return rubricId; }
    public void setRubricId(String rubricId) { this.rubricId = rubricId; }
    public int getMaxMarks() { return maxMarks; }
    public void setMaxMarks(int maxMarks) { this.maxMarks = maxMarks; }
    public Map<String, Double> getRubricScores() { return rubricScores; }
    public void setRubricScores(Map<String, Double> rubricScores) { this.rubricScores = rubricScores; }
    public List<String> getFileIds() { return fileIds; }
    public void setFileIds(List<String> fileIds) {
        this.fileIds = fileIds == null ? new ArrayList<>() : fileIds;
    }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public int getSubmissionCount() { return submissionCount; }
    public void setSubmissionCount(int submissionCount) { this.submissionCount = submissionCount; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}

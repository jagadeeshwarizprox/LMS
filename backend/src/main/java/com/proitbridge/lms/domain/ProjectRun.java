package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One learner working through one project.
 *
 * The run holds the position; the catalogue holds the content. That split is what lets a
 * brief be corrected mid-cohort without disturbing anybody's submissions, and what lets
 * the same project run for every batch without being copied.
 *
 * A stage gate is a mentor decision, never automatic. Auto-advancing on submission would
 * let somebody reach the demo having built nothing.
 */
@Document("project_runs")
public class ProjectRun {

    @Id private String id;

    @Indexed private String learnerId;
    @Indexed private String projectId;
    private String mentorId;
    private String batchId;

    /** The key of the stage the learner is on now. */
    private String currentStage;

    /** RUNNING | APPROVED | ABANDONED */
    private String status = "RUNNING";

    private Instant startedAt = Instant.now();
    private Instant approvedAt;

    /** Set only when a team project. The learner who owns this run is included. */
    private List<String> teamLearnerIds = new ArrayList<>();

    /** stage key to its state. Kept in order, so the rail renders from it directly. */
    private Map<String, StageState> stages = new LinkedHashMap<>();

    /**
     * What happened at one stage, including every attempt.
     *
     * Attempts are appended, never replaced, for the same reason assignment versions are:
     * a learner asked to redo something needs to see what they sent last time, and a
     * mentor picking a run up from a colleague needs to see how it got here.
     */
    public static class StageState {
        /** LOCKED | OPEN | SUBMITTED | CHANGES | APPROVED */
        private String status = "LOCKED";
        private Instant openedAt;
        private Instant dueAt;
        private Instant submittedAt;
        private Instant reviewedAt;
        private String reviewedBy;
        private Double score;
        private String feedback;
        private Map<String, Double> rubricScores = new LinkedHashMap<>();
        private List<Attempt> attempts = new ArrayList<>();

        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Instant getOpenedAt() { return openedAt; }
        public void setOpenedAt(Instant openedAt) { this.openedAt = openedAt; }
        public Instant getDueAt() { return dueAt; }
        public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }
        public Instant getSubmittedAt() { return submittedAt; }
        public void setSubmittedAt(Instant submittedAt) { this.submittedAt = submittedAt; }
        public Instant getReviewedAt() { return reviewedAt; }
        public void setReviewedAt(Instant reviewedAt) { this.reviewedAt = reviewedAt; }
        public String getReviewedBy() { return reviewedBy; }
        public void setReviewedBy(String reviewedBy) { this.reviewedBy = reviewedBy; }
        public Double getScore() { return score; }
        public void setScore(Double score) { this.score = score; }
        public String getFeedback() { return feedback; }
        public void setFeedback(String feedback) { this.feedback = feedback; }
        public Map<String, Double> getRubricScores() { return rubricScores; }
        public void setRubricScores(Map<String, Double> rubricScores) {
            this.rubricScores = rubricScores == null ? new LinkedHashMap<>() : rubricScores;
        }
        public List<Attempt> getAttempts() { return attempts; }
        public void setAttempts(List<Attempt> attempts) {
            this.attempts = attempts == null ? new ArrayList<>() : attempts;
        }
    }

    /** One hand-in. Version numbers start at one and never go backwards. */
    public static class Attempt {
        private int version;
        private String notes;
        private String repoUrl;
        private String demoUrl;
        private List<String> fileIds = new ArrayList<>();
        private Instant at = Instant.now();
        /** Carried from the review that followed, so history reads in one place. */
        private String outcome;
        private String feedback;

        public int getVersion() { return version; }
        public void setVersion(int version) { this.version = version; }
        public String getNotes() { return notes; }
        public void setNotes(String notes) { this.notes = notes; }
        public String getRepoUrl() { return repoUrl; }
        public void setRepoUrl(String repoUrl) { this.repoUrl = repoUrl; }
        public String getDemoUrl() { return demoUrl; }
        public void setDemoUrl(String demoUrl) { this.demoUrl = demoUrl; }
        public List<String> getFileIds() { return fileIds; }
        public void setFileIds(List<String> fileIds) {
            this.fileIds = fileIds == null ? new ArrayList<>() : fileIds;
        }
        public Instant getAt() { return at; }
        public void setAt(Instant at) { this.at = at; }
        public String getOutcome() { return outcome; }
        public void setOutcome(String outcome) { this.outcome = outcome; }
        public String getFeedback() { return feedback; }
        public void setFeedback(String feedback) { this.feedback = feedback; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLearnerId() { return learnerId; }
    public void setLearnerId(String learnerId) { this.learnerId = learnerId; }
    public String getProjectId() { return projectId; }
    public void setProjectId(String projectId) { this.projectId = projectId; }
    public String getMentorId() { return mentorId; }
    public void setMentorId(String mentorId) { this.mentorId = mentorId; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getCurrentStage() { return currentStage; }
    public void setCurrentStage(String currentStage) { this.currentStage = currentStage; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant approvedAt) { this.approvedAt = approvedAt; }
    public List<String> getTeamLearnerIds() { return teamLearnerIds; }
    public void setTeamLearnerIds(List<String> teamLearnerIds) {
        this.teamLearnerIds = teamLearnerIds == null ? new ArrayList<>() : teamLearnerIds;
    }
    public Map<String, StageState> getStages() { return stages; }
    public void setStages(Map<String, StageState> stages) {
        this.stages = stages == null ? new LinkedHashMap<>() : stages;
    }
}

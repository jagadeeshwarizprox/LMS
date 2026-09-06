package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.*;

/**
 * One row per learner per chapter: which topics were watched, then test, teach back, task.
 *
 * Watching moved down to the topic when the topic level was introduced, but the row
 * stayed on the chapter, because everything a learner is judged on is still assessed
 * once per chapter. {@code watched} is kept as a flag rather than recomputed on every
 * read: it is set the moment the last topic in the chapter is finished.
 */
@Document("progress")
public class Progress {
    @Id private String id;
    private String learnerId;
    private String chapterId;
    private String moduleId;
    /** Topic ids inside this chapter that the learner has finished. */
    private Set<String> watchedTopicIds = new LinkedHashSet<>();
    /** True once every topic in the chapter is in watchedTopicIds. */
    private boolean watched;
    private Double quizScore;            // first attempt is the recorded score
    private Double conceptCheckScore;
    /**
     * Whether that score came from the model or from the fallback.
     *
     * The fallback used to award marks by word count, so an unreachable Ollama silently
     * turned the teach back into a length check and nothing on any screen said so. The
     * fallback no longer scores at all; this flag is what lets a pending attempt be told
     * apart from a graded one, including on rows written before the change.
     */
    private boolean conceptCheckPending;
    /** What they actually wrote, so a pending answer can be read by a mentor or regraded. */
    private String conceptCheckAnswer;
    private String taskStatus = "NOT_STARTED"; // NOT_STARTED|SUBMITTED|CHANGES|APPROVED
    private Instant updatedAt = Instant.now();

    public String getLearnerId() { return learnerId; }
    public void setLearnerId(String learnerId) { this.learnerId = learnerId; }
    public String getChapterId() { return chapterId; }
    public void setChapterId(String chapterId) { this.chapterId = chapterId; }
    public String getModuleId() { return moduleId; }
    public void setModuleId(String moduleId) { this.moduleId = moduleId; }
    public Set<String> getWatchedTopicIds() { return watchedTopicIds; }
    public void setWatchedTopicIds(Set<String> watchedTopicIds) { this.watchedTopicIds = watchedTopicIds; }
    public boolean isWatched() { return watched; }
    public void setWatched(boolean watched) { this.watched = watched; }
    public Double getQuizScore() { return quizScore; }
    public void setQuizScore(Double quizScore) { this.quizScore = quizScore; }
    public Double getConceptCheckScore() { return conceptCheckScore; }
    public void setConceptCheckScore(Double conceptCheckScore) { this.conceptCheckScore = conceptCheckScore; }
    public boolean isConceptCheckPending() { return conceptCheckPending; }
    public void setConceptCheckPending(boolean conceptCheckPending) { this.conceptCheckPending = conceptCheckPending; }
    public String getConceptCheckAnswer() { return conceptCheckAnswer; }
    public void setConceptCheckAnswer(String conceptCheckAnswer) { this.conceptCheckAnswer = conceptCheckAnswer; }
    public String getTaskStatus() { return taskStatus; }
    public void setTaskStatus(String taskStatus) { this.taskStatus = taskStatus; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}

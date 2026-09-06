package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Where a learner stopped watching.
 *
 * One row per learner per video, updated rather than appended, because the only
 * question anyone asks is where to resume. A history of every position would be a
 * large table answering a question nobody has.
 */
@Document("watch_positions")
public class WatchPosition {

    @Id private String id;
    @Indexed private String learnerId;
    @Indexed private String videoRef;

    private int seconds;
    private int durationSec;
    private int percent;
    private boolean completed;

    /** So the roadmap can offer the most recent thing first. */
    private Instant updatedAt = Instant.now();

    /** What it was, for the row that says "continue watching". */
    private String title;
    private String topicId;
    private String chapterId;
    private String kind = "TOPIC";   // TOPIC | RECORDING | GUIDE

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLearnerId() { return learnerId; }
    public void setLearnerId(String learnerId) { this.learnerId = learnerId; }
    public String getVideoRef() { return videoRef; }
    public void setVideoRef(String videoRef) { this.videoRef = videoRef; }
    public int getSeconds() { return seconds; }
    public void setSeconds(int seconds) { this.seconds = seconds; }
    public int getDurationSec() { return durationSec; }
    public void setDurationSec(int durationSec) { this.durationSec = durationSec; }
    public int getPercent() { return percent; }
    public void setPercent(int percent) { this.percent = percent; }
    public boolean isCompleted() { return completed; }
    public void setCompleted(boolean completed) { this.completed = completed; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getTopicId() { return topicId; }
    public void setTopicId(String topicId) { this.topicId = topicId; }
    public String getChapterId() { return chapterId; }
    public void setChapterId(String chapterId) { this.chapterId = chapterId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
}

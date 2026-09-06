package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.*;
@Document("quiz_attempts")
public class QuizAttempt {
    @Id private String id;
    private String learnerId;
    private String chapterId;
    private int attemptNo = 1;
    private double score;
    private Map<String, Integer> answers = new HashMap<>();
    private Instant takenAt = Instant.now();

    public String getLearnerId() { return learnerId; }
    public void setLearnerId(String learnerId) { this.learnerId = learnerId; }
    public String getChapterId() { return chapterId; }
    public void setChapterId(String chapterId) { this.chapterId = chapterId; }
    public int getAttemptNo() { return attemptNo; }
    public void setAttemptNo(int attemptNo) { this.attemptNo = attemptNo; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public Map<String, Integer> getAnswers() { return answers; }
    public void setAnswers(Map<String, Integer> answers) { this.answers = answers; }
    public Instant getTakenAt() { return takenAt; }
    public void setTakenAt(Instant takenAt) { this.takenAt = takenAt; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}

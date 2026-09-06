package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * One study session. Opened by an explicit check in, closed by check out, by the
 * browser going away, or by the nightly sweep if someone forgets. Time is counted
 * from real activity, never from a tab left open overnight.
 */
@Document("study_sessions")
public class StudySession {
    @Id private String id;
    @Indexed private String learnerId;
    private LocalDate day;
    private Instant checkedInAt = Instant.now();
    private Instant lastHeartbeatAt = Instant.now();
    private Instant checkedOutAt;
    private int minutes;                  // settled on check out
    private String closedBy = "OPEN";     // OPEN|LEARNER|IDLE|SWEEP
    private String note;                  // what they sat down to do
    private Set<String> chaptersTouched = new LinkedHashSet<>();

    public LocalDate getDay() { return day; }
    public void setDay(LocalDate day) { this.day = day; }
    public Instant getCheckedInAt() { return checkedInAt; }
    public void setCheckedInAt(Instant checkedInAt) { this.checkedInAt = checkedInAt; }
    public Instant getLastHeartbeatAt() { return lastHeartbeatAt; }
    public void setLastHeartbeatAt(Instant lastHeartbeatAt) { this.lastHeartbeatAt = lastHeartbeatAt; }
    public Instant getCheckedOutAt() { return checkedOutAt; }
    public void setCheckedOutAt(Instant checkedOutAt) { this.checkedOutAt = checkedOutAt; }
    public int getMinutes() { return minutes; }
    public void setMinutes(int minutes) { this.minutes = minutes; }
    public String getClosedBy() { return closedBy; }
    public void setClosedBy(String closedBy) { this.closedBy = closedBy; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Set<String> getChaptersTouched() { return chaptersTouched; }
    public void setChaptersTouched(Set<String> chaptersTouched) { this.chaptersTouched = chaptersTouched; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLearnerId() { return learnerId; }
    public void setLearnerId(String learnerId) { this.learnerId = learnerId; }
}

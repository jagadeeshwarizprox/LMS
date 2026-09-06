package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import java.time.LocalDate;
import java.util.*;

/** A day's settled total, so streaks and charts never re-add thousands of sessions. */
@Document("study_days")
public class StudyDay {
    @Id private String id;               // learnerId:yyyy-MM-dd
    @Indexed private String learnerId;
    private LocalDate day;
    private int minutes;
    private int sessions;
    private int chaptersWatched;
    private int tasksSubmitted;

    public LocalDate getDay() { return day; }
    public void setDay(LocalDate day) { this.day = day; }
    public int getMinutes() { return minutes; }
    public void setMinutes(int minutes) { this.minutes = minutes; }
    public int getSessions() { return sessions; }
    public void setSessions(int sessions) { this.sessions = sessions; }
    public int getChaptersWatched() { return chaptersWatched; }
    public void setChaptersWatched(int chaptersWatched) { this.chaptersWatched = chaptersWatched; }
    public int getTasksSubmitted() { return tasksSubmitted; }
    public void setTasksSubmitted(int tasksSubmitted) { this.tasksSubmitted = tasksSubmitted; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLearnerId() { return learnerId; }
    public void setLearnerId(String learnerId) { this.learnerId = learnerId; }
}

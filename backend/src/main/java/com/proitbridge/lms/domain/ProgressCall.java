package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import java.time.Instant;
import java.util.*;

/** Premium only. Biweekly one to one. Logging a call auto schedules the next one. */
/* a learner's calls newest first, and a mentor's calls */
@CompoundIndexes({
    @CompoundIndex(name = "learner_scheduled", def = "{'learnerId': 1, 'scheduledFor': -1}"),
    @CompoundIndex(name = "mentor", def = "{'mentorId': 1}")
})
@Document("progress_calls")
public class ProgressCall {
    @Id private String id;
    private String learnerId;
    private String mentorId;
    private Instant scheduledFor;
    private Instant heldAt;
    private String notes;
    private String nextGoal;

    public String getLearnerId() { return learnerId; }
    public void setLearnerId(String learnerId) { this.learnerId = learnerId; }
    public String getMentorId() { return mentorId; }
    public void setMentorId(String mentorId) { this.mentorId = mentorId; }
    public Instant getScheduledFor() { return scheduledFor; }
    public void setScheduledFor(Instant scheduledFor) { this.scheduledFor = scheduledFor; }
    public Instant getHeldAt() { return heldAt; }
    public void setHeldAt(Instant heldAt) { this.heldAt = heldAt; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getNextGoal() { return nextGoal; }
    public void setNextGoal(String nextGoal) { this.nextGoal = nextGoal; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}

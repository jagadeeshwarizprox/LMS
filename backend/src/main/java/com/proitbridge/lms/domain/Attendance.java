package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import java.time.Instant;
import java.util.*;

/** Who actually turned up. Attendance is the other half of running a session. */
/* a learner's attendance history; slotId already carries its own index */
@CompoundIndexes({
    @CompoundIndex(name = "learner", def = "{'learnerId': 1}"),
    @CompoundIndex(name = "slot_learner", def = "{'slotId': 1, 'learnerId': 1}")
})
@Document("attendance")
public class Attendance {
    @Id private String id;
    @Indexed private String slotId;
    private String learnerId;
    private String state = "PRESENT"; // PRESENT|ABSENT|LATE
    private String note;
    private Instant markedAt = Instant.now();

    public String getLearnerId() { return learnerId; }
    public void setLearnerId(String learnerId) { this.learnerId = learnerId; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public Instant getMarkedAt() { return markedAt; }
    public void setMarkedAt(Instant markedAt) { this.markedAt = markedAt; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getSlotId() { return slotId; }
    public void setSlotId(String slotId) { this.slotId = slotId; }
}

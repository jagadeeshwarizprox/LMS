package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import java.time.Instant;
import java.util.*;

/**
 * Any deliberate move of a learner: track, batch, course, mentor, or on hold.
 * Nothing is deleted by a move, so the record survives every one of them.
 */
@Document("learner_moves")
public class LearnerMove {
    @Id private String id;
    @Indexed private String learnerId;
    private String kind;                 // TRACK|BATCH|BUNDLE|MENTOR|HOLD|RESUME
    private String fromValue;
    private String toValue;
    private String reason;
    private String actorEmail;
    private Instant at = Instant.now();

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getFromValue() { return fromValue; }
    public void setFromValue(String fromValue) { this.fromValue = fromValue; }
    public String getToValue() { return toValue; }
    public void setToValue(String toValue) { this.toValue = toValue; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getActorEmail() { return actorEmail; }
    public void setActorEmail(String actorEmail) { this.actorEmail = actorEmail; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLearnerId() { return learnerId; }
    public void setLearnerId(String learnerId) { this.learnerId = learnerId; }
}

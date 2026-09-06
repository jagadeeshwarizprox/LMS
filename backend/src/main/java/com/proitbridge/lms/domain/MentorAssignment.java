package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.*;

/** Mentors stay put. Every change is deliberate, reasoned and kept. */
@Document("mentor_assignments")
public class MentorAssignment {
    @Id private String id;
    private String learnerId;
    private String fromMentorId;
    private String toMentorId;
    private String reason;
    private String actorEmail;
    private Instant at = Instant.now();

    public String getLearnerId() { return learnerId; }
    public void setLearnerId(String learnerId) { this.learnerId = learnerId; }
    public String getFromMentorId() { return fromMentorId; }
    public void setFromMentorId(String fromMentorId) { this.fromMentorId = fromMentorId; }
    public String getToMentorId() { return toMentorId; }
    public void setToMentorId(String toMentorId) { this.toMentorId = toMentorId; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getActorEmail() { return actorEmail; }
    public void setActorEmail(String actorEmail) { this.actorEmail = actorEmail; }
    public Instant getAt() { return at; }
    public void setAt(Instant at) { this.at = at; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}

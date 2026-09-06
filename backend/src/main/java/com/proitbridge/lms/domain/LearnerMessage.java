package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import java.time.Instant;
import java.util.*;

/** A note sent to a learner, kept on their record rather than lost in WhatsApp. */
@Document("messages")
public class LearnerMessage {
    @Id private String id;
    @Indexed private String learnerId;
    private String fromId;
    private String fromName;
    private String subject;
    private String body;
    private String kind = "MESSAGE";  // MESSAGE|NUDGE|ANNOUNCEMENT
    private boolean readByLearner;
    private Instant sentAt = Instant.now();

    public String getFromId() { return fromId; }
    public void setFromId(String fromId) { this.fromId = fromId; }
    public String getFromName() { return fromName; }
    public void setFromName(String fromName) { this.fromName = fromName; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public boolean isReadByLearner() { return readByLearner; }
    public void setReadByLearner(boolean readByLearner) { this.readByLearner = readByLearner; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLearnerId() { return learnerId; }
    public void setLearnerId(String learnerId) { this.learnerId = learnerId; }
}

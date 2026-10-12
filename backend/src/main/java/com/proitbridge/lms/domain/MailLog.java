package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import java.time.Instant;
import java.util.*;

/** Every credential and notification mail is recorded, sent or not. */
/*
 * Indexes for twenty thousand learners and their logs.
 *
 * Paged newest first, and looked up per recipient by the onboarding board (was this
 * person ever sent their credentials) and the learner record.
 */
@CompoundIndexes({
    @CompoundIndex(name = "sent", def = "{'sentAt': -1}"),
    @CompoundIndex(name = "to_sent", def = "{'toEmail': 1, 'sentAt': -1}")
})
@Document("mail_log")
public class MailLog {
    @Id private String id;
    private String toEmail;
    private String subject;
    private String body;
    private String kind;                 // CREDENTIALS|REVIEW|CALL|GENERIC
    private String status = "LOGGED";    // LOGGED|SENT|FAILED
    private String error;
    private Instant sentAt = Instant.now();

    public String getToEmail() { return toEmail; }
    public void setToEmail(String toEmail) { this.toEmail = toEmail; }
    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}

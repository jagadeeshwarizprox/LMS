package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.*;

/** Every credential and notification mail is recorded, sent or not. */
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

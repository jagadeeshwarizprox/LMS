package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One mentor covering another's learners while they are away.
 *
 * The important part is what it does not do: the learner's mentor does not change. The
 * relationship is the product, and a five day absence should not rewrite it or show the
 * learner a new name. The covering mentor sees the same queues and the same records, the
 * learner sees no difference, and every action taken during cover is attributed to the
 * person who actually took it.
 *
 * Only an admin can arrange this. A mentor handing their own learners to someone else,
 * unrecorded, is how a caseload quietly becomes somebody else's problem.
 */
@Document("mentor_cover")
public class MentorCover {

    @Id private String id;

    /** Whose learners these are. Unchanged throughout. */
    @Indexed private String mentorId;

    /** Who is watching them meanwhile. */
    @Indexed private String coveringMentorId;

    private LocalDate from;
    private LocalDate until;
    private String reason;

    /** Ended by hand before the date, or still running. */
    private boolean active = true;
    private String endedBy;
    private Instant endedAt;

    private String arrangedBy;
    private Instant createdAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMentorId() { return mentorId; }
    public void setMentorId(String mentorId) { this.mentorId = mentorId; }
    public String getCoveringMentorId() { return coveringMentorId; }
    public void setCoveringMentorId(String coveringMentorId) { this.coveringMentorId = coveringMentorId; }
    public LocalDate getFrom() { return from; }
    public void setFrom(LocalDate from) { this.from = from; }
    public LocalDate getUntil() { return until; }
    public void setUntil(LocalDate until) { this.until = until; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getEndedBy() { return endedBy; }
    public void setEndedBy(String endedBy) { this.endedBy = endedBy; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public String getArrangedBy() { return arrangedBy; }
    public void setArrangedBy(String arrangedBy) { this.arrangedBy = arrangedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

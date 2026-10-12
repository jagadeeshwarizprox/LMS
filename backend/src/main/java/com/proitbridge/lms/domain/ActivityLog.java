package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import java.time.Instant;
import java.util.*;
/*
 * Indexes for twenty thousand learners and their logs.
 *
 * The activity log is paged newest first and filtered by who did it or which area it
 * touched; each filter has an index that also carries the sort, so a page is a range
 * read rather than a sort of the whole log in memory.
 */
@CompoundIndexes({
    @CompoundIndex(name = "created", def = "{'createdAt': -1}"),
    @CompoundIndex(name = "actor_created", def = "{'actorEmail': 1, 'createdAt': -1}"),
    @CompoundIndex(name = "entity_created", def = "{'entity': 1, 'createdAt': -1}")
})
@Document("activity_log")
public class ActivityLog {
    @Id private String id;
    private String actorId;
    private String actorEmail;
    private String action;
    private String entity;
    private String detail;
    private Instant createdAt = Instant.now();

    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public String getActorEmail() { return actorEmail; }
    public void setActorEmail(String actorEmail) { this.actorEmail = actorEmail; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getEntity() { return entity; }
    public void setEntity(String entity) { this.entity = entity; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}

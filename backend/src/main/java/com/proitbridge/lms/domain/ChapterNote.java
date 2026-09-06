package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import java.time.Instant;
import java.util.*;

/** A learner's own note against a chapter. Theirs alone, and exportable. */
@Document("chapter_notes")
public class ChapterNote {
    @Id private String id;
    @Indexed private String learnerId;
    private String chapterId;
    private String body;
    private Instant updatedAt = Instant.now();

    public String getChapterId() { return chapterId; }
    public void setChapterId(String chapterId) { this.chapterId = chapterId; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getLearnerId() { return learnerId; }
    public void setLearnerId(String learnerId) { this.learnerId = learnerId; }
}

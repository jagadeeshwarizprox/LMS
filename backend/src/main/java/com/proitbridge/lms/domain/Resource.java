package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Anything a teacher hands out: a video, notes, a notebook, a dataset, slides, a link,
 * or a paragraph of instructions.
 *
 * A topic used to hold exactly one video and nothing else, so everything else a
 * session actually produces had nowhere to live. This is that everything else.
 *
 * A resource holds exactly one of three things: a video reference, an uploaded file, or
 * a URL. Which one is decided by the kind, and the service refuses a resource that
 * carries the wrong one, because a "dataset" pointing at a video reference is a bug
 * waiting to reach a learner.
 */
@Document("resources")
public class Resource {

    @Id private String id;
    @Indexed private String topicId;

    /** VIDEO | NOTES | CODE | DATASET | SLIDES | LINK | TEXT */
    private String kind = "NOTES";
    private String title;
    private String note;              // one line under the title
    private int position;

    private String videoRef;          // VIDEO only, -> Video.id
    private String fileId;            // NOTES, CODE, DATASET, SLIDES, -> StoredFile.id
    private String url;               // LINK only
    private String body;              // TEXT only

    /** Only required resources count towards finishing the topic. */
    private boolean required = true;

    /** Pre-reading, or material released after the session. */
    private boolean beforeSession = true;

    /** A dataset yes; your slides perhaps not. */
    private boolean downloadable = true;

    private String trackScope = "BOTH";   // PREMIUM | BATCH | BOTH
    private boolean active = true;
    private Instant createdAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTopicId() { return topicId; }
    public void setTopicId(String topicId) { this.topicId = topicId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getVideoRef() { return videoRef; }
    public void setVideoRef(String videoRef) { this.videoRef = videoRef; }
    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }
    public boolean isRequired() { return required; }
    public void setRequired(boolean required) { this.required = required; }
    public boolean isBeforeSession() { return beforeSession; }
    public void setBeforeSession(boolean beforeSession) { this.beforeSession = beforeSession; }
    public boolean isDownloadable() { return downloadable; }
    public void setDownloadable(boolean downloadable) { this.downloadable = downloadable; }
    public String getTrackScope() { return trackScope; }
    public void setTrackScope(String trackScope) { this.trackScope = trackScope; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}

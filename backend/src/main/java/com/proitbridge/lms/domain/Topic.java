package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The leaf a learner actually watches: one video and the files that go with it.
 *
 * Nothing is assessed here. A topic is watched or it is not; the test, the assignment
 * and the teach back belong to the {@link Chapter} above it.
 *
 * moduleId is carried alongside chapterId because almost every read wants the module
 * without walking back up through the chapter, and a topic never moves between modules
 * without moving chapter first.
 */
@Document("topics")
public class Topic {

    @Id private String id;
    @Indexed private String chapterId;
    @Indexed private String moduleId;

    private String title;
    private String summary;
    private int position;

    private String videoRef;              // -> Video.id. No URL is ever stored on a topic.
    private int durationMin = 15;

    /**
     * Whether a code runner appears on this topic, and for which language.
     *
     * NONE by default. An LMS does not know what it is teaching: offering "try it in
     * Python" on a topic about interview technique is noise, and on one about SQL it is
     * wrong. The teacher says.
     */
    private String codeRunner = "NONE";   // NONE | PYTHON

    private boolean active = true;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getChapterId() { return chapterId; }
    public void setChapterId(String chapterId) { this.chapterId = chapterId; }
    public String getModuleId() { return moduleId; }
    public void setModuleId(String moduleId) { this.moduleId = moduleId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getVideoRef() { return videoRef; }
    public void setVideoRef(String videoRef) { this.videoRef = videoRef; }
    public int getDurationMin() { return durationMin; }
    public void setDurationMin(int durationMin) { this.durationMin = durationMin; }
    public String getCodeRunner() { return codeRunner; }
    public void setCodeRunner(String codeRunner) { this.codeRunner = codeRunner; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}

package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.util.*;

/**
 * A session that repeats. Slots are generated from this rather than a mentor
 * creating each week by hand, which is how a schedule quietly stops happening.
 */
@Document("session_schedules")
public class SessionSchedule {
    @Id private String id;
    private String kind = "GROUP_DOUBT";
    private int weekday;                 // 1 Monday to 7 Sunday
    private String startTime = "19:00";  // IST
    private int durationMin = 60;
    private String mentorId;
    private String roomId;
    private String trackScope = "BATCH";
    private String batchId;
    private int capacity = 80;
    private boolean active = true;
    private String label;

    /*
     * A learner belongs to one mentor. A live session does not: the host rotates,
     * week by week, across whoever is on the roster. Holding the roster here and
     * picking from it at generation time is what lets the same schedule be taken
     * by a different mentor each week without anybody editing it.
     */
    private List<String> hostRotation = new ArrayList<>();
    /* doubt clearing is combined across batches, so a batch id must not exclude */
    private boolean openToAllBatches;
    private String topic;
    private String speaker;
    private boolean published = true;

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public int getWeekday() { return weekday; }
    public void setWeekday(int weekday) { this.weekday = weekday; }
    public String getStartTime() { return startTime; }
    public void setStartTime(String startTime) { this.startTime = startTime; }
    public int getDurationMin() { return durationMin; }
    public void setDurationMin(int durationMin) { this.durationMin = durationMin; }
    public String getMentorId() { return mentorId; }
    public void setMentorId(String mentorId) { this.mentorId = mentorId; }
    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    public String getTrackScope() { return trackScope; }
    public void setTrackScope(String trackScope) { this.trackScope = trackScope; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public int getCapacity() { return capacity; }
    public void setCapacity(int capacity) { this.capacity = capacity; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public List<String> getHostRotation() { return hostRotation; }
    public void setHostRotation(List<String> hostRotation) { this.hostRotation = hostRotation; }
    public boolean isOpenToAllBatches() { return openToAllBatches; }
    public void setOpenToAllBatches(boolean openToAllBatches) { this.openToAllBatches = openToAllBatches; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getSpeaker() { return speaker; }
    public void setSpeaker(String speaker) { this.speaker = speaker; }
    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}

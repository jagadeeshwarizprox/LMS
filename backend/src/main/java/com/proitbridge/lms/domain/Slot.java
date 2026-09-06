package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.Instant;
import java.util.*;
@Document("slots")
public class Slot {
    @Id private String id;
    private String mentorId;
    /*
     * ONBOARDING is the premium one to one call. INDUCTION is the batch group session.
     * They were the same value, labelled "Onboarding call" on one screen and "Induction"
     * on another, which meant a premium learner booking a batch induction cleared their
     * premium gate. Two different things in the lifecycle document, two values here.
     */
    private String kind = "ONBOARDING";
    // ONBOARDING|INDUCTION|DOUBT|GROUP_DOUBT|PROJECT|LIVE|MOCK
    private Instant startsAt;
    private int durationMin = 30;
    private int capacity = 1;             // group sessions carry a larger capacity
    private String trackScope = "PREMIUM";// PREMIUM|BATCH|BOTH
    private String batchId;
    private String roomId;            // -> MeetingRoom.id. The link is fetched at click time.
    private String recordingId;
    private boolean open = true;
    private boolean cancelled;
    private String cancelReason;
    private String scheduleId;            // when generated from a recurring schedule
    private String notes;                 // what was covered, visible to attendees
    /* set from the schedule's rotation, so an occurrence knows who is taking it */
    private boolean openToAllBatches;
    private String topic;
    private String speaker;
    private boolean published = true;

    public String getMentorId() { return mentorId; }
    public void setMentorId(String mentorId) { this.mentorId = mentorId; }
    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }
    public Instant getStartsAt() { return startsAt; }
    public void setStartsAt(Instant startsAt) { this.startsAt = startsAt; }
    public int getDurationMin() { return durationMin; }
    public void setDurationMin(int durationMin) { this.durationMin = durationMin; }
    public int getCapacity() { return capacity; }
    public void setCapacity(int capacity) { this.capacity = capacity; }
    public String getTrackScope() { return trackScope; }
    public void setTrackScope(String trackScope) { this.trackScope = trackScope; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getRoomId() { return roomId; }
    public void setRoomId(String roomId) { this.roomId = roomId; }
    public String getRecordingId() { return recordingId; }
    public void setRecordingId(String recordingId) { this.recordingId = recordingId; }
    public boolean isOpen() { return open; }
    public void setOpen(boolean open) { this.open = open; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
    public String getCancelReason() { return cancelReason; }
    public void setCancelReason(String cancelReason) { this.cancelReason = cancelReason; }
    public String getScheduleId() { return scheduleId; }
    public void setScheduleId(String scheduleId) { this.scheduleId = scheduleId; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
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

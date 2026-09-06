package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
@Document("batches")
public class Batch {
    @Id private String id;
    @Indexed(unique = true) private String code;    // B56
    private String name;
    private String bundleId;
    /* A batch's mentor is fixed when the batch is made, so every learner in it goes
       to the same person. Round robin only ever applied to premium. */
    private String mentorId;
    private LocalDate startDate;                    // always a Tuesday
    private LocalDate inductionDate;
    private boolean inductionDone;
    private String inductionRecordingId;   // -> Recording.id
    private String whatsappLink;
    private boolean open = true;
    private String communityLink;
    private Instant createdAt = Instant.now();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getMentorId() { return mentorId; }
    public void setMentorId(String mentorId) { this.mentorId = mentorId; }

    public String getBundleId() { return bundleId; }
    public void setBundleId(String bundleId) { this.bundleId = bundleId; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getInductionDate() { return inductionDate; }
    public void setInductionDate(LocalDate inductionDate) { this.inductionDate = inductionDate; }
    public boolean isInductionDone() { return inductionDone; }
    public void setInductionDone(boolean inductionDone) { this.inductionDone = inductionDone; }
    public String getInductionRecordingId() { return inductionRecordingId; }
    public void setInductionRecordingId(String inductionRecordingId) { this.inductionRecordingId = inductionRecordingId; }
    public String getWhatsappLink() { return whatsappLink; }
    public void setWhatsappLink(String whatsappLink) { this.whatsappLink = whatsappLink; }
    public boolean isOpen() { return open; }
    public void setOpen(boolean open) { this.open = open; }
    public String getCommunityLink() { return communityLink; }
    public void setCommunityLink(String communityLink) { this.communityLink = communityLink; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
}

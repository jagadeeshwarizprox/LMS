package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.Indexed;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
@Document("learners")
public class Learner {
    @Id private String id;
    @Indexed(unique = true) private String userId;
    private TrackType trackType;            // PREMIUM | BATCH
    private String bundleId;                // course opted
    private String batchId;                 // batch learners only
    private String mentorId;
    private String sourceRow;               // provenance from the record sheet
    private String whatsappGroupLink;
    private LocalDate joinedOn;

    // Onboarding
    private boolean guideVideoWatched;
    private boolean gateFormDone;
    private boolean gatePrereqDone;
    private boolean gateCallDone;           // premium: slot booked+held. batch: induction
    private boolean inductionWatched;       // batch mid-joiners watch the recording
    private boolean upgradedFromBatch;

    /** On hold: no nudges, no at risk flags, modules frozen until resumed. */
    private boolean onHold;
    private String holdReason;
    private java.time.LocalDate holdSince;

    /** Modules the learner already knows, marked review only from their own intake answers. */
    private Set<String> fastForwardTopicIds = new LinkedHashSet<>();

    private Instant createdAt = Instant.now();

    public enum TrackType { PREMIUM, BATCH }

    /**
     * Whether they have actually been through onboarding.
     *
     * This is the honest state of the three gates and is what the onboarding board and
     * the mentor views read, so switching the gate off below does not make everybody
     * disappear from the list of people who still need a call.
     */
    public boolean gatesCleared() {
        return gatesCleared(true);
    }

    /**
     * The same question, with the induction optionally left out of it.
     *
     * `induction_gate` is a track feature: some cohorts run an induction that genuinely
     * blocks the course, and some hold one that is worth attending but should not stop
     * anybody studying. The toggle existed and nothing read it, so both cohorts were
     * treated the same. Callers that know the learner's features pass the answer in;
     * everything else keeps the strict reading, which is the safe default.
     */
    public boolean gatesCleared(boolean inductionCounts) {
        boolean gates = gateFormDone && gatePrereqDone && gateCallDone;
        if (trackType == TrackType.BATCH && inductionCounts) return gates && inductionWatched;
        return gates;
    }

    /**
     * Whether the course is open to them.
     *
     * No partial unlock: all gates, then modules open. Unless onboarding is not being
     * enforced, in which case the gates are a checklist on their home page rather than a
     * wall, and everything opens on first sign in.
     */
    public boolean modulesUnlocked() {
        return modulesUnlocked(true);
    }

    public boolean modulesUnlocked(boolean inductionCounts) {
        return !GatePolicy.onboardingRequired() || gatesCleared(inductionCounts);
    }

    public TrackType getTrackType() { return trackType; }
    public void setTrackType(TrackType trackType) { this.trackType = trackType; }
    public String getBundleId() { return bundleId; }
    public void setBundleId(String bundleId) { this.bundleId = bundleId; }
    public String getBatchId() { return batchId; }
    public void setBatchId(String batchId) { this.batchId = batchId; }
    public String getMentorId() { return mentorId; }
    public void setMentorId(String mentorId) { this.mentorId = mentorId; }
    public String getSourceRow() { return sourceRow; }
    public void setSourceRow(String sourceRow) { this.sourceRow = sourceRow; }
    public String getWhatsappGroupLink() { return whatsappGroupLink; }
    public void setWhatsappGroupLink(String whatsappGroupLink) { this.whatsappGroupLink = whatsappGroupLink; }
    public LocalDate getJoinedOn() { return joinedOn; }
    public void setJoinedOn(LocalDate joinedOn) { this.joinedOn = joinedOn; }
    public boolean isGuideVideoWatched() { return guideVideoWatched; }
    public void setGuideVideoWatched(boolean guideVideoWatched) { this.guideVideoWatched = guideVideoWatched; }
    public boolean isGateFormDone() { return gateFormDone; }
    public void setGateFormDone(boolean gateFormDone) { this.gateFormDone = gateFormDone; }
    public boolean isGatePrereqDone() { return gatePrereqDone; }
    public void setGatePrereqDone(boolean gatePrereqDone) { this.gatePrereqDone = gatePrereqDone; }
    public boolean isGateCallDone() { return gateCallDone; }
    public void setGateCallDone(boolean gateCallDone) { this.gateCallDone = gateCallDone; }
    public boolean isInductionWatched() { return inductionWatched; }
    public void setInductionWatched(boolean inductionWatched) { this.inductionWatched = inductionWatched; }
    public boolean isUpgradedFromBatch() { return upgradedFromBatch; }
    public void setUpgradedFromBatch(boolean upgradedFromBatch) { this.upgradedFromBatch = upgradedFromBatch; }
    public boolean isOnHold() { return onHold; }
    public void setOnHold(boolean onHold) { this.onHold = onHold; }
    public String getHoldReason() { return holdReason; }
    public void setHoldReason(String holdReason) { this.holdReason = holdReason; }
    public java.time.LocalDate getHoldSince() { return holdSince; }
    public void setHoldSince(java.time.LocalDate holdSince) { this.holdSince = holdSince; }
    public Set<String> getFastForwardTopicIds() { return fastForwardTopicIds; }
    public void setFastForwardTopicIds(Set<String> fastForwardTopicIds) { this.fastForwardTopicIds = fastForwardTopicIds; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
}

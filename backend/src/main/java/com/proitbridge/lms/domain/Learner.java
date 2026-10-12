package com.proitbridge.lms.domain;

import org.springframework.data.annotation.AccessType;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
/*
 * Indexes for the ranking and roster reads.
 *
 * A rank is "how many on my course, not on hold, are ahead of me", and a batch rank the
 * same within the batch, so both are answered by a count over these two compounds rather
 * than by loading everybody. The batch leaderboard is the batch compound read backwards.
 * Each compound also leads with its id field, so a plain lookup by course or by batch is
 * served by it too and neither needs a single-field index of its own beside it: that
 * would be a second index to keep up on every write and no query would prefer it.
 */
@CompoundIndexes({
    @CompoundIndex(name = "bundle_hold_progress", def = "{'bundleId': 1, 'onHold': 1, 'progressPercent': -1}"),
    @CompoundIndex(name = "batch_progress", def = "{'batchId': 1, 'progressPercent': -1}"),
    /* the snapshot sweep pages "stale" (progressUpdatedAt null) learners in id order */
    @CompoundIndex(name = "stale_sweep", def = "{'progressUpdatedAt': 1, '_id': 1}"),
    /* the onboarding board pages "still onboarding" in id order; counts use the prefix */
    @CompoundIndex(name = "onboarded_id", def = "{'onboarded': 1, '_id': 1}"),
    /* the at-risk and roster reads filter on hold and sort or bound on progress */
    @CompoundIndex(name = "hold_progress", def = "{'onHold': 1, 'progressPercent': -1}")
})
@Document("learners")
public class Learner {
    @Id private String id;
    @Indexed(unique = true) private String userId;
    @Indexed private TrackType trackType;   // PREMIUM | BATCH
    private String bundleId;                // course opted
    private String batchId;                 // batch learners only
    @Indexed private String mentorId;
    private String sourceRow;               // provenance from the record sheet
    private String whatsappGroupLink;
    private LocalDate joinedOn;

    // Onboarding
    private boolean guideVideoWatched;
    private boolean gateFormDone;
    private boolean gatePrereqDone;
    private boolean gateCallDone;           // premium: slot booked+held. batch: induction
    private boolean inductionWatched;       // batch mid-joiners watch the recording
    /*
     * gatesCleared(true), stored so "who is still onboarding" is one indexed field rather
     * than a four-way $or no index can serve. Property access: Mongo writes whatever
     * isOnboarded() returns, which is computed from the gates on every save, so no gate
     * setter anywhere has to remember it. The value read back is ignored for the same
     * reason. Rows written before the field existed are set by OnboardedFlagBackfill.
     */
    @AccessType(AccessType.Type.PROPERTY)
    private boolean onboarded;
    private boolean upgradedFromBatch;

    /** On hold: no nudges, no at risk flags, modules frozen until resumed. */
    private boolean onHold;
    private String holdReason;
    private java.time.LocalDate holdSince;

    /** Modules the learner already knows, marked review only from their own intake answers. */
    private Set<String> fastForwardTopicIds = new LinkedHashSet<>();

    private Instant createdAt = Instant.now();

    /*
     * Progress snapshot.
     *
     * Where the learner has got to, worked out once when something changes it and kept
     * here, rather than recomputed from their progress rows, the course tree and their
     * projects every time a page shows a percentage. A batch page used to do that for
     * every learner in the batch and every learner on the course just to print one rank,
     * which is tens of thousands of reads at the size this has to run at.
     *
     * ProgressSnapshotService is the only writer. The fields are read only to Spring Data,
     * so a whole-learner save elsewhere (a gate flag, a mentor change, a hold) can never
     * write back numbers it loaded a moment ago over fresher ones.
     *
     * ProgressSnapshotService writes them with a raw update on the collection. progressUpdatedAt null means "not
     * worked out yet, or out of date": the next read computes it, and a background sweep
     * catches up the rest. A catalogue change sets it back to null for a whole course in
     * one update rather than recomputing thousands of learners while an admin waits.
     */
    @org.springframework.data.annotation.ReadOnlyProperty
    private int progressPercent;            // watched topics over topics in the course, 0..100
    @org.springframework.data.annotation.ReadOnlyProperty
    private long watchedTopics;
    @org.springframework.data.annotation.ReadOnlyProperty
    private long totalTopics;
    @org.springframework.data.annotation.ReadOnlyProperty
    private long watchedChapters;           // chapters with every topic done
    @org.springframework.data.annotation.ReadOnlyProperty
    private long totalChapters;
    @org.springframework.data.annotation.ReadOnlyProperty
    private boolean courseHasContent;       // a published course with at least one topic
    @org.springframework.data.annotation.ReadOnlyProperty
    private Double avgQuiz;                 // mean of best chapter test scores; null if none taken
    @org.springframework.data.annotation.ReadOnlyProperty
    private long tasksApproved;
    @org.springframework.data.annotation.ReadOnlyProperty
    private long projectsApproved;
    @org.springframework.data.annotation.ReadOnlyProperty
    private Instant lastActiveAt;           // the most recent progress row touched
    @org.springframework.data.annotation.ReadOnlyProperty
    private Instant progressUpdatedAt;

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
    public boolean isOnboarded() { return gatesCleared(true); }
    public void setOnboarded(boolean ignored) { /* derived from the gates; see the field */ }
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

    public int getProgressPercent() { return progressPercent; }
    public void setProgressPercent(int progressPercent) { this.progressPercent = progressPercent; }
    public long getWatchedTopics() { return watchedTopics; }
    public void setWatchedTopics(long watchedTopics) { this.watchedTopics = watchedTopics; }
    public long getTotalTopics() { return totalTopics; }
    public void setTotalTopics(long totalTopics) { this.totalTopics = totalTopics; }
    public long getWatchedChapters() { return watchedChapters; }
    public void setWatchedChapters(long watchedChapters) { this.watchedChapters = watchedChapters; }
    public long getTotalChapters() { return totalChapters; }
    public void setTotalChapters(long totalChapters) { this.totalChapters = totalChapters; }
    public boolean isCourseHasContent() { return courseHasContent; }
    public void setCourseHasContent(boolean courseHasContent) { this.courseHasContent = courseHasContent; }
    public Double getAvgQuiz() { return avgQuiz; }
    public void setAvgQuiz(Double avgQuiz) { this.avgQuiz = avgQuiz; }
    public long getTasksApproved() { return tasksApproved; }
    public void setTasksApproved(long tasksApproved) { this.tasksApproved = tasksApproved; }
    public long getProjectsApproved() { return projectsApproved; }
    public void setProjectsApproved(long projectsApproved) { this.projectsApproved = projectsApproved; }
    public Instant getLastActiveAt() { return lastActiveAt; }
    public void setLastActiveAt(Instant lastActiveAt) { this.lastActiveAt = lastActiveAt; }
    public Instant getProgressUpdatedAt() { return progressUpdatedAt; }
    public void setProgressUpdatedAt(Instant progressUpdatedAt) { this.progressUpdatedAt = progressUpdatedAt; }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
}

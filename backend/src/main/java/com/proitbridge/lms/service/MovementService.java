package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Moving a learner: track, batch, course, or on hold.
 *
 * One rule runs through all of it. A move never deletes anything. Progress, tasks,
 * tests, projects, resumes and mentor history survive every move, because a learner
 * who changes batch has not stopped being the same person. Every move carries a
 * reason and lands in learner_moves, so six months later somebody can ask why.
 */
@Service
public class MovementService {

    private final LearnerRepository learners;
    private final UserRepository users;
    private final BatchRepository batches;
    private final BundleRepository bundles;
    private final ChapterRepository chapters;
    private final ProgressRepository progress;
    private final LearnerMoveRepository moves;
    private final MentorAssignmentRepository mentorHistory;
    private final ActivityService activity;

    public MovementService(LearnerRepository learners, UserRepository users, BatchRepository batches,
                           BundleRepository bundles, ChapterRepository chapters,
                           ProgressRepository progress, LearnerMoveRepository moves,
                           MentorAssignmentRepository mentorHistory,
                           ActivityService activity) {
        this.learners = learners; this.users = users; this.batches = batches;
        this.bundles = bundles; this.chapters = chapters; this.progress = progress;
        this.moves = moves; this.mentorHistory = mentorHistory; this.activity = activity;
    }

    private Learner require(String learnerId) {
        return learners.findById(learnerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Learner not found."));
    }

    private void need(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Moving a learner needs a reason. It stays on their record.");
        }
    }

    private void record(String learnerId, String kind, String from, String to,
                        String reason, String actorEmail) {
        LearnerMove m = new LearnerMove();
        m.setLearnerId(learnerId);
        m.setKind(kind);
        m.setFromValue(from);
        m.setToValue(to);
        m.setReason(reason);
        m.setActorEmail(actorEmail);
        moves.save(m);
        activity.log(null, actorEmail, "MOVE_" + kind, "learner",
                learnerId + " " + from + " to " + to + " (" + reason + ")");
    }

    /* ---------------------------------------------------------------- track */

    /**
     * Premium to Batch, or Batch to Premium. Features resolve from the track, so
     * nothing needs switching by hand. The induction gate only re-applies when the
     * learner lands in a batch that has not held its induction yet: making someone
     * who has been studying for a month sit through an induction is theatre.
     */
    public Learner changeTrack(String learnerId, Learner.TrackType to, String batchId,
                               String reason, String actorEmail) {
        Learner l = require(learnerId);
        need(reason);
        Learner.TrackType from = l.getTrackType();
        if (from == to && Objects.equals(l.getBatchId(), batchId)) return l;

        l.setTrackType(to);
        if (to == Learner.TrackType.PREMIUM) {
            l.setBatchId(null);
            l.setWhatsappGroupLink(null);
            if (from == Learner.TrackType.BATCH) l.setUpgradedFromBatch(true);
            // an upgrade never re-gates: they keep the onboarding they already cleared
        } else {
            Batch b = batches.findById(batchId == null ? "" : batchId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Choose the batch this learner is moving into."));
            applyBatch(l, b);
        }
        learners.save(l);
        record(learnerId, "TRACK", from.name(), to.name(), reason, actorEmail);
        return l;
    }

    /* ---------------------------------------------------------------- batch */

    /** Batch A to batch B. Cohort pace and the leaderboard recompute on their own. */
    public Learner changeBatch(String learnerId, String batchId, String reason, String actorEmail) {
        Learner l = require(learnerId);
        need(reason);
        Batch b = batches.findById(batchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch not found."));
        String from = l.getBatchId() == null ? "none"
                : batches.findById(l.getBatchId()).map(Batch::getCode).orElse("none");
        if (b.getId().equals(l.getBatchId())) return l;

        l.setTrackType(Learner.TrackType.BATCH);
        applyBatch(l, b);
        learners.save(l);
        record(learnerId, "BATCH", from, b.getCode(), reason, actorEmail);
        return l;
    }

    /**
     * Everything that follows from being in this batch.
     *
     * The mentor is the part that used to be missing. A batch has one mentor, fixed when
     * the batch was made, and every learner in it is theirs. Setting the batch without
     * setting the mentor left the learner on the new roster while still reporting to the
     * old batch's mentor, so the mentor's batch view and their learner list disagreed and
     * neither was wrong: the record was.
     */
    private void applyBatch(Learner l, Batch b) {
        l.setBatchId(b.getId());
        l.setWhatsappGroupLink(b.getWhatsappLink());
        if (b.getMentorId() != null && !b.getMentorId().isBlank()
                && !b.getMentorId().equals(l.getMentorId())) {
            MentorAssignment h = new MentorAssignment();
            h.setLearnerId(l.getId());
            h.setFromMentorId(l.getMentorId());
            h.setToMentorId(b.getMentorId());
            h.setReason("Moved into " + b.getCode() + ". A batch's mentor takes every learner in it.");
            mentorHistory.save(h);
            l.setMentorId(b.getMentorId());
        }
        if (b.isInductionDone()) {
            // the recording is the gate, so a mid batch joiner is never a second live session
            l.setInductionWatched(false);
            l.setGateCallDone(false);
        } else {
            l.setInductionWatched(false);
        }
    }

    /**
     * The Wednesday rule, in one place. A new batch starts every Tuesday; someone who
     * joins on a Wednesday goes back into the batch that has just started rather than
     * waiting six days for the next one.
     */
    public Batch batchForJoinDate(LocalDate joined) {
        List<Batch> open = batches.findAllByOrderByStartDateDesc().stream()
                .filter(Batch::isOpen).filter(b -> b.getStartDate() != null).toList();
        if (joined.getDayOfWeek() == DayOfWeek.WEDNESDAY) {
            return open.stream()
                    .filter(b -> !b.getStartDate().isAfter(joined))
                    .findFirst()
                    .orElseGet(() -> nextBatchFrom(joined, open));
        }
        return nextBatchFrom(joined, open);
    }

    private Batch nextBatchFrom(LocalDate joined, List<Batch> open) {
        LocalDate tuesday = ProvisioningService.nextTuesday(joined);
        return open.stream()
                .filter(b -> !b.getStartDate().isBefore(tuesday))
                .reduce((a, b) -> a.getStartDate().isBefore(b.getStartDate()) ? a : b)
                .orElse(open.isEmpty() ? null : open.get(0));
    }

    /* ---------------------------------------------------------------- course */

    /**
     * Changing course mid flight. Modules shared between the old course and the new one
     * keep their progress; the rest simply is not part of the roadmap any more. Nothing
     * is deleted, so moving back restores everything.
     */
    public Map<String, Object> changeBundle(String learnerId, String bundleId,
                                            String reason, String actorEmail) {
        Learner l = require(learnerId);
        need(reason);
        Bundle to = bundles.findById(bundleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Course not found."));
        Bundle from = l.getBundleId() == null ? null : bundles.findById(l.getBundleId()).orElse(null);

        Set<String> keptTopics = from == null ? Set.of()
                : from.getModuleIds().stream().filter(to.getModuleIds()::contains)
                    .collect(Collectors.toSet());

        l.setBundleId(bundleId);
        learners.save(l);
        record(learnerId, "BUNDLE", from == null ? "none" : from.getName(), to.getName(),
                reason, actorEmail);

        long carried = progress.findByLearnerId(learnerId).stream()
                .filter(p -> keptTopics.contains(p.getModuleId())).count();
        return Map.of(
                "bundle", to.getName(),
                "topicsCarriedOver", keptTopics.size(),
                "chaptersKept", carried,
                "note", "Nothing was deleted. Progress on modules outside the new course is kept "
                        + "and returns if the learner moves back.");
    }

    /* ---------------------------------------------------------------- hold */

    /** On hold: no nudges, no at risk flags, modules frozen. Life happens. */
    public Learner hold(String learnerId, String reason, String actorEmail) {
        Learner l = require(learnerId);
        need(reason);
        l.setOnHold(true);
        l.setHoldReason(reason);
        l.setHoldSince(LocalDate.now());
        learners.save(l);
        record(learnerId, "HOLD", "active", "on hold", reason, actorEmail);
        return l;
    }

    public Learner resume(String learnerId, String actorEmail) {
        Learner l = require(learnerId);
        String since = l.getHoldSince() == null ? "" : l.getHoldSince().toString();
        l.setOnHold(false);
        l.setHoldReason(null);
        l.setHoldSince(null);
        learners.save(l);
        record(learnerId, "RESUME", "on hold since " + since, "active",
                "Resumed", actorEmail);
        return l;
    }

    /* ---------------------------------------------------------------- history */

    public List<Map<String, Object>> history(String learnerId) {
        return moves.findByLearnerIdOrderByAtDesc(learnerId).stream().map(m -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("at", m.getAt());
            x.put("kind", m.getKind());
            x.put("from", m.getFromValue());
            x.put("to", m.getToValue());
            x.put("reason", m.getReason());
            x.put("by", m.getActorEmail());
            return x;
        }).collect(Collectors.toList());
    }

    /** What the move panel needs to draw itself: options, and what each one would do. */
    public Map<String, Object> options(String learnerId) {
        Learner l = require(learnerId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("trackType", l.getTrackType());
        m.put("onHold", l.isOnHold());
        m.put("holdReason", l.getHoldReason());
        m.put("currentBatch", l.getBatchId() == null ? null
                : batches.findById(l.getBatchId()).map(Batch::getCode).orElse(null));
        m.put("currentBundle", l.getBundleId() == null ? null
                : bundles.findById(l.getBundleId()).map(Bundle::getName).orElse(null));
        m.put("batches", batches.findAllByOrderByStartDateDesc().stream()
                .filter(Batch::isOpen)
                .map(b -> Map.of("id", b.getId(), "code", b.getCode(),
                        "startDate", String.valueOf(b.getStartDate()),
                        "inductionDone", b.isInductionDone(),
                        "size", learners.countByBatchId(b.getId())))
                .toList());
        m.put("bundles", bundles.findByActiveTrue().stream()
                .map(b -> Map.of("id", b.getId(), "name", b.getName(),
                        "modules", b.getModuleIds().size()))
                .toList());
        m.put("suggestedBatch", Optional.ofNullable(batchForJoinDate(LocalDate.now()))
                .map(Batch::getCode).orElse(null));
        m.put("history", history(learnerId));
        return m;
    }
}

package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Cover, for when a mentor is away.
 *
 * The learner's mentor never changes. A five day absence should not rewrite a
 * relationship that took months to build, and the learner should not open their profile
 * to a name they have never spoken to. The covering mentor simply sees the same queues
 * and records for the duration, marked as cover, and every action they take is
 * attributed to them.
 *
 * Only an admin arranges it. A mentor quietly handing their caseload to a colleague is
 * how work becomes nobody's responsibility.
 */
@Service
public class MentorCoverService {

    private final MentorCoverRepository covers;
    private final LearnerRepository learners;
    private final UserRepository users;
    private final MessageService messages;
    private final ActivityService activity;
    private final MentorHierarchyService hierarchy;
    private final BatchRepository batches;

    public MentorCoverService(MentorCoverRepository covers, LearnerRepository learners,
                              UserRepository users, MessageService messages,
                              ActivityService activity, MentorHierarchyService hierarchy,
                              BatchRepository batches) {
        this.covers = covers; this.learners = learners; this.users = users;
        this.messages = messages; this.activity = activity; this.hierarchy = hierarchy;
        this.batches = batches;
    }

    /* ------------------------------------------------------------- arranging */

    public MentorCover arrange(String mentorId, String coveringMentorId, LocalDate from,
                               LocalDate until, String reason, String actorEmail) {
        if (mentorId.equals(coveringMentorId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A mentor cannot cover for themselves.");
        }
        require(mentorId, "The mentor who is away");
        require(coveringMentorId, "The mentor covering");
        if (from == null || until == null || until.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Give the dates, and make sure the end is not before the start.");
        }
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Say why. It stays on the record.");
        }
        boolean already = covers.findByMentorIdAndActiveTrue(mentorId).stream()
                .filter(c -> c.getUntil() != null)
                .anyMatch(c -> !c.getUntil().isBefore(from));
        if (already) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This mentor already has cover arranged over those dates.");
        }

        MentorCover c = new MentorCover();
        c.setMentorId(mentorId);
        c.setCoveringMentorId(coveringMentorId);
        c.setFrom(from);
        c.setUntil(until);
        c.setReason(reason);
        c.setArrangedBy(actorEmail);
        covers.save(c);

        String away = nameOf(mentorId);
        String covering = nameOf(coveringMentorId);
        long n = learners.findByMentorId(mentorId).size();

        /* the covering mentor is told what they have picked up, because a queue that
           silently doubles is worse than no cover at all */
        messages.send(null, null, "ProITBridge",
                "You are covering for " + away,
                covering + " is covering " + away + "'s " + n + " learners from " + from
                        + " until " + until + ".", "ANNOUNCEMENT");

        activity.log(null, actorEmail, "ARRANGE_COVER", "mentor",
                covering + " covering " + away + " (" + from + " to " + until + "): " + reason);
        return c;
    }

    public void end(String coverId, String actorEmail) {
        MentorCover c = covers.findById(coverId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such cover."));
        c.setActive(false);
        c.setEndedBy(actorEmail);
        c.setEndedAt(Instant.now());
        covers.save(c);
        activity.log(null, actorEmail, "END_COVER", "mentor",
                nameOf(c.getCoveringMentorId()) + " no longer covering " + nameOf(c.getMentorId()));
    }

    /** Cover ends on its own date. Nobody should have to remember to switch it off. */
    @Scheduled(cron = "0 5 0 * * *")
    public void expire() {
        LocalDate today = LocalDate.now();
        for (MentorCover c : covers.findByActiveTrue()) {
            if (c.getUntil() != null && c.getUntil().isBefore(today)) {
                c.setActive(false);
                c.setEndedBy("expired");
                c.setEndedAt(Instant.now());
                covers.save(c);
            }
        }
    }

    /* -------------------------------------------------------------- reading */

    /**
     * Whose learners this mentor can see today: their own, anyone they are covering for
     * while the cover is running, and everyone below them in the reporting tree.
     *
     * The third one is new. A lead used to see only the learners they personally held,
     * which for most leads is none, so the whole point of a chain was unreachable.
     * Everything built on this method inherits the change, including the ownership
     * guards on a task thread, so a senior reads down their tree and nowhere else.
     */
    public List<String> mentorIdsVisibleTo(String mentorId) {
        LocalDate today = LocalDate.now();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ids.add(mentorId);
        /*
         * A cover row with no dates would throw here, and this method is the first thing
         * every mentor screen calls: the roster, the batch list and the learner record
         * all resolve visibility through it, so one bad row takes out the entire mentor
         * side at once. It is skipped instead.
         */
        covers.findByCoveringMentorIdAndActiveTrue(mentorId).stream()
                .filter(c -> c.getFrom() != null && c.getUntil() != null)
                .filter(c -> !today.isBefore(c.getFrom()) && !today.isAfter(c.getUntil()))
                .map(MentorCover::getMentorId)
                .forEach(ids::add);
        ids.addAll(hierarchy.everyoneBelow(mentorId));
        return new ArrayList<>(ids);
    }

    /**
     * Reading down the tree is not the same as owning the work. A senior may open any
     * learner below them; scoring, approving and releasing slots stay with the mentor
     * whose name is on the record, unless a cover is actually running.
     */
    public boolean canActOn(String mentorId, String ownerMentorId) {
        if (mentorId.equals(ownerMentorId)) return true;
        LocalDate today = LocalDate.now();
        return covers.findByCoveringMentorIdAndActiveTrue(mentorId).stream()
                .filter(c -> c.getFrom() != null && c.getUntil() != null)
                .filter(c -> !today.isBefore(c.getFrom()) && !today.isAfter(c.getUntil()))
                .anyMatch(c -> ownerMentorId != null && ownerMentorId.equals(c.getMentorId()));
    }

    /** The learners themselves, with whose they are, so nothing looks reassigned. */
    /**
     * Everyone this mentor may open.
     *
     * Two ways in, and it used to be one.
     *
     * The old rule was "learners whose mentorId is mine", which relies on a denormalised
     * field being right on every single record. It is not: a learner placed in a batch
     * before the mentor was carried across, or moved by any path that did not set it,
     * sits in the batch roster with somebody else's mentorId or none at all. The mentor
     * then saw a batch of thirty with twelve learners in their own list, and clicking any
     * of the other eighteen was refused.
     *
     * Being in a batch this mentor runs is the second way in, and it is the structural
     * one: a batch has a mentor, and every learner in it is theirs by definition. Reading
     * both means the screens agree with each other whatever state the stored mentorId is
     * in, and it fixes the existing records without a migration.
     */
    public List<Learner> learnersVisibleTo(String mentorId) {
        List<String> visible = mentorIdsVisibleTo(mentorId);
        Set<String> batchIds = batches.findAll().stream()
                .filter(b -> b.getMentorId() != null && visible.contains(b.getMentorId()))
                .map(Batch::getId)
                .collect(Collectors.toSet());

        Map<String, Learner> byId = new LinkedHashMap<>();
        for (String id : visible) {
            for (Learner l : learners.findByMentorId(id)) byId.put(l.getId(), l);
        }
        for (String bid : batchIds) {
            for (Learner l : learners.findByBatchId(bid)) byId.putIfAbsent(l.getId(), l);
        }
        return new ArrayList<>(byId.values());
    }

    /** The same rule for one learner, so a list and an access check cannot disagree. */
    public boolean canSee(String mentorId, Learner l) {
        List<String> visible = mentorIdsVisibleTo(mentorId);
        if (l.getMentorId() != null && visible.contains(l.getMentorId())) return true;
        if (l.getBatchId() == null) return false;
        return batches.findById(l.getBatchId())
                .map(b -> b.getMentorId() != null && visible.contains(b.getMentorId()))
                .orElse(false);
    }

    /** Whether this mentor may open a batch at all, used before its roster is handed over. */
    public boolean canSeeBatch(String mentorId, String batchId) {
        if (batchId == null) return false;
        List<String> visible = mentorIdsVisibleTo(mentorId);

        boolean ownsTheBatch = batches.findById(batchId)
                .map(b -> b.getMentorId() != null && visible.contains(b.getMentorId()))
                .orElse(false);
        if (ownsTheBatch) return true;

        /*
         * A batch is also visible when one of my learners is in it.
         *
         * The batch list already worked this way and this check did not, so a mentor was
         * shown a batch and then refused when they opened it: a batch created without a
         * mentor, or one holding a learner assigned to me directly, listed fine and gave
         * "not assigned to you" on the roster. A list and the check that guards it have
         * to be the same rule or one of them is lying, which is the same fault this class
         * already fixed once for learners.
         */
        return learners.findByBatchId(batchId).stream().anyMatch(l ->
                (l.getMentorId() != null && visible.contains(l.getMentorId())));
    }

    public boolean isCovering(String mentorId, String ownerId) {
        return !mentorId.equals(ownerId) && mentorIdsVisibleTo(mentorId).contains(ownerId);
    }

    /** What the mentor's own desk needs to say about it. */
    public Map<String, Object> stateFor(String mentorId) {
        LocalDate today = LocalDate.now();
        List<Map<String, Object>> covering = covers.findByCoveringMentorIdAndActiveTrue(mentorId).stream()
                .filter(c -> !today.isBefore(c.getFrom()) && !today.isAfter(c.getUntil()))
                .map(c -> Map.<String, Object>of(
                        "coverId", c.getId(),
                        "mentor", nameOf(c.getMentorId()),
                        "learners", learners.findByMentorId(c.getMentorId()).size(),
                        "until", String.valueOf(c.getUntil())))
                .collect(Collectors.toList());

        List<Map<String, Object>> coveredBy = covers.findByMentorIdAndActiveTrue(mentorId).stream()
                .map(c -> Map.<String, Object>of(
                        "coverId", c.getId(),
                        "mentor", nameOf(c.getCoveringMentorId()),
                        "from", String.valueOf(c.getFrom()),
                        "until", String.valueOf(c.getUntil())))
                .collect(Collectors.toList());

        return Map.of("covering", covering, "coveredBy", coveredBy);
    }

    /** The admin view: every arrangement, running or finished. */
    public List<Map<String, Object>> all() {
        LocalDate today = LocalDate.now();
        return covers.findAll().stream()
                .sorted(Comparator.comparing(MentorCover::getCreatedAt).reversed())
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("mentorId", c.getMentorId());
                    m.put("mentor", nameOf(c.getMentorId()));
                    m.put("covering", nameOf(c.getCoveringMentorId()));
                    m.put("from", String.valueOf(c.getFrom()));
                    m.put("until", String.valueOf(c.getUntil()));
                    m.put("reason", c.getReason());
                    m.put("learners", learners.findByMentorId(c.getMentorId()).size());
                    m.put("active", c.isActive());
                    m.put("running", c.isActive()
                            && !today.isBefore(c.getFrom()) && !today.isAfter(c.getUntil()));
                    m.put("arrangedBy", c.getArrangedBy());
                    return m;
                }).collect(Collectors.toList());
    }

    private void require(String id, String what) {
        User u = users.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, what + " was not found."));
        if (u.getRole() != User.Role.MENTOR) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, what + " is not a mentor.");
        }
    }

    private String nameOf(String id) {
        return users.findById(id).map(User::getFullName).orElse("Mentor");
    }
}

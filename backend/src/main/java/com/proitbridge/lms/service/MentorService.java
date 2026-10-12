package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.GroupOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class MentorService {

    private final UserRepository users;
    private final LearnerRepository learners;
    private final BatchRepository batches;
    private final BundleRepository bundles;
    private final ChapterRepository chapters;
    private final ProgressRepository progress;
    private final AssignmentRepository assignments;
    private final ProjectWorkRepository projects;
    private final MockRequestRepository mocks;
    private final ProgressCallRepository calls;
    private final SlotRepository slots;
    private final BookingRepository bookings;
    private final IntakeFormRepository intakeForms;
    private final ResumeVersionRepository resumes;
    private final QuizAttemptRepository quizAttempts;
    private final LearnerService learnerService;
    private final MailService mail;
    private final MentorCoverService cover;
    private final MentorHierarchyService hierarchy;
    private final LearnerProgressService progressView;
    private final AssignmentService assignmentService;
    private final ProgressSnapshotService snapshots;
    private final int noLoginDays;
    private final MongoTemplate mongo;

    public MentorService(UserRepository users, LearnerRepository learners, BatchRepository batches,
                         BundleRepository bundles, ChapterRepository chapters,
                         ProgressRepository progress, AssignmentRepository assignments,
                         ProjectWorkRepository projects, MockRequestRepository mocks,
                         ProgressCallRepository calls, SlotRepository slots,
                         BookingRepository bookings, IntakeFormRepository intakeForms,
                         ResumeVersionRepository resumes, QuizAttemptRepository quizAttempts,
                         LearnerService learnerService, MailService mail, MentorCoverService cover,
                         MentorHierarchyService hierarchy, LearnerProgressService progressView,
                         AssignmentService assignmentService, ProgressSnapshotService snapshots,
                         @Value("${lms.at-risk.no-login-days}") int noLoginDays,
                         MongoTemplate mongo) {
        this.mongo = mongo;
        this.users = users; this.learners = learners; this.batches = batches; this.bundles = bundles;
        this.chapters = chapters; this.progress = progress; this.assignments = assignments;
        this.projects = projects; this.mocks = mocks; this.calls = calls; this.slots = slots;
        this.bookings = bookings; this.intakeForms = intakeForms; this.resumes = resumes;
        this.quizAttempts = quizAttempts; this.learnerService = learnerService; this.mail = mail;
        this.cover = cover;
        this.hierarchy = hierarchy;
        this.progressView = progressView;
        this.assignmentService = assignmentService;
        this.snapshots = snapshots;
        this.noLoginDays = noLoginDays;
    }

    /** Own learners, plus any being covered for while a mentor is away. */
    public List<Learner> myLearners(String mentorId) {
        return cover.learnersVisibleTo(mentorId);
    }

    /**
     * The access check, using the same rule as the list.
     *
     * It used to compare the learner's stored mentorId and nothing else, while the batch
     * roster listed everybody in the batch. So a mentor was shown thirty names and
     * refused on eighteen of them, and the learner record simply never loaded. A list and
     * the check that guards it have to be the same rule or one of them is lying.
     */
    public void assertMine(String mentorId, Learner l, String role) {
        boolean staff = "ADMIN".equals(role) || "SUPER_ADMIN".equals(role);
        if (!staff && !cover.canSee(mentorId, l)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That learner is not assigned to you.");
        }
    }

    public Map<String, Object> dashboard(String mentorId) {
        List<Learner> mine = myLearners(mentorId);
        List<String> ids = mine.stream().map(Learner::getId).toList();
        /* everything the rows need for every learner, read once for the whole desk */
        Roster ctx = roster(mine, true);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("counts", Map.of(
                "total", mine.size(),
                "premium", mine.stream().filter(l -> l.getTrackType() == Learner.TrackType.PREMIUM).count(),
                "batch", mine.stream().filter(l -> l.getTrackType() == Learner.TrackType.BATCH).count(),
                /* the desk opens on two doors now, and one of them counts cohorts */
                "batches", myBatches(mentorId).size()));
        m.put("taskQueue", ids.isEmpty() ? List.of()
                : assignments.findByLearnerIdInAndStatus(ids, "SUBMITTED").stream()
                    .map(this::assignmentView).toList());
        m.put("projectQueue", ids.isEmpty() ? List.of()
                : projects.findByLearnerIdIn(ids).stream()
                    .filter(p -> "SUBMITTED".equals(p.getStatus()))
                    .map(this::projectView).toList());
        /*
         * Pending and scheduled both. A mock used to leave this list the moment it was
         * scheduled, which is why no mentor ever recorded an outcome: after scheduling it
         * there was nowhere left to go and say what happened. Held ones sort first.
         */
        m.put("mockQueue", ids.isEmpty() ? List.of()
                : mocks.findByLearnerIdInAndStatusIn(ids, List.of("PENDING", "SCHEDULED")).stream()
                    .sorted(Comparator.comparing(
                            (MockRequest r) -> "SCHEDULED".equals(r.getStatus()) ? 0 : 1)
                        .thenComparing(MockRequest::getCreatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .map(this::mockView).toList());
        m.put("atRisk", atRisk(mine, ctx));
        m.put("learners", mine.stream().map(l -> learnerRow(l, ctx)).toList());
        return m;
    }

    /**
     * Why this learner is at risk, or an empty list.
     *
     * One definition, in one place. There used to be three: this one, the list below, and
     * a third hardcoded inside the roster builder that ignored the configured threshold
     * entirely and added "no study minutes this week", which on a Monday morning is
     * everybody. A mentor setting the threshold to fourteen days and still seeing flags at
     * seven has no reason to trust anything else on the screen.
     *
     * Biweekly calls cannot be run across seventy learners, so drift is read off activity
     * the LMS already holds and surfaced as a list, not seventy rows to scan.
     */
    public List<String> riskReasons(Learner l) {
        if (l.isOnHold()) return List.of();   // on hold means left alone, not chased
        User u = users.findById(l.getUserId()).orElse(null);
        if (u == null) return List.of();
        /* a count in the database rather than every assignment row for the learner */
        long overdue = mongo.count(Query.query(overdueCriteria().and("learnerId").is(l.getId())),
                Assignment.class);
        return riskReasons(l, u, overdue);
    }

    /* overdue: a due date in the past on work not yet approved */
    private static Criteria overdueCriteria() {
        return Criteria.where("dueAt").lt(Instant.now()).and("status").ne("APPROVED");
    }

    /** Overdue task counts for a set of learners, as one grouped count. Absent means none. */
    public Map<String, Long> overdueCounts(Collection<String> learnerIds) {
        Map<String, Long> out = new HashMap<>();
        if (learnerIds == null || learnerIds.isEmpty()) return out;
        for (org.bson.Document d : aggregate(Assignment.class,
                overdueCriteria().and("learnerId").in(learnerIds),
                Aggregation.group("learnerId").count().as("n"))) {
            out.put(String.valueOf(d.get("_id")), count(d));
        }
        return out;
    }

    /** The rule itself, from numbers already in hand, so a roster can feed it in bulk. */
    public List<String> riskReasons(Learner l, User u, long overdue) {
        if (l.isOnHold() || u == null) return List.of();
        List<String> reasons = new ArrayList<>();

        long idle = u.getLastLoginAt() == null ? 999
                : ChronoUnit.DAYS.between(u.getLastLoginAt(), Instant.now());
        if (idle >= noLoginDays) {
            reasons.add(idle >= 999 ? "Never signed in" : "No sign in for " + idle + " days");
        }
        if (overdue > 0) reasons.add(overdue + " overdue task" + (overdue > 1 ? "s" : ""));

        if (!l.gatesCleared() && l.getJoinedOn() != null
                && l.getJoinedOn().plusDays(7).isBefore(java.time.LocalDate.now())) {
            reasons.add("Onboarding still open after a week");
        }
        return reasons;
    }

    public boolean isAtRisk(Learner l) {
        return !riskReasons(l).isEmpty();
    }

    public List<Map<String, Object>> atRisk(String mentorId) {
        List<Learner> mine = myLearners(mentorId);
        return atRisk(mine, roster(mine, false));
    }

    private List<Map<String, Object>> atRisk(List<Learner> mine, Roster ctx) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Learner l : mine) {
            User u = ctx.user(l.getUserId());
            List<String> reasons = riskReasons(l, u, ctx.overdue(l.getId()));
            if (reasons.isEmpty()) continue;
            if (u == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("learnerId", l.getId());
            row.put("name", u.getFullName());
            row.put("trackType", l.getTrackType());
            row.put("batch", ctx.batchCode(l.getBatchId()));
            row.put("phone", u.getPhone());
            row.put("reasons", reasons);
            out.add(row);
        }
        return out;
    }

    public Map<String, Object> learnerRow(Learner l) {
        return learnerRow(l, roster(List.of(l), true));
    }

    /** One roster row from a context loaded for the whole list, so no row reads alone. */
    private Map<String, Object> learnerRow(Learner l, Roster ctx) {
        User u = ctx.user(l.getUserId());
        Map<String, Object> s = learnerService.stats(l);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("learnerId", l.getId());
        m.put("userId", l.getUserId());
        m.put("name", u == null ? "Learner" : u.getFullName());
        m.put("email", u == null ? null : u.getEmail());
        m.put("loginId", u == null ? null : u.getLoginId());
        m.put("trackType", l.getTrackType());
        m.put("batch", ctx.batchCode(l.getBatchId()));
        m.put("bundle", ctx.bundleName(l.getBundleId()));
        m.put("percent", s.get("percent"));
        m.put("lastLoginAt", u == null ? null : u.getLastLoginAt());
        m.put("onboarded", l.gatesCleared());
        /* the register's suspend button reads these two */
        m.put("suspended", u != null && u.isSuspended());
        m.put("active", u == null || u.isActive());
        /* the register draws a mentor column and an assign button off these two: without
           them every learner reads as unassigned however many are actually assigned */
        m.put("mentorId", l.getMentorId());
        m.put("mentor", l.getMentorId() == null ? null
                : Optional.ofNullable(ctx.user(l.getMentorId())).map(User::getFullName).orElse(null));
        /* every list of learners now carries the watch picture, not just a percentage */
        m.putAll(ctx.rowStats(l));
        return m;
    }

    /**
     * The brief a mentor needs before a call, assembled instead of hunted.
     *
     * A biweekly progress call used to start with six screens open: the roadmap for what
     * they watched, the task queue for what is waiting, the tests for what they failed,
     * the last call for the goal that was set, and the doubts board for what they asked.
     * All of it already exists in the record. Nothing here is new data, it is the same
     * data ordered the way the conversation runs.
     */
    public Map<String, Object> callBrief(String mentorId, String role, String learnerId) {
        Learner l = learners.findById(learnerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Learner not found."));
        assertMine(mentorId, l, role);
        User u = users.findById(l.getUserId()).orElse(null);

        ProgressCall last = calls.findByLearnerIdOrderByScheduledForDesc(learnerId).stream()
                .filter(c -> c.getNotes() != null || c.getNextGoal() != null)
                .findFirst().orElse(null);

        List<Assignment> mine = assignments.findByLearnerId(learnerId);
        List<Map<String, Object>> waiting = mine.stream()
                .filter(a -> "SUBMITTED".equals(a.getStatus()) || "CHANGES".equals(a.getStatus()))
                .sorted(Comparator.comparing(Assignment::getSubmittedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", a.getId());
                    m.put("title", a.getTitle());
                    m.put("status", a.getStatus());
                    m.put("submissions", a.getSubmissionCount());
                    m.put("overdue", a.getDueAt() != null && a.getDueAt().isBefore(Instant.now()));
                    return m;
                }).collect(Collectors.toList());

        List<Map<String, Object>> weakTests = quizAttempts.findByLearnerId(learnerId).stream()
                /* below the pass mark is what a call is for; the rest is fine */
                .filter(q -> q.getScore() < 60)
                .sorted(Comparator.comparing(QuizAttempt::getTakenAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(5)
                .map(q -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("chapter", q.getChapterId());
                    m.put("score", q.getScore());
                    m.put("takenAt", q.getTakenAt());
                    return m;
                }).collect(Collectors.toList());

        Map<String, Object> stats = learnerService.stats(l);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", u == null ? "Learner" : u.getFullName());
        m.put("trackType", l.getTrackType());
        m.put("percent", stats.get("percent"));
        m.put("idleDays", u == null || u.getLastLoginAt() == null ? null
                : ChronoUnit.DAYS.between(u.getLastLoginAt(), Instant.now()));
        m.put("lastGoal", last == null ? null : last.getNextGoal());
        m.put("lastCallNotes", last == null ? null : last.getNotes());
        m.put("lastCallAt", last == null ? null : last.getScheduledFor());
        m.put("waiting", waiting);
        m.put("weakTests", weakTests);
        m.put("atRisk", isAtRisk(l));
        m.put("onHold", l.isOnHold());
        m.put("projectsApproved", projects.findByLearnerId(learnerId).stream()
                .filter(pr -> "APPROVED".equals(pr.getStatus())).count());
        m.put("mocksDone", mocks.findByLearnerId(learnerId).stream()
                .filter(k -> "DONE".equals(k.getStatus())).count());
        return m;
    }

    /** Six tabs: overview, background, assignments, tests, activity, notes and goals. */
    public Map<String, Object> learnerProfile(String mentorId, String role, String learnerId) {
        Learner l = learners.findById(learnerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Learner not found."));
        assertMine(mentorId, l, role);
        /*
         * A missing user row must not take the page down.
         *
         * This was orElseThrow, which is a 500 and reads to the mentor as "the record
         * will not load" with nothing saying why. Every other builder on this page copes
         * with a null user, so this one being stricter only meant it failed first.
         */
        User u = users.findById(l.getUserId()).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("overview", learnerRow(l));
        m.put("gates", learnerService.gates(l));
        m.put("background", intakeForms.findByLearnerId(learnerId).orElse(null));
        m.put("resumes", resumes.findByLearnerIdOrderByVersionDesc(learnerId));
        m.put("assignments", assignments.findByLearnerId(learnerId).stream().map(this::assignmentView).toList());
        m.put("projects", projects.findByLearnerId(learnerId).stream().map(this::projectView).toList());
        m.put("tests", quizAttempts.findByLearnerId(learnerId));
        m.put("progress", learnerService.roadmap(l));
        /*
         * The record used to draw one bar per module, which cannot answer the question a
         * mentor actually asks: has he watched the videos, and how far did he get. The
         * tree carries every topic, its watch position, its test, its task and its notes.
         */
        m.put("learning", progressView.tree(l));
        m.put("calls", calls.findByLearnerIdOrderByScheduledForDesc(learnerId));
        m.put("mocks", mocks.findByLearnerId(learnerId));
        /*
         * A LinkedHashMap, not Map.of: Map.of throws on a null value, so one learner
         * without an email would have thrown here rather than showing a blank field.
         */
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("email", u == null ? "" : u.getEmail());
        contact.put("phone", u == null || u.getPhone() == null ? "" : u.getPhone());
        contact.put("whatsapp", u == null || u.getWhatsapp() == null ? "" : u.getWhatsapp());
        m.put("contact", contact);
        return m;
    }

    /* ---------------------------------------------------------------- reviews */

    /**
     * A review from the desk.
     *
     * This wrote the assignment directly, so a review from the home screen left no event
     * on the task's thread: the learner saw a status change with no turn explaining it,
     * and the score was wiped when none was sent. It goes through the one review path the
     * task screen uses now, which records the turn, keeps an existing score and notifies.
     */
    public Assignment reviewAssignment(String mentorId, String role, String assignmentId,
                                       String status, Double score, String feedback) {
        Assignment existing = assignments.findById(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found."));
        Learner l = learners.findById(existing.getLearnerId()).orElseThrow();
        assertMine(mentorId, l, role);
        String name = users.findById(mentorId).map(User::getFullName).orElse("Your mentor");
        Assignment a = assignmentService.review(assignmentId, status, score, null, feedback,
                mentorId, name, role);

        // Premium: finishing a module opens the mock automatically.
        if ("APPROVED".equals(status) && l.getTrackType() == Learner.TrackType.PREMIUM) {
            autoOpenMockIfModuleDone(l, a.getChapterId());
        }
        return a;
    }

    private void autoOpenMockIfModuleDone(Learner l, String chapterId) {
        if (chapterId == null) return;
        Chapter c = chapters.findById(chapterId).orElse(null);
        if (c == null) return;
        boolean moduleDone = learnerService.roadmap(l).stream()
                .filter(t -> c.getModuleId().equals(t.get("moduleId")))
                .anyMatch(t -> t.get("done").equals(t.get("total")));
        if (!moduleDone) return;
        boolean already = mocks.findByLearnerId(l.getId()).stream()
                .anyMatch(m -> c.getModuleId().equals(m.getModuleId()));
        if (already) return;
        MockRequest m = new MockRequest();
        m.setLearnerId(l.getId());
        m.setModuleId(c.getModuleId());
        m.setOrigin("AUTO");
        mocks.save(m);
    }

    public ProjectWork reviewProject(String mentorId, String role, String projectId,
                                     String status, String feedback) {
        ProjectWork p = projects.findById(projectId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found."));
        Learner l = learners.findById(p.getLearnerId()).orElseThrow();
        assertMine(mentorId, l, role);
        /* the status was stored as sent, so anything at all could land on the record */
        if (!List.of("APPROVED", "CHANGES").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status must be APPROVED or CHANGES.");
        }
        p.setStatus(status);
        p.setMentorFeedback(feedback);
        p.setReviewedAt(Instant.now());
        projects.save(p);
        /* an approval, or one taken back, moves the approved projects count */
        snapshots.refresh(l);
        notify(l, "Your project was reviewed",
                "Status: " + status + "\n\n" + (feedback == null ? "" : feedback));
        return p;
    }

    public MockRequest scheduleMock(String mentorId, String role, String mockId, Instant when) {
        MockRequest m = mocks.findById(mockId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found."));
        Learner l = learners.findById(m.getLearnerId()).orElseThrow();
        assertMine(mentorId, l, role);
        m.setScheduledFor(when);
        m.setStatus("SCHEDULED");
        return mocks.save(m);
    }

    /**
     * The outcome of a mock, which is the end of the request.
     *
     * A mock with no feedback is a meeting that happened, not an assessment. The whole
     * point of the exercise is what the learner does differently next time, so the note
     * is required and it reaches them rather than sitting on the record.
     */
    public MockRequest recordMock(String mentorId, String role, String mockId, Double score, String feedback) {
        MockRequest m = mocks.findById(mockId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found."));
        Learner l = learners.findById(m.getLearnerId()).orElseThrow();
        assertMine(mentorId, l, role);
        if ("DONE".equals(m.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This mock has already been recorded.");
        }
        if (feedback == null || feedback.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Write what they should work on. A score with no note is nothing they can act on.");
        }
        if (score == null || score < 0 || score > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Score the mock out of 100.");
        }
        m.setScore(score);
        m.setFeedback(feedback.trim());
        m.setStatus("DONE");
        mocks.save(m);
        notify(l, "Your mock interview has been assessed",
                "Score: " + Math.round(score) + " out of 100.\n\n" + feedback.trim());
        return m;
    }

    /**
     * Reopening a chapter test for one of my learners.
     *
     * The ownership check is the same one every other mentor action uses, so a mentor
     * cannot clear attempts for somebody else's learner.
     */
    public Map<String, Object> reopenTestFor(String mentorId, String role,
                                             String learnerId, String chapterId) {
        Learner l = learners.findById(learnerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Learner not found."));
        assertMine(mentorId, l, role);
        Map<String, Object> out = learnerService.reopenTest(l.getId(), chapterId);
        notify(l, "Your chapter test has been reopened",
                "Your mentor has cleared your attempts on this test. You can sit it again.");
        return out;
    }

    /** Declining a request, which is a real answer and needs a reason like any other. */
    public MockRequest declineMock(String mentorId, String role, String mockId, String reason) {
        MockRequest m = mocks.findById(mockId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Request not found."));
        Learner l = learners.findById(m.getLearnerId()).orElseThrow();
        assertMine(mentorId, l, role);
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Say why, and what they should finish first.");
        }
        m.setStatus("DECLINED");
        m.setFeedback(reason.trim());
        mocks.save(m);
        notify(l, "About your mock interview request", reason.trim());
        return m;
    }

    /* ---------------------------------------------------------------- calls, slots */

    /** Logging a call schedules the next one two weeks out, so the cadence holds itself. */
    public ProgressCall logCall(String mentorId, String role, String learnerId,
                                String notes, String nextGoal) {
        Learner l = learners.findById(learnerId).orElseThrow();
        assertMine(mentorId, l, role);
        if (l.getTrackType() != Learner.TrackType.PREMIUM) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Progress calls run for premium learners. Batch drift is tracked from activity.");
        }
        /*
         * The call being logged is the one already on the calendar.
         *
         * Logging used to add a fresh "held" row and leave the scheduled one open, so
         * every call left an orphan behind it that read as due, then overdue, for ever.
         * The earliest open call is the one that just happened; it is marked held. Only
         * when nothing was scheduled is a new row written for it.
         */
        List<ProgressCall> open = calls.findByLearnerIdOrderByScheduledForDesc(learnerId).stream()
                .filter(c -> c.getHeldAt() == null)
                .sorted(Comparator.comparing(ProgressCall::getScheduledFor,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
        ProgressCall done = open.isEmpty() ? new ProgressCall() : open.get(0);
        done.setLearnerId(learnerId);
        done.setMentorId(mentorId);           // whoever actually held it, cover included
        if (done.getScheduledFor() == null) done.setScheduledFor(Instant.now());
        done.setHeldAt(Instant.now());
        done.setNotes(notes);
        done.setNextGoal(nextGoal);
        calls.save(done);

        /* another call still open means the next one is already booked; do not stack a second */
        if (open.size() > 1) return open.get(1);

        ProgressCall next = new ProgressCall();
        next.setLearnerId(learnerId);
        next.setMentorId(mentorId);
        next.setScheduledFor(Instant.now().plus(14, ChronoUnit.DAYS));
        return calls.save(next);
    }

    /*
     * releaseSlot lived here and bound onto Slot directly, which has no join link on it,
     * so every link a mentor typed into the release form was dropped by the deserialiser
     * and the session was saved with no room on it. Releasing now goes through
     * ScheduleService.createOne, the same path the week board uses, which turns a link
     * into a room pinned to the session. One creation path, one set of rules.
     */

    public List<Map<String, Object>> slotBookings(String mentorId) {
        List<Slot> mine = slots.findByMentorId(mentorId);
        /* bookings, learners and their names for every slot in three reads, not three per
           booking. BookingRepository is not ours, so the slotId $in goes through MongoTemplate */
        Set<String> slotIds = mine.stream().map(Slot::getId).collect(Collectors.toSet());
        Map<String, List<Booking>> bySlot = slotIds.isEmpty() ? Map.of()
                : mongo.find(Query.query(Criteria.where("slotId").in(slotIds)), Booking.class).stream()
                    .collect(Collectors.groupingBy(Booking::getSlotId, LinkedHashMap::new, Collectors.toList()));
        Map<String, Learner> learnerById = new HashMap<>();
        learners.findAllById(bySlot.values().stream().flatMap(List::stream).map(Booking::getLearnerId)
                        .filter(Objects::nonNull).collect(Collectors.toSet()))
                .forEach(l -> learnerById.put(l.getId(), l));
        Map<String, User> userById = usersById(learnerById.values().stream().map(Learner::getUserId)
                .collect(Collectors.toSet()));
        return mine.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slot", s);
            m.put("booked", bySlot.getOrDefault(s.getId(), List.of()).stream().map(b -> {
                Learner l = b.getLearnerId() == null ? null : learnerById.get(b.getLearnerId());
                if (l == null) return "";
                User u = userById.get(l.getUserId());
                return u == null ? "" : u.getFullName();
            }).toList());
            return m;
        }).collect(Collectors.toList());
    }

    /**
     * A batch roster with the whole progress picture, not just a name and a percentage.
     *
     * Opening the batch and then opening each learner one at a time to find out who is
     * behind is the thing this replaces. Everything a mentor asks on a Monday is in the
     * row: how far in, how long since they signed in, what is waiting to be reviewed,
     * what is overdue, and whether the sweep considers them at risk.
     */
    /**
     * Everyone in a batch.
     *
     * This had no access check at all: any mentor could read any batch's roster by
     * passing its id, including cohorts they have nothing to do with. It now goes through
     * the same visibility rule as everything else.
     */
    public List<Map<String, Object>> batchRoster(String mentorId, String role, String batchId) {
        boolean staff = "ADMIN".equals(role) || "SUPER_ADMIN".equals(role);
        if (!staff && !cover.canSeeBatch(mentorId, batchId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That batch is not yours.");
        }
        List<Learner> roster = learners.findByBatchId(batchId);
        Roster ctx = roster(roster, true);
        List<String> ids = roster.stream().map(Learner::getId).toList();
        /* the assignment and project counts for the whole batch as grouped counts, not
           every assignment row of every learner */
        Map<String, Map<String, Long>> byStatus = new HashMap<>();
        Map<String, Long> projectsApproved = new HashMap<>();
        if (!ids.isEmpty()) {
            for (org.bson.Document d : aggregate(Assignment.class, Criteria.where("learnerId").in(ids),
                    Aggregation.group("learnerId", "status").count().as("n"))) {
                Object key = d.get("_id");
                if (!(key instanceof Map<?, ?> k)) continue;
                byStatus.computeIfAbsent(String.valueOf(k.get("learnerId")), x -> new HashMap<>())
                        .put(String.valueOf(k.get("status")), count(d));
            }
            for (org.bson.Document d : aggregate(ProjectWork.class,
                    Criteria.where("learnerId").in(ids).and("status").is("APPROVED"),
                    Aggregation.group("learnerId").count().as("n"))) {
                projectsApproved.put(String.valueOf(d.get("_id")), count(d));
            }
        }
        return roster.stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>(learnerRow(l, ctx));
            User u = ctx.user(l.getUserId());
            Map<String, Long> st = byStatus.getOrDefault(l.getId(), Map.of());
            m.put("idleDays", u == null || u.getLastLoginAt() == null ? null
                    : ChronoUnit.DAYS.between(u.getLastLoginAt(), Instant.now()));
            m.put("awaitingReview", st.getOrDefault("SUBMITTED", 0L));
            m.put("changesAsked", st.getOrDefault("CHANGES", 0L));
            m.put("overdue", ctx.overdue(l.getId()));
            m.put("approved", st.getOrDefault("APPROVED", 0L));
            m.put("projectsApproved", projectsApproved.getOrDefault(l.getId(), 0L));
            m.put("onHold", l.isOnHold());
            m.put("atRisk", !riskReasons(l, u, ctx.overdue(l.getId())).isEmpty());
            return m;
        }).toList();
    }

    /**
     * The batches this mentor teaches, batch first rather than learner first.
     *
     * A mentor could reach a cohort only by scrolling their whole learner list and
     * reading the batch column, which stops working somewhere around the second batch.
     * A batch's mentor is fixed at creation, so this is a direct lookup, plus any batch
     * holding a learner they can see, which is what keeps cover and the reporting tree
     * from losing cohorts.
     */
    public List<Map<String, Object>> myBatches(String mentorId) {
        Set<String> visible = new LinkedHashSet<>(cover.mentorIdsVisibleTo(mentorId));

        /* the batches run by a visible mentor, by mentorId, instead of every batch */
        Map<String, Batch> byId = new LinkedHashMap<>();
        for (Batch b : cover.batchesRunBy(visible)) byId.put(b.getId(), b);
        Set<String> batchIds = new LinkedHashSet<>(byId.keySet());
        for (Learner l : cover.learnersVisibleTo(mentorId)) {
            if (l.getBatchId() != null) batchIds.add(l.getBatchId());
        }
        Set<String> missing = new HashSet<>(batchIds);
        missing.removeAll(byId.keySet());
        if (!missing.isEmpty()) batches.findAllById(missing).forEach(b -> byId.put(b.getId(), b));

        /* sizes and on-hold counts for every batch as two grouped counts, and the bundle
           and mentor names in one read each, instead of a roster load and two lookups per batch */
        Map<String, Long> size = new HashMap<>();
        Map<String, Long> held = new HashMap<>();
        if (!batchIds.isEmpty()) {
            for (org.bson.Document d : aggregate(Learner.class, Criteria.where("batchId").in(batchIds),
                    Aggregation.group("batchId").count().as("n"))) {
                size.put(String.valueOf(d.get("_id")), count(d));
            }
            for (org.bson.Document d : aggregate(Learner.class,
                    Criteria.where("batchId").in(batchIds).and("onHold").is(true),
                    Aggregation.group("batchId").count().as("n"))) {
                held.put(String.valueOf(d.get("_id")), count(d));
            }
        }
        Map<String, String> bundleNames = new HashMap<>();
        bundles.findAllById(byId.values().stream().map(Batch::getBundleId).filter(Objects::nonNull)
                .collect(Collectors.toSet())).forEach(x -> bundleNames.put(x.getId(), x.getName()));
        Map<String, User> mentorsById = usersById(byId.values().stream().map(Batch::getMentorId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));

        return batchIds.stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Batch::getStartDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(b -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", b.getId());
                    m.put("code", b.getCode());
                    m.put("name", b.getName());
                    m.put("startDate", b.getStartDate());
                    m.put("inductionDone", b.isInductionDone());
                    m.put("open", b.isOpen());
                    m.put("whatsappLink", b.getWhatsappLink());
                    m.put("bundle", b.getBundleId() == null ? null : bundleNames.get(b.getBundleId()));
                    m.put("mentor", b.getMentorId() == null ? null
                            : Optional.ofNullable(mentorsById.get(b.getMentorId())).map(User::getFullName).orElse(null));
                    m.put("mine", mentorId.equals(b.getMentorId()));
                    m.put("size", size.getOrDefault(b.getId(), 0L).intValue());
                    m.put("onHold", held.getOrDefault(b.getId(), 0L));
                    return m;
                })
                .toList();
    }

    /**
     * For a senior: each person reporting to them, with the shape of their load.
     *
     * The counts are what a lead actually asks for on a Monday, and gathering them by
     * message from four mentors is the thing this replaces. Nothing here is actionable;
     * opening a learner is, and that goes through the same visibility rule as everything
     * else.
     */
    public List<Map<String, Object>> team(String mentorId) {
        List<User> reports = hierarchy.directReports(mentorId);
        /* every report's learners in one $in read, and one context for all of them */
        Map<String, List<Learner>> byMentor = reports.isEmpty() ? Map.of()
                : learners.findByMentorIdIn(reports.stream().map(User::getId).toList()).stream()
                    .collect(Collectors.groupingBy(Learner::getMentorId));
        Roster ctx = roster(byMentor.values().stream().flatMap(List::stream).toList(), false);
        return reports.stream().map(u -> {
            List<Learner> theirs = byMentor.getOrDefault(u.getId(), List.of());
            long overdue = theirs.stream().mapToLong(l -> ctx.overdue(l.getId())).sum();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mentorId", u.getId());
            m.put("name", u.getFullName());
            m.put("email", u.getEmail());
            m.put("learners", theirs.size());
            m.put("onHold", theirs.stream().filter(Learner::isOnHold).count());
            m.put("atRisk", theirs.stream()
                    .filter(l -> !riskReasons(l, ctx.user(l.getUserId()), ctx.overdue(l.getId())).isEmpty())
                    .count());
            m.put("overdueTasks", overdue);
            m.put("reports", hierarchy.directReports(u.getId()).size());
            return m;
        }).toList();
    }

    /* ------------------------------------------------------------- batching */

    /**
     * What a list of roster rows needs, read once for the whole list.
     *
     * Every row used to look up its own user, batch, bundle and mentor by id, and its own
     * assignments for the overdue count, so an eighty learner desk was several hundred
     * reads. These are a handful of findAllById / grouped-count reads whatever the size.
     */
    private final class Roster {
        final Map<String, User> people;
        final Map<String, String> batchCodes = new HashMap<>();
        final Map<String, String> bundleNames = new HashMap<>();
        final Map<String, Long> overdue = new HashMap<>();
        final Map<String, Map<String, Object>> stats;

        Roster(List<Learner> ls, boolean withStats) {
            Set<String> userIds = new HashSet<>();
            Set<String> batchIds = new HashSet<>();
            Set<String> bundleIds = new HashSet<>();
            List<String> ids = new ArrayList<>();
            for (Learner l : ls) {
                if (l.getUserId() != null) userIds.add(l.getUserId());
                if (l.getMentorId() != null) userIds.add(l.getMentorId());
                if (l.getBatchId() != null) batchIds.add(l.getBatchId());
                if (l.getBundleId() != null) bundleIds.add(l.getBundleId());
                if (l.getId() != null) ids.add(l.getId());
            }
            people = usersById(userIds);
            if (!batchIds.isEmpty()) batches.findAllById(batchIds).forEach(b -> batchCodes.put(b.getId(), b.getCode()));
            if (!bundleIds.isEmpty()) bundles.findAllById(bundleIds).forEach(b -> bundleNames.put(b.getId(), b.getName()));
            overdue.putAll(overdueCounts(ids));
            stats = withStats ? progressView.rowStatsFor(ls) : Map.of();
        }

        User user(String id) { return id == null ? null : people.get(id); }
        String batchCode(String id) { return id == null ? null : batchCodes.get(id); }
        String bundleName(String id) { return id == null ? null : bundleNames.get(id); }
        long overdue(String learnerId) { return overdue.getOrDefault(learnerId, 0L); }
        Map<String, Object> rowStats(Learner l) {
            Map<String, Object> m = stats.get(l.getId());
            return m != null ? m : progressView.rowStats(l);
        }
    }

    private Roster roster(List<Learner> ls, boolean withStats) { return new Roster(ls, withStats); }

    private Map<String, User> usersById(Collection<String> ids) {
        Map<String, User> out = new HashMap<>();
        if (ids == null || ids.isEmpty()) return out;
        users.findAllById(ids).forEach(u -> out.put(u.getId(), u));
        return out;
    }

    private List<org.bson.Document> aggregate(Class<?> type, Criteria match, GroupOperation group) {
        return mongo.aggregate(Aggregation.newAggregation(Aggregation.match(match), group),
                type, org.bson.Document.class).getMappedResults();
    }

    private static long count(org.bson.Document d) {
        Object n = d.get("n");
        return n instanceof Number x ? x.longValue() : 0L;
    }

    /* ---------------------------------------------------------------- views */

    private Map<String, Object> assignmentView(Assignment a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("learnerId", a.getLearnerId());
        m.put("learner", learnerName(a.getLearnerId()));
        m.put("title", a.getTitle());
        m.put("submissionUrl", a.getSubmissionUrl());
        m.put("notes", a.getNotes());
        m.put("status", a.getStatus());
        m.put("score", a.getScore());
        m.put("mentorFeedback", a.getMentorFeedback());
        m.put("submittedAt", a.getSubmittedAt());
        m.put("dueAt", a.getDueAt());
        m.put("submissionCount", a.getSubmissionCount());
        /*
         * Which chapter's assignment this is.
         *
         * The queue listed a learner and a task title with nothing tying it to the
         * chapter it came from, so working through it meant opening each row to find
         * out what was being marked. Grouping needs a key, and the key is the chapter.
         */
        m.put("chapterId", a.getChapterId());
        m.put("chapter", a.getChapterId() == null ? null
                : chapters.findById(a.getChapterId()).map(Chapter::getTitle).orElse(null));
        return m;
    }

    private Map<String, Object> projectView(ProjectWork p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("learnerId", p.getLearnerId());
        m.put("learner", learnerName(p.getLearnerId()));
        m.put("title", p.getTitle());
        m.put("summary", p.getSummary());
        m.put("repoUrl", p.getRepoUrl());
        m.put("status", p.getStatus());
        m.put("mentorFeedback", p.getMentorFeedback());
        m.put("submittedAt", p.getSubmittedAt());
        return m;
    }

    private Map<String, Object> mockView(MockRequest r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("learnerId", r.getLearnerId());
        m.put("learner", learnerName(r.getLearnerId()));
        m.put("origin", r.getOrigin());
        m.put("status", r.getStatus());
        m.put("scheduledFor", r.getScheduledFor());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }

    private String learnerName(String learnerId) {
        return learners.findById(learnerId)
                .flatMap(l -> users.findById(l.getUserId()))
                .map(User::getFullName).orElse("Learner");
    }

    private void notify(Learner l, String subject, String body) {
        users.findById(l.getUserId()).ifPresent(u ->
                mail.send(u.getEmail(), subject, "Hello " + u.getFullName() + ",\n\n" + body, "REVIEW"));
    }
}

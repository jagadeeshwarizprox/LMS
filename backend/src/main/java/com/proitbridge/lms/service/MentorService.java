package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.beans.factory.annotation.Value;
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
    private final int noLoginDays;

    public MentorService(UserRepository users, LearnerRepository learners, BatchRepository batches,
                         BundleRepository bundles, ChapterRepository chapters,
                         ProgressRepository progress, AssignmentRepository assignments,
                         ProjectWorkRepository projects, MockRequestRepository mocks,
                         ProgressCallRepository calls, SlotRepository slots,
                         BookingRepository bookings, IntakeFormRepository intakeForms,
                         ResumeVersionRepository resumes, QuizAttemptRepository quizAttempts,
                         LearnerService learnerService, MailService mail, MentorCoverService cover,
                         MentorHierarchyService hierarchy, LearnerProgressService progressView,
                         @Value("${lms.at-risk.no-login-days}") int noLoginDays) {
        this.users = users; this.learners = learners; this.batches = batches; this.bundles = bundles;
        this.chapters = chapters; this.progress = progress; this.assignments = assignments;
        this.projects = projects; this.mocks = mocks; this.calls = calls; this.slots = slots;
        this.bookings = bookings; this.intakeForms = intakeForms; this.resumes = resumes;
        this.quizAttempts = quizAttempts; this.learnerService = learnerService; this.mail = mail;
        this.cover = cover;
        this.hierarchy = hierarchy;
        this.progressView = progressView;
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
        m.put("atRisk", atRisk(mentorId));
        m.put("learners", mine.stream().map(this::learnerRow).toList());
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
        List<String> reasons = new ArrayList<>();

        long idle = u.getLastLoginAt() == null ? 999
                : ChronoUnit.DAYS.between(u.getLastLoginAt(), Instant.now());
        if (idle >= noLoginDays) {
            reasons.add(idle >= 999 ? "Never signed in" : "No sign in for " + idle + " days");
        }
        long overdue = assignments.findByLearnerId(l.getId()).stream()
                .filter(a -> a.getDueAt() != null && a.getDueAt().isBefore(Instant.now())
                        && !"APPROVED".equals(a.getStatus()))
                .count();
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
        List<Map<String, Object>> out = new ArrayList<>();
        for (Learner l : myLearners(mentorId)) {
            List<String> reasons = riskReasons(l);
            if (reasons.isEmpty()) continue;
            User u = users.findById(l.getUserId()).orElse(null);
            if (u == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("learnerId", l.getId());
            row.put("name", u.getFullName());
            row.put("trackType", l.getTrackType());
            row.put("batch", l.getBatchId() == null ? null
                    : batches.findById(l.getBatchId()).map(Batch::getCode).orElse(null));
            row.put("phone", u.getPhone());
            row.put("reasons", reasons);
            out.add(row);
        }
        return out;
    }

    public Map<String, Object> learnerRow(Learner l) {
        User u = users.findById(l.getUserId()).orElse(null);
        Map<String, Object> s = learnerService.stats(l);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("learnerId", l.getId());
        m.put("name", u == null ? "Learner" : u.getFullName());
        m.put("email", u == null ? null : u.getEmail());
        m.put("loginId", u == null ? null : u.getLoginId());
        m.put("trackType", l.getTrackType());
        m.put("batch", l.getBatchId() == null ? null
                : batches.findById(l.getBatchId()).map(Batch::getCode).orElse(null));
        m.put("bundle", l.getBundleId() == null ? null
                : bundles.findById(l.getBundleId()).map(Bundle::getName).orElse(null));
        m.put("percent", s.get("percent"));
        m.put("lastLoginAt", u == null ? null : u.getLastLoginAt());
        m.put("onboarded", l.gatesCleared());
        /* the register draws a mentor column and an assign button off these two: without
           them every learner reads as unassigned however many are actually assigned */
        m.put("mentorId", l.getMentorId());
        m.put("mentor", l.getMentorId() == null ? null
                : users.findById(l.getMentorId()).map(User::getFullName).orElse(null));
        /* every list of learners now carries the watch picture, not just a percentage */
        m.putAll(progressView.rowStats(l));
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

    public Assignment reviewAssignment(String mentorId, String role, String assignmentId,
                                       String status, Double score, String feedback) {
        Assignment a = assignments.findById(assignmentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found."));
        Learner l = learners.findById(a.getLearnerId()).orElseThrow();
        assertMine(mentorId, l, role);
        if (!List.of("APPROVED", "CHANGES").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status must be APPROVED or CHANGES.");
        }
        a.setStatus(status);
        a.setScore(score);
        a.setMentorFeedback(feedback);
        a.setReviewedAt(Instant.now());
        assignments.save(a);

        if (a.getChapterId() != null) {
            progress.findByLearnerIdAndChapterId(l.getId(), a.getChapterId()).ifPresent(p -> {
                p.setTaskStatus(status);
                p.setUpdatedAt(Instant.now());
                progress.save(p);
            });
        }
        notify(l, "Your task was reviewed",
                "Status: " + status + "\n\n" + (feedback == null ? "" : feedback));

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
        p.setStatus(status);
        p.setMentorFeedback(feedback);
        p.setReviewedAt(Instant.now());
        projects.save(p);
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
        ProgressCall done = new ProgressCall();
        done.setLearnerId(learnerId);
        done.setMentorId(mentorId);
        done.setHeldAt(Instant.now());
        done.setScheduledFor(Instant.now());
        done.setNotes(notes);
        done.setNextGoal(nextGoal);
        calls.save(done);

        ProgressCall next = new ProgressCall();
        next.setLearnerId(learnerId);
        next.setMentorId(mentorId);
        next.setScheduledFor(Instant.now().plus(14, ChronoUnit.DAYS));
        return calls.save(next);
    }

    public Slot releaseSlot(String mentorId, Slot body) {
        body.setId(null);
        body.setMentorId(mentorId);
        body.setOpen(true);
        return slots.save(body);
    }

    public List<Map<String, Object>> slotBookings(String mentorId) {
        return slots.findByMentorId(mentorId).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("slot", s);
            m.put("booked", bookings.findBySlotId(s.getId()).stream().map(b -> {
                Learner l = learners.findById(b.getLearnerId()).orElse(null);
                return l == null ? "" : users.findById(l.getUserId()).map(User::getFullName).orElse("");
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
        return learners.findByBatchId(batchId).stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>(learnerRow(l));
            User u = users.findById(l.getUserId()).orElse(null);
            List<Assignment> mine = assignments.findByLearnerId(l.getId());
            m.put("idleDays", u == null || u.getLastLoginAt() == null ? null
                    : ChronoUnit.DAYS.between(u.getLastLoginAt(), Instant.now()));
            m.put("awaitingReview", mine.stream()
                    .filter(a -> "SUBMITTED".equals(a.getStatus())).count());
            m.put("changesAsked", mine.stream()
                    .filter(a -> "CHANGES".equals(a.getStatus())).count());
            m.put("overdue", mine.stream()
                    .filter(a -> a.getDueAt() != null && a.getDueAt().isBefore(Instant.now()))
                    .filter(a -> !"APPROVED".equals(a.getStatus())).count());
            m.put("approved", mine.stream()
                    .filter(a -> "APPROVED".equals(a.getStatus())).count());
            m.put("projectsApproved", projects.findByLearnerId(l.getId()).stream()
                    .filter(pr -> "APPROVED".equals(pr.getStatus())).count());
            m.put("onHold", l.isOnHold());
            m.put("atRisk", isAtRisk(l));
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

        Set<String> batchIds = new LinkedHashSet<>();
        for (Batch b : batches.findAll()) {
            if (b.getMentorId() != null && visible.contains(b.getMentorId())) batchIds.add(b.getId());
        }
        for (Learner l : cover.learnersVisibleTo(mentorId)) {
            if (l.getBatchId() != null) batchIds.add(l.getBatchId());
        }

        return batchIds.stream()
                .map(id -> batches.findById(id).orElse(null))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(Batch::getStartDate,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(b -> {
                    List<Learner> roster = learners.findByBatchId(b.getId());
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", b.getId());
                    m.put("code", b.getCode());
                    m.put("name", b.getName());
                    m.put("startDate", b.getStartDate());
                    m.put("inductionDone", b.isInductionDone());
                    m.put("open", b.isOpen());
                    m.put("whatsappLink", b.getWhatsappLink());
                    m.put("bundle", b.getBundleId() == null ? null
                            : bundles.findById(b.getBundleId()).map(Bundle::getName).orElse(null));
                    m.put("mentor", b.getMentorId() == null ? null
                            : users.findById(b.getMentorId()).map(User::getFullName).orElse(null));
                    m.put("mine", mentorId.equals(b.getMentorId()));
                    m.put("size", roster.size());
                    m.put("onHold", roster.stream().filter(Learner::isOnHold).count());
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
        return hierarchy.directReports(mentorId).stream().map(u -> {
            List<Learner> theirs = learners.findByMentorId(u.getId());
            Set<String> ids = theirs.stream().map(Learner::getId).collect(Collectors.toSet());
            long overdue = ids.isEmpty() ? 0 : assignments.findByLearnerIdIn(ids).stream()
                    .filter(a -> a.getDueAt() != null && a.getDueAt().isBefore(Instant.now()))
                    .filter(a -> !"APPROVED".equals(a.getStatus()))
                    .count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mentorId", u.getId());
            m.put("name", u.getFullName());
            m.put("email", u.getEmail());
            m.put("learners", theirs.size());
            m.put("onHold", theirs.stream().filter(Learner::isOnHold).count());
            m.put("atRisk", theirs.stream().filter(this::isAtRisk).count());
            m.put("overdueTasks", overdue);
            m.put("reports", hierarchy.directReports(u.getId()).size());
            return m;
        }).toList();
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

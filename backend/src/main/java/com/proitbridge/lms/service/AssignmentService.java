package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tasks that a mentor can actually create, and a thread that never loses anything.
 *
 * Two things were wrong before. A mentor could only review work a chapter had already
 * generated, so there was no way to set a piece of work for one learner or a cohort.
 * And a resubmission overwrote the last one, so the conversation around a task, which
 * is most of the teaching, simply vanished. Every submission and every review is now
 * an event on a thread.
 *
 * Due dates are set here too. The at-risk sweep has been counting overdue tasks since
 * the beginning against a field nothing ever populated.
 */
@Service
public class AssignmentService {

    private final AssignmentRepository assignments;
    private final AssignmentEventRepository events;
    private final RubricRepository rubrics;
    private final LearnerRepository learners;
    private final UserRepository users;
    private final BatchRepository batches;
    private final ProgressRepository progress;
    private final ChapterRepository chapters;
    private final MailService mail;
    private final ActivityService activity;
    private final MessageService messages;
    private final MentorCoverService cover;
    private final FileService fileService;

    public AssignmentService(AssignmentRepository assignments, AssignmentEventRepository events,
                             RubricRepository rubrics, LearnerRepository learners,
                             UserRepository users, BatchRepository batches,
                             ProgressRepository progress, ChapterRepository chapters,
                             MailService mail, ActivityService activity, MessageService messages,
                             MentorCoverService cover,
                             FileService fileService) {
        this.assignments = assignments; this.events = events; this.rubrics = rubrics;
        this.learners = learners; this.users = users; this.batches = batches;
        this.progress = progress; this.chapters = chapters; this.mail = mail;
        this.activity = activity; this.messages = messages; this.cover = cover; this.fileService = fileService;
    }

    /* ---------------------------------------------------------------- creating */

    public record NewTask(String title, String brief, Instant dueAt, String rubricId,
                          List<String> learnerIds, String batchId, String trackScope) {}

    /** One learner, several, or a whole batch. The same task, one row each. */
    public Map<String, Object> assign(NewTask body, String mentorId, String mentorName) {
        if (body.title() == null || body.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the task a title.");
        }
        Set<String> targets = new LinkedHashSet<>();
        if (body.learnerIds() != null) targets.addAll(body.learnerIds());
        if (body.batchId() != null && !body.batchId().isBlank()) {
            learners.findByBatchId(body.batchId()).forEach(l -> targets.add(l.getId()));
        }
        if (targets.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choose at least one learner, or a batch.");
        }

        List<String> created = new ArrayList<>();
        for (String learnerId : targets) {
            Learner l = learners.findById(learnerId).orElse(null);
            if (l == null || l.isOnHold()) continue;    // on hold means left alone
            Assignment a = new Assignment();
            a.setLearnerId(learnerId);
            a.setTitle(body.title());
            a.setBrief(body.brief());
            a.setDueAt(body.dueAt());
            a.setRubricId(body.rubricId());
            a.setOrigin("ADHOC");
            a.setAssignedBy(mentorId);
            a.setStatus("ASSIGNED");
            a.setSubmittedAt(null);
            assignments.save(a);
            created.add(a.getId());

            event(a.getId(), "ASSIGNED", mentorId, mentorName, "MENTOR",
                    body.brief(), null, null, null, null);
            notify(l, "New task: " + body.title(),
                    (body.brief() == null ? "" : body.brief() + "\n\n")
                    + (body.dueAt() == null ? "" : "Due " + body.dueAt().toString().substring(0, 10)),
                    "NUDGE", mentorId, mentorName);
        }
        activity.log(mentorId, null, "ASSIGN_TASK", "assignment",
                body.title() + " to " + created.size() + " learners");
        return Map.of("created", created.size(), "ids", created);
    }

    public Assignment setDue(String assignmentId, Instant dueAt, String mentorId, String mentorName) {
        Assignment a = require(assignmentId);
        a.setDueAt(dueAt);
        assignments.save(a);
        event(assignmentId, "DUE_CHANGED", mentorId, mentorName, "MENTOR",
                dueAt == null ? "Due date removed" : "Due " + dueAt, null, null, null, null);
        return a;
    }

    /* ---------------------------------------------------------------- submitting */

    /** A resubmission is a new turn on the thread, not a replacement of the last one. */
    public Assignment submit(String assignmentId, String learnerId, String learnerName,
                             String url, String fileId, String notes) {
        return submit(assignmentId, learnerId, learnerName, url,
                fileId == null ? List.of() : List.of(fileId), notes);
    }

    public Assignment submit(String assignmentId, String learnerId, String learnerName,
                             String url, List<String> fileIds, String notes) {
        Assignment a = requireOwnedBy(assignmentId, learnerId);
        List<String> files = fileIds == null ? List.of()
                : fileIds.stream().filter(f -> f != null && !f.isBlank()).toList();
        String fileId = files.isEmpty() ? null : files.get(0);
        a.setSubmissionUrl(url);
        a.setFileId(fileId);
        a.setFileIds(new ArrayList<>(files));
        a.setNotes(notes);
        a.setStatus("SUBMITTED");
        a.setSubmittedAt(Instant.now());
        a.setSubmissionCount(a.getSubmissionCount() + 1);
        assignments.save(a);
        event(assignmentId, "SUBMITTED", learnerId, learnerName, "LEARNER",
                notes, url, fileId, null, null, files);
        syncProgress(a, "SUBMITTED");
        return a;
    }

    /* ---------------------------------------------------------------- reviewing */

    public Assignment review(String assignmentId, String status, Double score,
                             Map<String, Double> rubricScores, String feedback,
                             String mentorId, String mentorName, String mentorRole) {
        if (!List.of("APPROVED", "CHANGES").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A review is either APPROVED or CHANGES.");
        }
        Assignment a = requireVisibleTo(assignmentId, mentorId, mentorRole);

        /* when a rubric is attached the overall score is derived from it, so two
           mentors scoring the same criteria land in the same place */
        Double finalScore = score;
        if (rubricScores != null && !rubricScores.isEmpty() && a.getRubricId() != null) {
            Rubric r = rubrics.findById(a.getRubricId()).orElse(null);
            if (r != null) {
                double weighted = 0, weight = 0;
                for (Rubric.Criterion c : r.getCriteria()) {
                    Double v = rubricScores.get(c.getKey());
                    if (v == null) continue;
                    weighted += v * c.getWeight();
                    weight += c.getWeight();
                }
                if (weight > 0) finalScore = Math.round(weighted / weight * 10) / 10.0;
            }
            a.setRubricScores(new LinkedHashMap<>(rubricScores));
        }

        a.setStatus(status);
        a.setScore(finalScore);
        a.setMentorFeedback(feedback);
        a.setReviewedAt(Instant.now());
        assignments.save(a);
        event(assignmentId, "REVIEWED", mentorId, mentorName, "MENTOR",
                feedback, null, null, status, finalScore);
        syncProgress(a, status);

        learners.findById(a.getLearnerId()).ifPresent(l -> notify(l,
                "Your task was reviewed",
                "Status: " + ("APPROVED".equals(status) ? "approved" : "changes asked")
                        + (feedback == null ? "" : "\n\n" + feedback),
                "MESSAGE", mentorId, mentorName));
        return a;
    }

    /** Several at once, with one comment. Four approvals should not be four screens. */
    public Map<String, Object> bulkReview(List<String> ids, String status, String feedback,
                                          String mentorId, String mentorName, String mentorRole) {
        int n = 0;
        for (String id : ids) {
            try { review(id, status, null, null, feedback, mentorId, mentorName, mentorRole); n++; }
            catch (Exception ignored) { /* one bad row should not stop the rest */ }
        }
        return Map.of("reviewed", n);
    }

    /* ------------------------------------------------------ who may touch a task */

    /*
     * A task belongs to one learner and to the staff above them. Nothing checked that:
     * thread(), comment() and review() all went through require() alone, so an id
     * lifted from anywhere read back another learner's submissions, their mentor's
     * feedback, their score and the file id behind it, and let anyone comment on it.
     * submit() was the only one that got this right, and these follow its wording.
     */

    private Assignment requireOwnedBy(String assignmentId, String learnerId) {
        Assignment a = require(assignmentId);
        if (!a.getLearnerId().equals(learnerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That task is not yours.");
        }
        return a;
    }

    /**
     * An admin sees every learner by definition. A mentor sees their own and anyone
     * they are covering, which is the same set the desk is built from, so cover
     * arrangements keep working without a second rule.
     */
    private Assignment requireVisibleTo(String assignmentId, String staffId, String staffRole) {
        Assignment a = require(assignmentId);
        if ("ADMIN".equals(staffRole) || "SUPER_ADMIN".equals(staffRole)) return a;
        boolean mine = cover.learnersVisibleTo(staffId).stream()
                .anyMatch(l -> l.getId().equals(a.getLearnerId()));
        if (!mine) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That learner is not yours.");
        }
        return a;
    }

    public void commentAsLearner(String assignmentId, String body, String learnerId,
                                 String actorId, String actorName) {
        requireOwnedBy(assignmentId, learnerId);
        event(assignmentId, "COMMENT", actorId, actorName, "LEARNER", body, null, null, null, null);
    }

    public void commentAsStaff(String assignmentId, String body, String actorId,
                               String actorName, String staffRole) {
        requireVisibleTo(assignmentId, actorId, staffRole);
        event(assignmentId, "COMMENT", actorId, actorName, "MENTOR", body, null, null, null, null);
    }

    /* ---------------------------------------------------------------- reading */

    public Map<String, Object> threadForLearner(String assignmentId, String learnerId) {
        requireOwnedBy(assignmentId, learnerId);
        return thread(assignmentId);
    }

    public Map<String, Object> threadForStaff(String assignmentId, String staffId, String staffRole) {
        requireVisibleTo(assignmentId, staffId, staffRole);
        return thread(assignmentId);
    }

    private Map<String, Object> thread(String assignmentId) {
        Assignment a = require(assignmentId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("assignment", view(a));
        m.put("events", events.findByAssignmentIdOrderByAtAsc(assignmentId).stream().map(e -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("kind", e.getKind());
            x.put("actor", e.getActorName());
            x.put("role", e.getActorRole());
            x.put("body", e.getBody());
            x.put("submissionUrl", e.getSubmissionUrl());
            x.put("fileId", e.getFileId());
            x.put("fileIds", e.getFileIds());
            x.put("status", e.getStatus());
            x.put("score", e.getScore());
            x.put("at", e.getAt());
            return x;
        }).collect(Collectors.toList()));
        m.put("rubric", a.getRubricId() == null ? null : rubrics.findById(a.getRubricId()).orElse(null));

        /*
         * The material the task was set from.
         *
         * The thread carried the brief text and the learner's upload, and not the dataset
         * or the rubric document the work was written against. Marking a notebook without
         * the question in front of you is guesswork, so the chapter's own attachments
         * come through here too.
         */
        if ("CHAPTER".equals(a.getOrigin()) && a.getChapterId() != null) {
            chapters.findById(a.getChapterId()).ifPresent(c -> {
                m.put("chapterTitle", c.getTitle());
                m.put("briefFiles", fileService.viewsOf(c.getAssignment().getFileIds()));
                m.put("maxMarks", c.getAssignment().getMarks());
            });
        }
        return m;
    }

    /** Everything late across a mentor's learners, which nothing could show before. */
    public List<Map<String, Object>> overdue(String mentorId) {
        Set<String> mine = cover.learnersVisibleTo(mentorId).stream()
                .filter(l -> !l.isOnHold())
                .map(Learner::getId).collect(Collectors.toSet());
        Instant now = Instant.now();
        return assignments.findAll().stream()
                .filter(a -> mine.contains(a.getLearnerId()))
                .filter(a -> a.getDueAt() != null && a.getDueAt().isBefore(now))
                .filter(a -> !"APPROVED".equals(a.getStatus()))
                .sorted(Comparator.comparing(Assignment::getDueAt))
                .map(a -> {
                    Map<String, Object> m = view(a);
                    m.put("daysLate", ChronoUnit.DAYS.between(a.getDueAt(), now));
                    return m;
                }).collect(Collectors.toList());
    }

    public List<Rubric> rubricsFor(String scope) {
        return rubrics.findByScopeAndActiveTrue(scope);
    }

    public Rubric saveRubric(Rubric r) { return rubrics.save(r); }

    private Map<String, Object> view(Assignment a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.getId());
        m.put("learnerId", a.getLearnerId());
        m.put("learner", learners.findById(a.getLearnerId())
                .flatMap(l -> users.findById(l.getUserId()))
                .map(User::getFullName).orElse("Learner"));
        m.put("title", a.getTitle());
        m.put("brief", a.getBrief());
        m.put("origin", a.getOrigin());
        m.put("status", a.getStatus());
        m.put("score", a.getScore());
        m.put("rubricId", a.getRubricId());
        m.put("rubricScores", a.getRubricScores());
        m.put("mentorFeedback", a.getMentorFeedback());
        m.put("submissionUrl", a.getSubmissionUrl());
        m.put("fileId", a.getFileId());
        m.put("fileIds", a.getFileIds());
        m.put("submittedAt", a.getSubmittedAt());
        m.put("reviewedAt", a.getReviewedAt());
        m.put("dueAt", a.getDueAt());
        m.put("submissionCount", a.getSubmissionCount());
        m.put("maxMarks", a.getMaxMarks());
        /* what the learner handed in, with names rather than ids */
        m.put("files", fileService.viewsOf(a.getFileIds()));
        return m;
    }

    private Assignment require(String id) {
        return assignments.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Task not found."));
    }

    private void syncProgress(Assignment a, String status) {
        if (a.getChapterId() == null) return;
        progress.findByLearnerIdAndChapterId(a.getLearnerId(), a.getChapterId()).ifPresent(p -> {
            p.setTaskStatus(status);
            p.setUpdatedAt(Instant.now());
            progress.save(p);
        });
    }

    private void event(String assignmentId, String kind, String actorId, String actorName,
                       String role, String body, String url, String fileId,
                       String status, Double score) {
        event(assignmentId, kind, actorId, actorName, role, body, url, fileId, status, score,
                fileId == null ? List.of() : List.of(fileId));
    }

    private void event(String assignmentId, String kind, String actorId, String actorName,
                       String role, String body, String url, String fileId,
                       String status, Double score, List<String> fileIds) {
        AssignmentEvent e = new AssignmentEvent();
        e.setAssignmentId(assignmentId);
        e.setKind(kind);
        e.setActorId(actorId);
        e.setActorName(actorName);
        e.setActorRole(role);
        e.setBody(body);
        e.setSubmissionUrl(url);
        e.setFileId(fileId);
        e.setFileIds(new ArrayList<>(fileIds == null ? List.of() : fileIds));
        e.setStatus(status);
        e.setScore(score);
        events.save(e);
    }

    private void notify(Learner l, String subject, String body, String kind,
                        String fromId, String fromName) {
        messages.send(l.getId(), fromId, fromName, subject, body, kind);
    }
}

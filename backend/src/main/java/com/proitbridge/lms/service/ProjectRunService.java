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
 * Running a project: enrolment, the stage rail, submission, and the mentor gate.
 *
 * The rule that shapes everything here is that a stage only advances when a mentor says
 * so. Everything else follows from it: a learner cannot reach the demo with nothing
 * built, a redo is just a stage being sent back rather than a special case, and a batch
 * grid is meaningful because every position on it was decided by a person.
 */
@Service
public class ProjectRunService {

    private final ProjectRepository projects;
    private final ProjectRunRepository runs;
    private final LearnerRepository learners;
    private final UserRepository users;
    private final RubricRepository rubrics;
    private final MockRequestRepository mocks;
    private final MailService mail;

    public ProjectRunService(ProjectRepository projects, ProjectRunRepository runs,
                             LearnerRepository learners, UserRepository users,
                             RubricRepository rubrics, MockRequestRepository mocks,
                             MailService mail) {
        this.projects = projects; this.runs = runs; this.learners = learners;
        this.users = users; this.rubrics = rubrics; this.mocks = mocks; this.mail = mail;
    }

    /** Controllers hold a user id; everything in here is keyed on the learner row. */
    public String learnerIdFor(String userId) {
        return learners.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No learner record for this account."))
                .getId();
    }

    /* ------------------------------------------------------------------ enrolment */

    /**
     * Put a learner on a project.
     *
     * The first stage opens immediately, because a brief nobody can read is not a
     * kickoff. Every other stage is locked until the one before it is approved.
     */
    public ProjectRun enrol(String learnerId, String projectId) {
        Project p = project(projectId);
        Learner l = learners.findById(learnerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Learner not found."));

        Optional<ProjectRun> existing = runs.findByLearnerIdAndProjectId(learnerId, projectId);
        if (existing.isPresent()) return existing.get();

        ProjectRun r = new ProjectRun();
        r.setLearnerId(learnerId);
        r.setProjectId(projectId);
        r.setMentorId(l.getMentorId());
        r.setBatchId(l.getBatchId());

        Instant now = Instant.now();
        boolean first = true;
        for (Project.Stage s : p.getStages()) {
            ProjectRun.StageState st = new ProjectRun.StageState();
            st.setDueAt(now.plus(s.getDueOffsetDays(), ChronoUnit.DAYS));
            if (first) {
                st.setStatus("OPEN");
                st.setOpenedAt(now);
                r.setCurrentStage(s.getKey());
                first = false;
            }
            r.getStages().put(s.getKey(), st);
        }
        runs.save(r);

        notify(l, "You have been put on a project",
                p.getTitle() + "\n\nOpen Projects in your LMS to read the brief and get the data.");
        return r;
    }

    /** A whole cohort onto one project, which is how a batch project actually starts. */
    public Map<String, Object> enrolBatch(String batchId, String projectId) {
        List<Learner> cohort = learners.findByBatchId(batchId);
        int n = 0;
        for (Learner l : cohort) {
            if (runs.findByLearnerIdAndProjectId(l.getId(), projectId).isEmpty()) {
                enrol(l.getId(), projectId);
                n++;
            }
        }
        return Map.of("enrolled", n, "cohort", cohort.size());
    }

    /* ------------------------------------------------------------------- learner */

    public List<Map<String, Object>> myRuns(String learnerId) {
        return runs.findByLearnerId(learnerId).stream()
                .map(r -> view(r, false))
                .collect(Collectors.toList());
    }

    public Map<String, Object> run(String learnerId, String runId) {
        ProjectRun r = runs.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such project run."));
        if (!r.getLearnerId().equals(learnerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That is not your project.");
        }
        return view(r, true);
    }

    /**
     * Hand in a stage.
     *
     * A stage sent back for changes accepts another attempt without any special path:
     * the attempt is appended, the version goes up, and the mentor sees both. Nothing a
     * learner sent before is ever replaced.
     */
    public Map<String, Object> submit(String learnerId, String runId, String stageKey,
                                      Map<String, Object> body) {
        ProjectRun r = runs.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such project run."));
        if (!r.getLearnerId().equals(learnerId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "That is not your project.");
        }
        ProjectRun.StageState st = r.getStages().get(stageKey);
        if (st == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such stage on this project.");
        }
        if ("LOCKED".equals(st.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Finish the stage before this one first.");
        }
        if ("APPROVED".equals(st.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This stage is already approved.");
        }

        ProjectRun.Attempt a = new ProjectRun.Attempt();
        a.setVersion(st.getAttempts().size() + 1);
        a.setNotes(str(body, "notes"));
        a.setRepoUrl(str(body, "repoUrl"));
        a.setDemoUrl(str(body, "demoUrl"));
        Object files = body.get("fileIds");
        if (files instanceof List<?> list) {
            a.setFileIds(list.stream().map(String::valueOf).collect(Collectors.toList()));
        }
        st.getAttempts().add(a);
        st.setStatus("SUBMITTED");
        st.setSubmittedAt(Instant.now());
        runs.save(r);

        return Map.of("status", st.getStatus(), "version", a.getVersion());
    }

    /* -------------------------------------------------------------------- mentor */

    /** Every stage waiting on a review, oldest first, which is the order to work in. */
    public List<Map<String, Object>> reviewQueue(List<String> learnerIds) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProjectRun r : runs.findByLearnerIdIn(learnerIds)) {
            Project p = projects.findById(r.getProjectId()).orElse(null);
            if (p == null) continue;
            for (Project.Stage s : p.getStages()) {
                ProjectRun.StageState st = r.getStages().get(s.getKey());
                if (st == null || !"SUBMITTED".equals(st.getStatus())) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("runId", r.getId());
                m.put("stageKey", s.getKey());
                m.put("stageName", s.getName());
                m.put("project", p.getTitle());
                m.put("learnerId", r.getLearnerId());
                m.put("learner", learnerName(r.getLearnerId()));
                m.put("submittedAt", st.getSubmittedAt());
                m.put("version", st.getAttempts().size());
                m.put("waitingDays", st.getSubmittedAt() == null ? 0
                        : ChronoUnit.DAYS.between(st.getSubmittedAt(), Instant.now()));
                m.put("overdue", st.getDueAt() != null && st.getDueAt().isBefore(Instant.now()));
                out.add(m);
            }
        }
        out.sort(Comparator.comparing(m -> (Instant) m.getOrDefault("submittedAt", Instant.now())));
        return out;
    }

    /**
     * The gate.
     *
     * Approving opens the next stage. Sending back reopens this one and requires a
     * reason, because a rejection with no note is not something anybody can act on.
     */
    public Map<String, Object> review(String mentorId, String runId, String stageKey,
                                      String outcome, Double score, String feedback,
                                      Map<String, Double> rubricScores) {
        if (!List.of("APPROVED", "CHANGES").contains(outcome)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The outcome is either APPROVED or CHANGES.");
        }
        if ("CHANGES".equals(outcome) && (feedback == null || feedback.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Say what needs changing. They cannot act on a rejection with no note.");
        }

        ProjectRun r = runs.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such project run."));
        Project p = project(r.getProjectId());
        ProjectRun.StageState st = r.getStages().get(stageKey);
        if (st == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such stage on this project.");
        }

        st.setStatus(outcome);
        st.setScore(score);
        st.setFeedback(feedback);
        st.setReviewedAt(Instant.now());
        st.setReviewedBy(mentorId);
        if (rubricScores != null) st.setRubricScores(rubricScores);

        /* the outcome is copied onto the attempt so the history reads in one place */
        if (!st.getAttempts().isEmpty()) {
            ProjectRun.Attempt last = st.getAttempts().get(st.getAttempts().size() - 1);
            last.setOutcome(outcome);
            last.setFeedback(feedback);
        }

        Learner l = learners.findById(r.getLearnerId()).orElse(null);

        if ("APPROVED".equals(outcome)) {
            String next = nextStageKey(p, stageKey);
            if (next == null) {
                r.setStatus("APPROVED");
                r.setApprovedAt(Instant.now());
                r.setCurrentStage(stageKey);
                if (l != null) openMockIfEarned(l);
            } else {
                ProjectRun.StageState ns = r.getStages().get(next);
                if (ns != null && "LOCKED".equals(ns.getStatus())) {
                    ns.setStatus("OPEN");
                    ns.setOpenedAt(Instant.now());
                }
                r.setCurrentStage(next);
            }
        } else {
            r.setCurrentStage(stageKey);
        }
        runs.save(r);

        if (l != null) {
            String stageName = p.getStages().stream()
                    .filter(s -> s.getKey().equals(stageKey))
                    .map(Project.Stage::getName).findFirst().orElse(stageKey);
            notify(l, "APPROVED".equals(outcome)
                            ? "Stage approved: " + stageName
                            : "Changes asked on: " + stageName,
                    (feedback == null ? "" : feedback)
                            + "\n\nOpen Projects in your LMS to see the review.");
        }

        return Map.of("status", outcome, "currentStage", String.valueOf(r.getCurrentStage()),
                "runStatus", r.getStatus());
    }

    /**
     * Every learner in a batch against every stage.
     *
     * This is the view that makes a cohort project runnable: who is stuck at scoping in
     * week three is a question a mentor should answer by looking, not by opening
     * twenty records.
     */
    public Map<String, Object> batchGrid(String batchId, String projectId) {
        Project p = project(projectId);
        List<ProjectRun> cohort = runs.findByBatchId(batchId).stream()
                .filter(r -> r.getProjectId().equals(projectId))
                .collect(Collectors.toList());

        List<Map<String, Object>> rows = cohort.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runId", r.getId());
            m.put("learnerId", r.getLearnerId());
            m.put("learner", learnerName(r.getLearnerId()));
            m.put("status", r.getStatus());
            m.put("currentStage", r.getCurrentStage());
            m.put("stages", p.getStages().stream().map(s -> {
                ProjectRun.StageState st = r.getStages().get(s.getKey());
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("key", s.getKey());
                c.put("status", st == null ? "LOCKED" : st.getStatus());
                c.put("overdue", st != null && st.getDueAt() != null
                        && !"APPROVED".equals(st.getStatus())
                        && st.getDueAt().isBefore(Instant.now()));
                c.put("score", st == null ? null : st.getScore());
                return c;
            }).collect(Collectors.toList()));
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("project", Map.of("id", p.getId(), "title", p.getTitle()));
        out.put("stages", p.getStages().stream()
                .map(s -> Map.of("key", s.getKey(), "name", s.getName()))
                .collect(Collectors.toList()));
        out.put("rows", rows);
        out.put("waiting", rows.stream().filter(r -> castStages(r).stream()
                .anyMatch(c -> "SUBMITTED".equals(c.get("status")))).count());
        out.put("approved", rows.stream().filter(r -> "APPROVED".equals(r.get("status"))).count());
        return out;
    }

    /* ------------------------------------------------------------------ assembly */

    /** The learner-facing shape: the brief, the rail, and the stage they are on. */
    private Map<String, Object> view(ProjectRun r, boolean full) {
        Project p = projects.findById(r.getProjectId()).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runId", r.getId());
        m.put("projectId", r.getProjectId());
        m.put("title", p == null ? "Project" : p.getTitle());
        m.put("subtitle", p == null ? null : p.getSubtitle());
        m.put("status", r.getStatus());
        m.put("currentStage", r.getCurrentStage());
        m.put("startedAt", r.getStartedAt());

        if (p != null) {
            long done = p.getStages().stream()
                    .filter(s -> {
                        ProjectRun.StageState st = r.getStages().get(s.getKey());
                        return st != null && "APPROVED".equals(st.getStatus());
                    }).count();
            m.put("stagesDone", done);
            m.put("stageCount", p.getStages().size());
            m.put("percent", p.getStages().isEmpty() ? 0
                    : Math.round(done * 100f / p.getStages().size()));
        }

        if (!full || p == null) return m;

        m.put("brief", p.getBrief());
        m.put("clientContext", p.getClientContext());
        m.put("successCriteria", p.getSuccessCriteria());
        m.put("difficulty", p.getDifficulty());
        m.put("expectedHours", p.getExpectedHours());
        m.put("resourceFileIds", p.getResourceFileIds());
        m.put("resourceLinks", p.getResourceLinks());

        m.put("stages", p.getStages().stream().map(s -> {
            ProjectRun.StageState st = r.getStages().get(s.getKey());
            Map<String, Object> sv = new LinkedHashMap<>();
            sv.put("key", s.getKey());
            sv.put("name", s.getName());
            sv.put("asks", s.getAsks());
            sv.put("guidance", s.getGuidance());
            sv.put("submittable", s.isSubmittable());
            sv.put("acceptedExtensions", s.getAcceptedExtensions());
            sv.put("status", st == null ? "LOCKED" : st.getStatus());
            sv.put("dueAt", st == null ? null : st.getDueAt());
            sv.put("submittedAt", st == null ? null : st.getSubmittedAt());
            sv.put("reviewedAt", st == null ? null : st.getReviewedAt());
            sv.put("score", st == null ? null : st.getScore());
            sv.put("feedback", st == null ? null : st.getFeedback());
            sv.put("rubricScores", st == null ? Map.of() : st.getRubricScores());
            sv.put("attempts", st == null ? List.of() : st.getAttempts());
            sv.put("rubric", s.getRubricId() == null ? null
                    : rubrics.findById(s.getRubricId()).orElse(null));
            sv.put("overdue", st != null && st.getDueAt() != null
                    && !"APPROVED".equals(st.getStatus())
                    && st.getDueAt().isBefore(Instant.now()));
            return sv;
        }).collect(Collectors.toList()));
        return m;
    }

    /** The mentor-facing shape is the learner one plus who it belongs to. */
    public Map<String, Object> runForMentor(String runId) {
        ProjectRun r = runs.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such project run."));
        Map<String, Object> m = new LinkedHashMap<>(view(r, true));
        m.put("learnerId", r.getLearnerId());
        m.put("learner", learnerName(r.getLearnerId()));
        return m;
    }

    /* -------------------------------------------------------------------- pieces */

    private Project project(String id) {
        return projects.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such project."));
    }

    private String nextStageKey(Project p, String afterKey) {
        List<Project.Stage> ss = p.getStages();
        for (int i = 0; i < ss.size(); i++) {
            if (ss.get(i).getKey().equals(afterKey)) {
                return i + 1 < ss.size() ? ss.get(i + 1).getKey() : null;
            }
        }
        return null;
    }

    /**
     * The lifecycle document's rule: a batch learner can request a mock once at least one
     * project is finished. Premium already gets one per module, so this only opens the
     * door that was previously opened by the old declared-project approval.
     */
    private void openMockIfEarned(Learner l) {
        if (l.getTrackType() != Learner.TrackType.BATCH) return;
        boolean already = mocks.findByLearnerId(l.getId()).stream()
                .anyMatch(k -> !"DONE".equals(k.getStatus()) && !"DECLINED".equals(k.getStatus()));
        if (already) return;
        MockRequest k = new MockRequest();
        k.setLearnerId(l.getId());
        k.setOrigin("REQUEST");
        k.setStatus("PENDING");
        mocks.save(k);
    }

    private String learnerName(String learnerId) {
        return learners.findById(learnerId)
                .flatMap(l -> users.findById(l.getUserId()))
                .map(User::getFullName)
                .orElse("Learner");
    }

    private void notify(Learner l, String subject, String body) {
        users.findById(l.getUserId()).ifPresent(u -> mail.send(u.getEmail(), subject, body, "PROJECT"));
    }

    private static String str(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castStages(Map<String, Object> row) {
        Object v = row.get("stages");
        return v instanceof List ? (List<Map<String, Object>>) v : List.of();
    }
}

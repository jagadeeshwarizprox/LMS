package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Every learner on one row, and the same row summed three ways.
 *
 * The numbers already existed and were scattered: progress in one screen, attendance in
 * another, tasks on a third, study minutes on a fourth. Nobody could answer "is batch 56
 * doing worse than 55" or "are this mentor's learners finishing" without opening five
 * screens and holding it in their head.
 *
 * One pass over the data builds a scorecard per learner. The batch and mentor views are
 * that same scorecard grouped, so the three can never disagree with each other, which is
 * the usual way a reporting screen loses people's trust.
 *
 * A deliberate omission: there is no single score. Rolling progress, attendance, marks and
 * effort into one number invents a weighting nobody agreed and hides which of the four is
 * actually the problem. The columns stay separate and the reader decides.
 */
@Service
public class AnalyticsService {

    private final LearnerRepository learners;
    private final UserRepository users;
    private final BatchRepository batches;
    private final BundleRepository bundles;
    private final ChapterRepository chapters;
    private final ProgressRepository progress;
    private final AssignmentRepository assignments;
    private final AttendanceRepository attendance;
    private final StudyDayRepository studyDays;
    private final MockRequestRepository mocks;
    private final ProjectRunRepository projectRuns;
    private final MentorService mentorService;
    private final MentorCoverService cover;

    public AnalyticsService(LearnerRepository learners, UserRepository users,
                            BatchRepository batches, BundleRepository bundles,
                            ChapterRepository chapters, ProgressRepository progress,
                            AssignmentRepository assignments, AttendanceRepository attendance,
                            StudyDayRepository studyDays, MockRequestRepository mocks,
                            ProjectRunRepository projectRuns, MentorService mentorService,
                            MentorCoverService cover) {
        this.learners = learners; this.users = users; this.batches = batches;
        this.bundles = bundles; this.chapters = chapters; this.progress = progress;
        this.assignments = assignments; this.attendance = attendance;
        this.studyDays = studyDays; this.mocks = mocks; this.projectRuns = projectRuns;
        this.mentorService = mentorService; this.cover = cover;
    }

    /**
     * The whole picture: every learner, and the batch and mentor rollups of the same rows.
     *
     * A mentor asking for this gets their own learners and nobody else's, because a
     * comparison table is exactly the shape that turns into gossip about another mentor's
     * numbers. An admin sees everyone.
     */
    public Map<String, Object> overview(String userId, String role) {
        boolean admin = "ADMIN".equals(role) || "SUPER_ADMIN".equals(role);

        /*
         * The same visibility rule every other mentor screen uses, rather than a second
         * filter on the stored mentorId. Two rules for "my learners" is how a mentor ends
         * up with thirty in their batch and twelve in their analytics.
         */
        List<Learner> scope = admin ? learners.findAll() : cover.learnersVisibleTo(userId);

        List<Map<String, Object>> rows = scorecards(scope);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("learners", rows);
        out.put("totals", totals(rows));
        out.put("byBatch", admin ? groupBy(rows, "batch", "batchId") : List.of());
        out.put("byMentor", admin ? groupBy(rows, "mentor", "mentorId") : List.of());
        out.put("byCourse", groupBy(rows, "course", "bundleId"));
        out.put("scope", admin ? "ORG" : "MINE");
        return out;
    }

    /* ---------------------------------------------------------------- the row */

    private List<Map<String, Object>> scorecards(List<Learner> scope) {
        if (scope.isEmpty()) return List.of();
        List<String> ids = scope.stream().map(Learner::getId).toList();

        /*
         * Everything is fetched once and indexed in memory rather than queried per
         * learner. Seventy learners times five collections is three hundred and fifty
         * round trips for a screen somebody refreshes; this is five.
         */
        Map<String, List<Progress>> progressBy = progress.findByLearnerIdIn(ids).stream()
                .collect(Collectors.groupingBy(Progress::getLearnerId));
        Map<String, List<Assignment>> tasksBy = assignments.findByLearnerIdIn(ids).stream()
                .collect(Collectors.groupingBy(Assignment::getLearnerId));
        /*
         * A chapter belongs to a module and a module to a bundle, so the denominator is
         * counted once per bundle rather than walked per learner.
         */
        Map<String, Long> chaptersPerModule = chapters.findAll().stream()
                .filter(c -> c.getModuleId() != null)
                .collect(Collectors.groupingBy(Chapter::getModuleId, Collectors.counting()));
        Map<String, Integer> chaptersInBundle = new HashMap<>();
        Map<String, String> bundleNames = new HashMap<>();
        bundles.findAll().forEach(b -> {
            bundleNames.put(b.getId(), b.getName());
            int n = b.getModuleIds() == null ? 0 : b.getModuleIds().stream()
                    .mapToInt(mid -> chaptersPerModule.getOrDefault(mid, 0L).intValue()).sum();
            chaptersInBundle.put(b.getId(), n);
        });
        Map<String, String> batchCodes = new HashMap<>();
        batches.findAll().forEach(b -> batchCodes.put(b.getId(), b.getCode()));

        LocalDate since = LocalDate.now().minusDays(28);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Learner l : scope) {
            User u = users.findById(l.getUserId()).orElse(null);
            if (u == null) continue;

            List<Progress> ps = progressBy.getOrDefault(l.getId(), List.of());
            List<Assignment> ts = tasksBy.getOrDefault(l.getId(), List.of());

            int total = l.getBundleId() == null ? 0
                    : chaptersInBundle.getOrDefault(l.getBundleId(), 0);

            long watched = ps.stream().filter(Progress::isWatched).count();
            List<Double> quizzes = ps.stream().map(Progress::getQuizScore)
                    .filter(Objects::nonNull).toList();
            List<Double> teach = ps.stream().map(Progress::getConceptCheckScore)
                    .filter(Objects::nonNull).toList();

            long approved = ts.stream().filter(a -> "APPROVED".equals(a.getStatus())).count();
            long overdue = ts.stream()
                    .filter(a -> a.getDueAt() != null && a.getDueAt().isBefore(Instant.now())
                            && !"APPROVED".equals(a.getStatus()))
                    .count();

            List<Attendance> att = attendance.findByLearnerId(l.getId());
            long invited = att.size();
            long present = att.stream()
                    .filter(a -> "PRESENT".equals(a.getState()) || "LATE".equals(a.getState()))
                    .count();

            int minutes = studyDays.findByLearnerIdOrderByDayDesc(l.getId()).stream()
                    .filter(d -> d.getDay() != null && !d.getDay().isBefore(since))
                    .mapToInt(StudyDay::getMinutes).sum();

            long idle = u.getLastLoginAt() == null ? -1
                    : ChronoUnit.DAYS.between(u.getLastLoginAt(), Instant.now());

            Map<String, Object> r = new LinkedHashMap<>();
            r.put("learnerId", l.getId());
            r.put("name", u.getFullName());
            r.put("trackType", String.valueOf(l.getTrackType()));
            r.put("batchId", l.getBatchId());
            r.put("batch", l.getBatchId() == null ? null : batchCodes.get(l.getBatchId()));
            r.put("mentorId", l.getMentorId());
            r.put("mentor", l.getMentorId() == null ? null
                    : users.findById(l.getMentorId()).map(User::getFullName).orElse(null));
            r.put("bundleId", l.getBundleId());
            r.put("course", l.getBundleId() == null ? null : bundleNames.get(l.getBundleId()));
            r.put("onHold", l.isOnHold());

            r.put("chaptersTotal", total);
            r.put("chaptersDone", watched);
            r.put("progressPct", total == 0 ? null : Math.round(watched * 100.0 / total));

            r.put("quizTaken", quizzes.size());
            r.put("quizAvg", avg(quizzes));
            r.put("teachAvg", avg(teach));

            r.put("tasksTotal", ts.size());
            r.put("tasksApproved", approved);
            r.put("tasksOverdue", overdue);

            r.put("sessionsInvited", invited);
            r.put("sessionsAttended", present);
            r.put("attendancePct", invited == 0 ? null : Math.round(present * 100.0 / invited));

            r.put("minutes28", minutes);
            r.put("idleDays", idle < 0 ? null : idle);
            r.put("mocksDone", mocks.findByLearnerId(l.getId()).stream()
                    .filter(m -> "DONE".equals(m.getStatus())).count());
            r.put("projectsApproved", projectRuns.findByLearnerId(l.getId()).stream()
                    .filter(p -> "APPROVED".equals(p.getStatus())).count());
            r.put("atRisk", mentorService.isAtRisk(l));
            rows.add(r);
        }
        return rows;
    }

    /* ------------------------------------------------------------- the rollups */

    /**
     * The same rows, grouped.
     *
     * Averages skip learners the number does not apply to rather than counting them as
     * zero: somebody who has not sat a test yet is not a zero in the test average, they
     * are simply not in it. Counting them would make every new cohort look like it was
     * failing.
     */
    private List<Map<String, Object>> groupBy(List<Map<String, Object>> rows,
                                              String labelKey, String idKey) {
        Map<String, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            Object id = r.get(idKey);
            groups.computeIfAbsent(id == null ? "" : String.valueOf(id), k -> new ArrayList<>()).add(r);
        }

        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : groups.entrySet()) {
            List<Map<String, Object>> g = e.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.getKey().isBlank() ? null : e.getKey());
            m.put("label", g.get(0).get(labelKey) == null ? "Unassigned" : g.get(0).get(labelKey));
            m.put("learners", g.size());
            m.put("progressPct", avgOf(g, "progressPct"));
            m.put("quizAvg", avgOf(g, "quizAvg"));
            m.put("attendancePct", avgOf(g, "attendancePct"));
            m.put("minutes28", avgOf(g, "minutes28"));
            m.put("tasksOverdue", sumOf(g, "tasksOverdue"));
            m.put("atRisk", g.stream().filter(r -> Boolean.TRUE.equals(r.get("atRisk"))).count());
            out.add(m);
        }
        out.sort(Comparator.comparing(x -> String.valueOf(x.get("label"))));
        return out;
    }

    private Map<String, Object> totals(List<Map<String, Object>> rows) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("learners", rows.size());
        m.put("onHold", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("onHold"))).count());
        m.put("atRisk", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("atRisk"))).count());
        m.put("progressPct", avgOf(rows, "progressPct"));
        m.put("quizAvg", avgOf(rows, "quizAvg"));
        m.put("attendancePct", avgOf(rows, "attendancePct"));
        m.put("minutes28", avgOf(rows, "minutes28"));
        m.put("tasksOverdue", sumOf(rows, "tasksOverdue"));
        return m;
    }

    private Double avg(List<Double> xs) {
        if (xs.isEmpty()) return null;
        return Math.round(xs.stream().mapToDouble(Double::doubleValue).average().orElse(0) * 10) / 10.0;
    }

    private Double avgOf(List<Map<String, Object>> rows, String key) {
        List<Double> xs = rows.stream()
                .map(r -> r.get(key))
                .filter(Objects::nonNull)
                .map(v -> ((Number) v).doubleValue())
                .toList();
        return avg(xs);
    }

    private long sumOf(List<Map<String, Object>> rows, String key) {
        return rows.stream().map(r -> r.get(key)).filter(Objects::nonNull)
                .mapToLong(v -> ((Number) v).longValue()).sum();
    }
}

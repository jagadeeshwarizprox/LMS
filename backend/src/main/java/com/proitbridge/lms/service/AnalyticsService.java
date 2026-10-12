package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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
    private final ProgressSnapshotService snapshots;
    private final MongoTemplate mongo;
    private final int noLoginDays;

    /** How many learner rows the organisation view carries. See orgOverview. */
    static final int ORG_ROWS = 200;

    public AnalyticsService(LearnerRepository learners, UserRepository users,
                            BatchRepository batches, BundleRepository bundles,
                            ChapterRepository chapters, ProgressRepository progress,
                            AssignmentRepository assignments, AttendanceRepository attendance,
                            StudyDayRepository studyDays, MockRequestRepository mocks,
                            ProjectRunRepository projectRuns, MentorService mentorService,
                            MentorCoverService cover, ProgressSnapshotService snapshots,
                            MongoTemplate mongo,
                            @Value("${lms.at-risk.no-login-days}") int noLoginDays) {
        this.mongo = mongo; this.noLoginDays = noLoginDays;
        this.learners = learners; this.users = users; this.batches = batches;
        this.bundles = bundles; this.chapters = chapters; this.progress = progress;
        this.assignments = assignments; this.attendance = attendance;
        this.studyDays = studyDays; this.mocks = mocks; this.projectRuns = projectRuns;
        this.mentorService = mentorService; this.cover = cover;
        this.snapshots = snapshots;
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
        if (admin) return orgOverview();

        /*
         * The same visibility rule every other mentor screen uses, rather than a second
         * filter on the stored mentorId. Two rules for "my learners" is how a mentor ends
         * up with thirty in their batch and twelve in their analytics.
         */
        List<Learner> scope = cover.learnersVisibleTo(userId);

        List<Map<String, Object>> rows = scorecards(scope, false);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("learners", rows);
        out.put("totals", totals(rows));
        out.put("byBatch", List.of());
        out.put("byMentor", List.of());
        out.put("byCourse", groupBy(rows, "course", "bundleId"));
        out.put("scope", "MINE");
        return out;
    }

    /* ------------------------------------------------------- the organisation view */

    /**
     * The admin view, computed in the database.
     *
     * This used to build a full scorecard for every learner on the register and then sum
     * them, which at twenty thousand learners is the whole learner collection plus its
     * progress, tasks, attendance and study days in memory on every refresh, and a few
     * hundred thousand round trips. Nothing about the rollups needs the rows.
     *
     * Totals and the batch, mentor and course rollups are now counts and $group
     * aggregations over the learner snapshot (progressPercent, avgQuiz, onHold), plus a
     * handful of reads on the other collections:
     *
     *  - progress is the mean of progressPercent over learners whose course has content,
     *    exactly as before (the rows already read it off the snapshot);
     *  - the test average is the mean of each learner's snapshot avgQuiz, the figure
     *    their own dashboard shows, where the rows used to average raw chapter scores;
     *  - minutes is total study minutes in the last 28 days over the learners in the
     *    group, which is the old per-learner average with the zeros included;
     *  - attendance is pooled for the organisation (sessions attended over sessions
     *    marked) and left blank per group, because a per-group figure would need every
     *    learner who ever attended a session mapped to a group on each refresh;
     *  - at risk is the same rule as MentorService.riskReasons, written as one learner
     *    query: not on hold, and idle past the threshold, or holding an overdue task, or
     *    still in onboarding a week after joining.
     *
     * The learner table is capped at ORG_ROWS: the at-risk learners, least progress
     * first. At organisation scale "every learner side by side" is twenty thousand rows
     * nobody reads; the ones who need the next call are the useful slice, and the
     * register pages through everybody. learnersTotal says how many there are in all.
     */
    private Map<String, Object> orgOverview() {
        Criteria risk = atRiskCriteria();

        /* the table: at-risk learners, furthest behind first */
        List<Learner> slice = mongo.find(new Query(risk)
                .with(Sort.by(Sort.Direction.ASC, "progressPercent"))
                .limit(ORG_ROWS), Learner.class);
        List<Map<String, Object>> rows = scorecards(slice, true);

        long total = learners.count();
        long atRiskTotal = mongo.count(new Query(risk), Learner.class);

        /* per-group figures that live outside the learner row, keyed by learner */
        Map<String, Long> overdueBy = overdueTasksByLearner();
        Map<String, Long> minutesBy = minutesByLearner(LocalDate.now().minusDays(28));

        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("learners", total);
        totals.put("onHold", mongo.count(new Query(Criteria.where("onHold").is(true)), Learner.class));
        totals.put("atRisk", atRiskTotal);
        Document all = one(group(null, new Criteria()));
        Document measured = one(group(null, Criteria.where("courseHasContent").is(true)));
        totals.put("progressPct", round(measured == null ? null : measured.get("progress")));
        totals.put("quizAvg", round(all == null ? null : all.get("quiz")));
        totals.put("attendancePct", pooledAttendance());
        long minutesAll = minutesBy.values().stream().mapToLong(Long::longValue).sum();
        totals.put("minutes28", total == 0 ? null : round(minutesAll / (double) total));
        totals.put("tasksOverdue", overdueBy.values().stream().mapToLong(Long::longValue).sum());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("learners", rows);
        out.put("learnersTotal", total);
        out.put("learnersShown", "AT_RISK");
        out.put("totals", totals);
        out.put("byBatch", rollup("batchId", batchLabels(), risk, overdueBy, minutesBy));
        out.put("byMentor", rollup("mentorId", mentorLabels(), risk, overdueBy, minutesBy));
        out.put("byCourse", rollup("bundleId", courseLabels(), risk, overdueBy, minutesBy));
        out.put("scope", "ORG");
        return out;
    }

    /**
     * MentorService.riskReasons as a learner query.
     *
     * Idle is read as "not signed in within the threshold": the ids of learners who did
     * sign in recently (the smaller set at this scale, one indexed-field read) are
     * excluded, which leaves the never-signed-in and the long-idle together, as the
     * per-row rule has them.
     */
    private Criteria atRiskCriteria() {
        Instant cutoff = Instant.now().minus(noLoginDays, ChronoUnit.DAYS);
        Query recent = new Query(new Criteria().andOperator(
                Criteria.where("role").is(User.Role.LEARNER),
                Criteria.where("lastLoginAt").gt(cutoff)));
        recent.fields().include("_id");
        List<String> activeUserIds = mongo.find(recent, User.class).stream().map(User::getId).toList();

        List<String> overdue = mongo.findDistinct(new Query(overdueTask()), "learnerId",
                Assignment.class, String.class);

        List<Criteria> reasons = new ArrayList<>();
        reasons.add(Criteria.where("userId").nin(activeUserIds));
        if (!overdue.isEmpty()) reasons.add(Criteria.where("_id").in(overdue));
        reasons.add(new Criteria().andOperator(AdminQueries.stillOnboarding(),
                Criteria.where("joinedOn").lt(LocalDate.now().minusDays(7))));
        return new Criteria().andOperator(
                Criteria.where("onHold").ne(true),
                new Criteria().orOperator(reasons));
    }

    private static Criteria overdueTask() {
        return Criteria.where("dueAt").lt(Instant.now()).and("status").ne("APPROVED");
    }

    /** Overdue task count per learner, only for learners that have any. */
    private Map<String, Long> overdueTasksByLearner() {
        Map<String, Long> out = new HashMap<>();
        for (Document d : mongo.aggregate(Aggregation.newAggregation(
                Aggregation.match(overdueTask()),
                Aggregation.group("learnerId").count().as("n")),
                Assignment.class, Document.class).getMappedResults()) {
            if (d.get("_id") != null) out.put(String.valueOf(d.get("_id")), num(d.get("n")));
        }
        return out;
    }

    /** Study minutes per learner since the given day, only for learners with any. */
    private Map<String, Long> minutesByLearner(LocalDate since) {
        Map<String, Long> out = new HashMap<>();
        for (Document d : mongo.aggregate(Aggregation.newAggregation(
                Aggregation.match(Criteria.where("day").gte(since)),
                Aggregation.group("learnerId").sum("minutes").as("m")),
                StudyDay.class, Document.class).getMappedResults()) {
            if (d.get("_id") != null) out.put(String.valueOf(d.get("_id")), num(d.get("m")));
        }
        return out;
    }

    /** Sessions attended over sessions marked, across everyone. */
    private Long pooledAttendance() {
        long marked = mongo.count(new Query(), Attendance.class);
        if (marked == 0) return null;
        long present = mongo.count(new Query(Criteria.where("state").in("PRESENT", "LATE")), Attendance.class);
        return Math.round(present * 100.0 / marked);
    }

    /** learners, mean progress and mean test score, grouped by one learner field. */
    private org.springframework.data.mongodb.core.aggregation.AggregationResults<Document> group(
            String field, Criteria match) {
        var g = field == null ? Aggregation.group() : Aggregation.group(field);
        return mongo.aggregate(Aggregation.newAggregation(
                Aggregation.match(match),
                g.count().as("learners").avg("progressPercent").as("progress").avg("avgQuiz").as("quiz")),
                Learner.class, Document.class);
    }

    private static Document one(org.springframework.data.mongodb.core.aggregation.AggregationResults<Document> r) {
        List<Document> xs = r.getMappedResults();
        return xs.isEmpty() ? null : xs.get(0);
    }

    /**
     * One rollup table, the same columns the row-based groupBy produced.
     *
     * Overdue tasks and study minutes are summed into the group through a projection of
     * just the learners that have any (three small fields each, read 500 at a time).
     */
    private List<Map<String, Object>> rollup(String field, Map<String, String> labels, Criteria risk,
                                             Map<String, Long> overdueBy, Map<String, Long> minutesBy) {
        Map<String, Document> all = byId(group(field, new Criteria()));
        Map<String, Document> measured = byId(group(field, Criteria.where("courseHasContent").is(true)));
        Map<String, Long> atRisk = new HashMap<>();
        for (Document d : mongo.aggregate(Aggregation.newAggregation(
                Aggregation.match(risk), Aggregation.group(field).count().as("n")),
                Learner.class, Document.class).getMappedResults()) {
            atRisk.put(key(d.get("_id")), num(d.get("n")));
        }
        Map<String, Long> overdue = sumIntoGroups(field, overdueBy);
        Map<String, Long> minutes = sumIntoGroups(field, minutesBy);

        List<Map<String, Object>> out = new ArrayList<>();
        for (var e : all.entrySet()) {
            String id = e.getKey();
            long n = num(e.getValue().get("learners"));
            Document m = measured.get(id);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", id.isBlank() ? null : id);
            row.put("label", id.isBlank() ? "Unassigned" : labels.getOrDefault(id, "Unassigned"));
            row.put("learners", n);
            row.put("progressPct", round(m == null ? null : m.get("progress")));
            row.put("quizAvg", round(e.getValue().get("quiz")));
            row.put("attendancePct", null);
            row.put("minutes28", n == 0 ? null : round(minutes.getOrDefault(id, 0L) / (double) n));
            row.put("tasksOverdue", overdue.getOrDefault(id, 0L));
            row.put("atRisk", atRisk.getOrDefault(id, 0L));
            out.add(row);
        }
        out.sort(Comparator.comparing(x -> String.valueOf(x.get("label"))));
        return out;
    }

    private Map<String, Long> sumIntoGroups(String field, Map<String, Long> perLearner) {
        Map<String, Long> out = new HashMap<>();
        AdminQueries.inChunks(perLearner.keySet(), ids -> {
            Query q = new Query(Criteria.where("_id").in(ids));
            q.fields().include("_id", field);
            for (Learner l : mongo.find(q, Learner.class)) {
                String g = switch (field) {
                    case "batchId" -> l.getBatchId();
                    case "mentorId" -> l.getMentorId();
                    default -> l.getBundleId();
                };
                out.merge(key(g), perLearner.getOrDefault(l.getId(), 0L), Long::sum);
            }
        });
        return out;
    }

    private static Map<String, Document> byId(
            org.springframework.data.mongodb.core.aggregation.AggregationResults<Document> r) {
        Map<String, Document> out = new LinkedHashMap<>();
        for (Document d : r.getMappedResults()) out.put(key(d.get("_id")), d);
        return out;
    }

    private Map<String, String> batchLabels() {
        Map<String, String> m = new HashMap<>();
        batches.findAll().forEach(b -> m.put(b.getId(), b.getCode()));
        return m;
    }

    private Map<String, String> mentorLabels() {
        Map<String, String> m = new HashMap<>();
        users.findByRoleIn(List.of(User.Role.MENTOR, User.Role.ADMIN, User.Role.SUPER_ADMIN))
                .forEach(u -> m.put(u.getId(), u.getFullName()));
        return m;
    }

    private Map<String, String> courseLabels() {
        Map<String, String> m = new HashMap<>();
        bundles.findAll().forEach(b -> m.put(b.getId(), b.getName()));
        return m;
    }

    private static String key(Object id) { return id == null ? "" : String.valueOf(id); }

    private static long num(Object o) { return o instanceof Number n ? n.longValue() : 0L; }

    private static Double round(Object o) {
        if (!(o instanceof Number n)) return null;
        return Math.round(n.doubleValue() * 10) / 10.0;
    }

    /* ---------------------------------------------------------------- the row */

    /**
     * One scorecard per learner in the scope.
     *
     * `selectedAtRisk` is true when the scope was chosen by the at-risk query itself (the
     * organisation slice), so every row is at risk by construction and the per-row check,
     * two reads per learner, is not repeated.
     */
    private List<Map<String, Object>> scorecards(List<Learner> scope, boolean selectedAtRisk) {
        if (scope.isEmpty()) return List.of();
        List<String> ids = scope.stream().map(Learner::getId).toList();

        /*
         * Everything is fetched once and indexed in memory rather than queried per
         * learner. Seventy learners times five collections is three hundred and fifty
         * round trips for a screen somebody refreshes; this is a fixed handful whatever
         * the size of the scope.
         */
        Map<String, List<Progress>> progressBy = progress.findByLearnerIdIn(ids).stream()
                .collect(Collectors.groupingBy(Progress::getLearnerId));
        Map<String, List<Assignment>> tasksBy = assignments.findByLearnerIdIn(ids).stream()
                .collect(Collectors.groupingBy(Assignment::getLearnerId));
        Map<String, List<Attendance>> attendanceBy = mongo.find(
                new Query(Criteria.where("learnerId").in(ids)), Attendance.class).stream()
                .collect(Collectors.groupingBy(Attendance::getLearnerId));
        Map<String, List<StudyDay>> daysBy = mongo.find(
                new Query(Criteria.where("learnerId").in(ids)
                        .and("day").gte(LocalDate.now().minusDays(28))), StudyDay.class).stream()
                .collect(Collectors.groupingBy(StudyDay::getLearnerId));
        Map<String, Long> mocksDone = mocks.findByLearnerIdIn(ids).stream()
                .filter(m -> "DONE".equals(m.getStatus()))
                .collect(Collectors.groupingBy(MockRequest::getLearnerId, Collectors.counting()));
        Map<String, Long> runsApproved = projectRuns.findByLearnerIdIn(ids).stream()
                .filter(p -> "APPROVED".equals(p.getStatus()))
                .collect(Collectors.groupingBy(ProjectRun::getLearnerId, Collectors.counting()));
        Set<String> userIds = new HashSet<>();
        scope.forEach(l -> {
            if (l.getUserId() != null) userIds.add(l.getUserId());
            if (l.getMentorId() != null) userIds.add(l.getMentorId());
        });
        Map<String, User> who = new HashMap<>();
        users.findAllById(userIds).forEach(u -> who.put(u.getId(), u));
        /* bounded by the catalogue and the batch list, not by learners */
        Map<String, String> bundleNames = new HashMap<>();
        bundles.findAllById(scope.stream().map(Learner::getBundleId).filter(Objects::nonNull)
                .collect(Collectors.toSet())).forEach(b -> bundleNames.put(b.getId(), b.getName()));
        Map<String, String> batchCodes = new HashMap<>();
        batches.findAllById(scope.stream().map(Learner::getBatchId).filter(Objects::nonNull)
                .collect(Collectors.toSet())).forEach(b -> batchCodes.put(b.getId(), b.getCode()));

        LocalDate since = LocalDate.now().minusDays(28);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Learner l : scope) {
            User u = who.get(l.getUserId());
            if (u == null) continue;

            List<Progress> ps = progressBy.getOrDefault(l.getId(), List.of());
            List<Assignment> ts = tasksBy.getOrDefault(l.getId(), List.of());

            /*
             * Progress comes off the learner's snapshot, the same figure their dashboard,
             * the roster and the rank show. This screen used to work out its own: chapters
             * ticked over chapters in the course, which put a learner part way through
             * every chapter at nought here while the rest of the product said otherwise.
             * Null still means there is no published course to measure against.
             */
            snapshots.ensure(l);
            List<Double> quizzes = ps.stream().map(Progress::getQuizScore)
                    .filter(Objects::nonNull).toList();
            List<Double> teach = ps.stream().map(Progress::getConceptCheckScore)
                    .filter(Objects::nonNull).toList();

            long approved = ts.stream().filter(a -> "APPROVED".equals(a.getStatus())).count();
            long overdue = ts.stream()
                    .filter(a -> a.getDueAt() != null && a.getDueAt().isBefore(Instant.now())
                            && !"APPROVED".equals(a.getStatus()))
                    .count();

            List<Attendance> att = attendanceBy.getOrDefault(l.getId(), List.of());
            long invited = att.size();
            long present = att.stream()
                    .filter(a -> "PRESENT".equals(a.getState()) || "LATE".equals(a.getState()))
                    .count();

            int minutes = daysBy.getOrDefault(l.getId(), List.of()).stream()
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
            User mentor = l.getMentorId() == null ? null : who.get(l.getMentorId());
            r.put("mentor", mentor == null ? null : mentor.getFullName());
            r.put("bundleId", l.getBundleId());
            r.put("course", l.getBundleId() == null ? null : bundleNames.get(l.getBundleId()));
            r.put("onHold", l.isOnHold());

            r.put("chaptersTotal", l.getTotalChapters());
            r.put("chaptersDone", l.getWatchedChapters());
            r.put("progressPct", l.isCourseHasContent() ? l.getProgressPercent() : null);

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
            r.put("mocksDone", mocksDone.getOrDefault(l.getId(), 0L));
            r.put("projectsApproved", runsApproved.getOrDefault(l.getId(), 0L));
            r.put("atRisk", selectedAtRisk || mentorService.isAtRisk(l));
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

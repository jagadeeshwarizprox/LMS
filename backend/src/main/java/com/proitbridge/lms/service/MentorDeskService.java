package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.GroupOperation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Everything a mentor needs that the queues did not give them.
 *
 * Two ideas run through this. First, a mentor thinks in cohorts, not in a flat list,
 * so learners arrive grouped by batch with premium as its own group. Second, a list
 * of names is useless without the two facts that decide who to contact today: when
 * they last did anything, and what is waiting on whom.
 */
@Service
public class MentorDeskService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final UserRepository users;
    private final LearnerRepository learners;
    private final BatchRepository batches;
    private final BundleRepository bundles;
    private final AssignmentRepository assignments;
    private final ProjectWorkRepository projects;
    private final MockRequestRepository mocks;
    private final ProgressCallRepository calls;
    private final SlotRepository slots;
    private final BookingRepository bookings;
    private final ResumeVersionRepository resumes;
    private final AiInteractionRepository aiLog;
    private final ChapterRepository chapters;
    private final ModuleRepository modules;
    private final StudyDayRepository studyDays;
    private final LearnerService learnerService;
    private final LearnerProgressService progressView;
    private final MentorService mentorService;
    private final MeetingService meetings;
    private final MentorCoverService cover;
    private final MongoTemplate mongo;

    public MentorDeskService(UserRepository users, LearnerRepository learners, BatchRepository batches,
                             BundleRepository bundles, AssignmentRepository assignments,
                             ProjectWorkRepository projects, MockRequestRepository mocks,
                             ProgressCallRepository calls, SlotRepository slots,
                             BookingRepository bookings, ResumeVersionRepository resumes,
                             AiInteractionRepository aiLog, ChapterRepository chapters,
                             ModuleRepository modules, StudyDayRepository studyDays,
                             LearnerService learnerService, MentorService mentorService,
                             MeetingService meetings, MentorCoverService cover,
                             LearnerProgressService progressView, MongoTemplate mongo) {
        this.mongo = mongo;
        this.users = users; this.learners = learners; this.batches = batches; this.bundles = bundles;
        this.assignments = assignments; this.projects = projects; this.mocks = mocks;
        this.calls = calls; this.slots = slots; this.bookings = bookings; this.resumes = resumes;
        this.aiLog = aiLog; this.chapters = chapters; this.modules = modules;
        this.studyDays = studyDays; this.learnerService = learnerService;
        this.mentorService = mentorService; this.meetings = meetings; this.cover = cover;
        this.progressView = progressView;
    }

    /* ================================================================ my learners */

    /**
     * Grouped by cohort, because that is how a mentor holds them in their head.
     * Every row carries the facts that decide who to contact: last activity, what is
     * waiting, and whether they are drifting.
     */
    public Map<String, Object> roster(String mentorId) {
        /* own learners, plus anyone being covered for. The learner's mentor is
           unchanged, so the grouping and the record still say whose they are. */
        List<Learner> mine = cover.learnersVisibleTo(mentorId);
        DeskContext ctx = new DeskContext(mine);
        List<Map<String, Object>> rows = mine.stream().map(l -> row(l, ctx)).toList();

        Map<String, List<Map<String, Object>>> grouped = new LinkedHashMap<>();
        rows.stream().filter(r -> "PREMIUM".equals(String.valueOf(r.get("trackType"))))
                .forEach(r -> grouped.computeIfAbsent("Premium", k -> new ArrayList<>()).add(r));
        rows.stream().filter(r -> !"PREMIUM".equals(String.valueOf(r.get("trackType"))))
                .sorted(Comparator.comparing(r -> String.valueOf(r.get("batch"))))
                .forEach(r -> grouped.computeIfAbsent(
                        r.get("batch") == null ? "No batch yet" : "Batch " + r.get("batch"),
                        k -> new ArrayList<>()).add(r));

        List<Map<String, Object>> groups = grouped.entrySet().stream().map(e -> {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("label", e.getKey());
            g.put("count", e.getValue().size());
            g.put("atRisk", e.getValue().stream()
                    .filter(r -> Boolean.TRUE.equals(r.get("atRisk"))).count());
            g.put("waitingOnMe", e.getValue().stream()
                    .mapToInt(r -> num(r, "waitingOnMe")).sum());
            g.put("averagePercent", (int) e.getValue().stream()
                    .mapToInt(r -> num(r, "percent")).average().orElse(0));
            g.put("learners", e.getValue());
            return g;
        }).collect(Collectors.toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("groups", groups);
        out.put("total", rows.size());
        out.put("cover", cover.stateFor(mentorId));
        out.put("counts", Map.of(
                "atRisk", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("atRisk"))).count(),
                "onHold", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("onHold"))).count(),
                "waitingOnMe", rows.stream().mapToInt(r -> num(r, "waitingOnMe")).sum(),
                "quietThisWeek", rows.stream()
                        .filter(r -> num(r, "studyMinutesWeek") == 0).count()));
        out.put("distribution", distribution(rows));
        return out;
    }

    /**
     * A number out of a row, whatever numeric type it arrived as.
     *
     * These were `(Integer)` casts and one of the values was a Long, which is a
     * ClassCastException rather than a wrong number: the whole roster 500s and a mentor
     * sees none of their learners. Reading through Number means a change of type
     * somewhere upstream can no longer take a screen down.
     */
    private int num(Map<String, Object> row, String key) {
        Object v = row.get(key);
        return v instanceof Number n ? n.intValue() : 0;
    }

    /**
     * Everything the desk rows need, for every learner on the desk, read once.
     *
     * Each row used to run its own eight or so reads (user, assignments, projects, mocks,
     * calls, study days, batch, bundle, mentor, risk, watch stats), so a desk of eighty was
     * several hundred round trips on every open. These are findAllById reads and grouped
     * counts over learnerId indexes, a fixed dozen whatever the roster size.
     */
    private final class DeskContext {
        final Map<String, User> people = new HashMap<>();
        final Map<String, String> batchCodes = new HashMap<>();
        final Map<String, String> bundleNames = new HashMap<>();
        final Map<String, Long> waiting = new HashMap<>();
        final Map<String, Instant> lastSubmission = new HashMap<>();
        final Map<String, Instant> nextCall = new HashMap<>();
        final Map<String, Long> weekMinutes = new HashMap<>();
        final Map<String, Long> overdue;
        final Map<String, Map<String, Object>> stats;

        DeskContext(List<Learner> ls) {
            List<String> ids = ls.stream().map(Learner::getId).filter(Objects::nonNull).toList();
            Set<String> userIds = new HashSet<>();
            for (Learner l : ls) {
                if (l.getUserId() != null) userIds.add(l.getUserId());
                if (l.getMentorId() != null) userIds.add(l.getMentorId());
            }
            if (!userIds.isEmpty()) people.putAll(usersById(userIds));
            Set<String> batchIds = ls.stream().map(Learner::getBatchId).filter(Objects::nonNull).collect(Collectors.toSet());
            if (!batchIds.isEmpty()) batches.findAllById(batchIds).forEach(b -> batchCodes.put(b.getId(), b.getCode()));
            Set<String> bundleIds = ls.stream().map(Learner::getBundleId).filter(Objects::nonNull).collect(Collectors.toSet());
            if (!bundleIds.isEmpty()) bundles.findAllById(bundleIds).forEach(b -> bundleNames.put(b.getId(), b.getName()));

            if (!ids.isEmpty()) {
                /* waiting on me: submitted tasks, submitted projects, pending mocks */
                for (org.bson.Document d : aggregate(Assignment.class,
                        Criteria.where("learnerId").in(ids).and("status").is("SUBMITTED"),
                        Aggregation.group("learnerId").count().as("n"))) add(waiting, d);
                for (org.bson.Document d : aggregate(ProjectWork.class,
                        Criteria.where("learnerId").in(ids).and("status").is("SUBMITTED"),
                        Aggregation.group("learnerId").count().as("n"))) add(waiting, d);
                for (org.bson.Document d : aggregate(MockRequest.class,
                        Criteria.where("learnerId").in(ids).and("status").is("PENDING"),
                        Aggregation.group("learnerId").count().as("n"))) add(waiting, d);

                /* the latest submission across tasks and projects */
                for (Class<?> type : List.of(Assignment.class, ProjectWork.class)) {
                    for (org.bson.Document d : aggregate(type, Criteria.where("learnerId").in(ids),
                            Aggregation.group("learnerId").max("submittedAt").as("t"))) {
                        Instant t = instant(d.get("t"));
                        if (t != null) lastSubmission.merge(String.valueOf(d.get("_id")), t,
                                (a, b) -> a.isAfter(b) ? a : b);
                    }
                }

                /* the soonest call still to be held, ignoring ones missed by more than two days */
                for (org.bson.Document d : aggregate(ProgressCall.class,
                        Criteria.where("learnerId").in(ids).and("heldAt").is(null)
                                .and("scheduledFor").gt(Instant.now().minus(2, ChronoUnit.DAYS)),
                        Aggregation.group("learnerId").min("scheduledFor").as("t"))) {
                    Instant t = instant(d.get("t"));
                    if (t != null) nextCall.put(String.valueOf(d.get("_id")), t);
                }

                /* study minutes since Monday (IST) */
                for (org.bson.Document d : aggregate(StudyDay.class,
                        Criteria.where("learnerId").in(ids)
                                .and("day").gte(LocalDate.now(IST).with(DayOfWeek.MONDAY)),
                        Aggregation.group("learnerId").sum("minutes").as("n"))) add(weekMinutes, d);
            }
            overdue = mentorService.overdueCounts(ids);
            stats = progressView.rowStatsFor(ls);
        }

        private void add(Map<String, Long> into, org.bson.Document d) {
            Object n = d.get("n");
            into.merge(String.valueOf(d.get("_id")), n instanceof Number x ? x.longValue() : 0L, Long::sum);
        }
    }

    private Map<String, User> usersById(Collection<String> ids) {
        Map<String, User> out = new HashMap<>();
        users.findAllById(ids).forEach(u -> out.put(u.getId(), u));
        return out;
    }

    private List<org.bson.Document> aggregate(Class<?> type, Criteria match, GroupOperation group) {
        return mongo.aggregate(Aggregation.newAggregation(Aggregation.match(match), group),
                type, org.bson.Document.class).getMappedResults();
    }

    private static Instant instant(Object o) {
        if (o instanceof Date d) return d.toInstant();
        if (o instanceof Instant i) return i;
        return null;
    }

    private Map<String, Object> row(Learner l, DeskContext ctx) {
        User u = ctx.people.get(l.getUserId());
        Map<String, Object> stats = learnerService.stats(l);

        long waiting = ctx.waiting.getOrDefault(l.getId(), 0L);
        Instant lastSubmission = ctx.lastSubmission.get(l.getId());
        Instant nextCall = ctx.nextCall.get(l.getId());
        int weekMinutes = ctx.weekMinutes.getOrDefault(l.getId(), 0L).intValue();

        long idleDays = u == null || u.getLastLoginAt() == null ? 999
                : ChronoUnit.DAYS.between(u.getLastLoginAt(), Instant.now());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("learnerId", l.getId());
        m.put("name", u == null ? "Learner" : u.getFullName());
        m.put("email", u == null ? null : u.getEmail());
        m.put("phone", u == null ? null : u.getPhone());
        m.put("trackType", l.getTrackType());
        m.put("batch", l.getBatchId() == null ? null : ctx.batchCodes.get(l.getBatchId()));
        /* the code is for reading; anything that acts on the batch needs its id */
        m.put("batchId", l.getBatchId());
        m.put("bundle", l.getBundleId() == null ? null : ctx.bundleNames.get(l.getBundleId()));
        m.put("percent", stats.get("percent"));
        m.put("avgQuiz", stats.get("avgQuiz"));
        m.put("onboarded", l.gatesCleared());
        m.put("onHold", l.isOnHold());
        m.put("lastLoginAt", u == null ? null : u.getLastLoginAt());
        m.put("idleDays", idleDays == 999 ? null : idleDays);
        m.put("lastSubmission", lastSubmission);
        m.put("nextCallDue", nextCall);
        m.put("studyMinutesWeek", weekMinutes);
        m.put("waitingOnMe", (int) waiting);
        /*
         * One at-risk rule for the whole product, owned by MentorService. This used to be
         * a second, hardcoded copy: seven days regardless of the configured threshold,
         * plus "no study minutes this week", which flags the entire cohort every Monday.
         * The week's minutes are still on the row as their own number, where they belong.
         */
        List<String> risk = mentorService.riskReasons(l, u, ctx.overdue.getOrDefault(l.getId(), 0L));
        m.put("atRisk", !risk.isEmpty());
        m.put("riskReasons", risk);
        m.put("mentor", l.getMentorId() == null || ctx.people.get(l.getMentorId()) == null ? null
                : ctx.people.get(l.getMentorId()).getFullName());
        /*
         * The roster is built here, not by MentorService.learnerRow, so adding the watch
         * numbers there left this list showing a dash in the Videos column. Two row
         * builders for the same thing is the underlying problem; until they are merged,
         * both carry the same stats.
         */
        m.putAll(ctx.stats.getOrDefault(l.getId(), progressView.rowStats(l)));
        return m;
    }

    /** Progress spread across the mentor's learners, for the bar on the desk. */
    private List<Map<String, Object>> distribution(List<Map<String, Object>> rows) {
        String[] bands = {"0 to 20", "20 to 40", "40 to 60", "60 to 80", "80 to 100"};
        int[] counts = new int[5];
        for (Map<String, Object> r : rows) {
            int p = num(r, "percent");
            counts[Math.min(4, p / 20)]++;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < 5; i++) out.add(Map.of("band", bands[i], "count", counts[i]));
        return out;
    }

    /* ================================================================ my day */

    /**
     * What today asks of this mentor. Sessions, calls, and the oldest thing waiting,
     * because a queue that is four days old is a different problem from a busy one.
     */
    public Map<String, Object> day(String mentorId) {
        Instant now = Instant.now();
        LocalDate today = LocalDate.now(IST);

        /* today's sessions by the (mentorId, startsAt) index, not every slot the mentor ever had */
        Instant dayStart = today.atStartOfDay(IST).toInstant();
        Instant dayEnd = today.plusDays(1).atStartOfDay(IST).toInstant();
        List<Map<String, Object>> sessions = mongo.find(Query.query(Criteria.where("mentorId").is(mentorId)
                        .and("startsAt").gte(dayStart).lt(dayEnd)), Slot.class).stream()
                .sorted(Comparator.comparing(Slot::getStartsAt))
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.getId());
                    m.put("kind", s.getKind());
                    m.put("startsAt", s.getStartsAt());
                    m.put("booked", bookings.findBySlotId(s.getId()).size());
                    m.put("capacity", s.getCapacity());
                    m.putAll(meetings.windowFor(s));
                    return m;
                }).collect(Collectors.toList());

        List<Learner> visible = cover.learnersVisibleTo(mentorId);
        List<Learner> callable = visible.stream()
                .filter(l -> l.getTrackType() == Learner.TrackType.PREMIUM && !l.isOnHold())
                .toList();
        /* open calls for every callable learner in one read, and their names in one more */
        Map<String, Instant> soonest = new HashMap<>();
        if (!callable.isEmpty()) {
            for (ProgressCall c : calls.findByLearnerIdInAndHeldAtIsNull(
                    callable.stream().map(Learner::getId).toList())) {
                if (c.getScheduledFor() == null) continue;
                soonest.merge(c.getLearnerId(), c.getScheduledFor(), (a, b) -> a.isBefore(b) ? a : b);
            }
        }
        Map<String, User> callNames = usersById(callable.stream()
                .filter(l -> soonest.containsKey(l.getId()))
                .map(Learner::getUserId).filter(Objects::nonNull).collect(Collectors.toSet()));
        List<Map<String, Object>> callsDue = callable.stream()
                .map(l -> {
                    Instant when = soonest.get(l.getId());
                    if (when == null) return null;
                    if (when.isAfter(now.plus(7, ChronoUnit.DAYS))) return null;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("learnerId", l.getId());
                    User cu = callNames.get(l.getUserId());
                    m.put("name", cu == null ? "" : cu.getFullName());
                    m.put("scheduledFor", when);
                    m.put("overdue", when.isBefore(now));
                    return m;
                }).filter(Objects::nonNull)
                .sorted(Comparator.comparing(m -> (Instant) m.get("scheduledFor")))
                .collect(Collectors.toList());

        List<String> ids = visible.stream().map(Learner::getId).toList();
        List<Map<String, Object>> ageing = ids.isEmpty() ? List.of()
                : assignments.findByLearnerIdInAndStatus(ids, "SUBMITTED").stream()
                    .sorted(Comparator.comparing(Assignment::getSubmittedAt))
                    .limit(5)
                    .map(a -> {
                        long days = ChronoUnit.DAYS.between(a.getSubmittedAt(), now);
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("id", a.getId());
                        m.put("title", a.getTitle());
                        m.put("learner", nameOf(a.getLearnerId()));
                        m.put("learnerId", a.getLearnerId());
                        m.put("waitingDays", days);
                        m.put("stale", days >= 2);
                        return m;
                    }).collect(Collectors.toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionsToday", sessions);
        out.put("callsDue", callsDue);
        out.put("oldestWaiting", ageing);
        /* counts in the database from Monday 00:00 IST, not every row then filtered */
        Instant monday = today.with(DayOfWeek.MONDAY).atStartOfDay(IST).toInstant();
        out.put("reviewedThisWeek", ids.isEmpty() ? 0L : mongo.count(Query.query(
                Criteria.where("learnerId").in(ids).and("reviewedAt").gte(monday)), Assignment.class));
        out.put("cover", cover.stateFor(mentorId));
        out.put("callsHeldThisWeek", mongo.count(Query.query(
                Criteria.where("mentorId").is(mentorId).and("heldAt").gte(monday)), ProgressCall.class));
        return out;
    }

    /* ================================================================ doubt digest */

    /**
     * Every doubt a learner asked the assistant, grouped by chapter. A mentor walks
     * into a session already knowing where the cohort is stuck, which is worth more
     * than any dashboard number.
     */
    public List<Map<String, Object>> doubtDigest(String mentorId) {
        Set<String> mine = cover.learnersVisibleTo(mentorId).stream()
                .map(Learner::getId).collect(Collectors.toSet());
        Instant since = Instant.now().minus(14, ChronoUnit.DAYS);

        Map<String, List<AiInteraction>> byChapter = aiLog.findTop100ByOrderByCreatedAtDesc().stream()
                .filter(i -> "DOUBT".equals(i.getKind()))
                .filter(i -> i.getCreatedAt().isAfter(since))
                .filter(i -> mine.contains(i.getLearnerId()))
                .filter(i -> i.getChapterId() != null)
                .collect(Collectors.groupingBy(AiInteraction::getChapterId));

        return byChapter.entrySet().stream().map(e -> {
            Chapter c = chapters.findById(e.getKey()).orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("chapterId", e.getKey());
            m.put("chapter", c == null ? "Unknown chapter" : c.getTitle());
            m.put("module", c == null ? "" : modules.findById(c.getModuleId())
                    .map(CourseModule::getName).orElse(""));
            m.put("count", e.getValue().size());
            m.put("learners", e.getValue().stream().map(AiInteraction::getLearnerId)
                    .distinct().count());
            m.put("questions", e.getValue().stream().limit(5)
                    .map(i -> Map.of(
                            "learner", nameOfLearner(i.getLearnerId()),
                            "question", shorten(i.getPrompt()),
                            "answered", i.getResponse() != null))
                    .toList());
            return m;
        }).sorted((a, b) -> Integer.compare((Integer) b.get("count"), (Integer) a.get("count")))
          .collect(Collectors.toList());
    }

    /** The prompt carries the chapter context too, so trim to the question itself. */
    private String shorten(String prompt) {
        if (prompt == null) return "";
        int at = prompt.lastIndexOf("Question:");
        String q = at >= 0 ? prompt.substring(at + 9).trim() : prompt;
        return q.length() > 160 ? q.substring(0, 157) + "\u2026" : q;
    }

    /* ================================================================ small actions */

    /** Only somebody who can see the learner may sign off their resume. */
    public ResumeVersion markResumeReviewed(String resumeId, String mentorId, String role) {
        ResumeVersion r = resumes.findById(resumeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such resume."));
        Learner l = r.getLearnerId() == null ? null : learners.findById(r.getLearnerId()).orElse(null);
        if (l == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Learner not found.");
        }
        mentorService.assertMine(mentorId, l, role);
        r.setReviewedByMentor(true);
        return resumes.save(r);
    }

    public Map<String, Object> studyTimeFor(String learnerId) {
        List<StudyDay> history = studyDays.findByLearnerIdOrderByDayDesc(learnerId);
        LocalDate today = LocalDate.now(IST);
        Map<LocalDate, Integer> byDay = history.stream()
                .collect(Collectors.toMap(StudyDay::getDay, StudyDay::getMinutes, (a, b) -> a));

        int week = 0, prev = 0;
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        for (LocalDate d = weekStart; !d.isAfter(today); d = d.plusDays(1))
            week += byDay.getOrDefault(d, 0);
        for (LocalDate d = weekStart.minusWeeks(1); d.isBefore(weekStart); d = d.plusDays(1))
            prev += byDay.getOrDefault(d, 0);

        List<Map<String, Object>> last14 = new ArrayList<>();
        for (int i = 13; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            last14.add(Map.of("day", d.toString(), "minutes", byDay.getOrDefault(d, 0)));
        }
        return Map.of("weekMinutes", week, "previousWeekMinutes", prev,
                "trend", week >= prev ? "UP" : "DOWN",
                "activeDaysLast14", last14.stream().filter(d -> (Integer) d.get("minutes") >= 10).count(),
                "last14", last14);
    }

    private String nameOf(String learnerId) { return nameOfLearner(learnerId); }

    private String nameOfLearner(String learnerId) {
        return learners.findById(learnerId == null ? "-" : learnerId)
                .flatMap(l -> users.findById(l.getUserId()))
                .map(User::getFullName).orElse("Learner");
    }

}

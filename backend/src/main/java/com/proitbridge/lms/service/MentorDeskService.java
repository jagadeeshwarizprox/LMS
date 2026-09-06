package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    public MentorDeskService(UserRepository users, LearnerRepository learners, BatchRepository batches,
                             BundleRepository bundles, AssignmentRepository assignments,
                             ProjectWorkRepository projects, MockRequestRepository mocks,
                             ProgressCallRepository calls, SlotRepository slots,
                             BookingRepository bookings, ResumeVersionRepository resumes,
                             AiInteractionRepository aiLog, ChapterRepository chapters,
                             ModuleRepository modules, StudyDayRepository studyDays,
                             LearnerService learnerService, MentorService mentorService,
                             MeetingService meetings, MentorCoverService cover,
                             LearnerProgressService progressView) {
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
        List<Map<String, Object>> rows = mine.stream().map(this::row).toList();

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

    private Map<String, Object> row(Learner l) {
        User u = users.findById(l.getUserId()).orElse(null);
        Map<String, Object> stats = learnerService.stats(l);

        List<Assignment> tasks = assignments.findByLearnerId(l.getId());
        List<ProjectWork> pw = projects.findByLearnerId(l.getId());
        long waiting = tasks.stream().filter(a -> "SUBMITTED".equals(a.getStatus())).count()
                + pw.stream().filter(p -> "SUBMITTED".equals(p.getStatus())).count()
                + mocks.findByLearnerId(l.getId()).stream()
                    .filter(m -> "PENDING".equals(m.getStatus())).count();

        Instant lastSubmission = Stream.concat(
                tasks.stream().map(Assignment::getSubmittedAt),
                pw.stream().map(ProjectWork::getSubmittedAt))
                .filter(Objects::nonNull).max(Instant::compareTo).orElse(null);

        Instant nextCall = calls.findByLearnerIdOrderByScheduledForDesc(l.getId()).stream()
                .filter(c -> c.getHeldAt() == null && c.getScheduledFor() != null)
                .map(ProgressCall::getScheduledFor)
                .filter(t -> t.isAfter(Instant.now().minus(2, ChronoUnit.DAYS)))
                .min(Instant::compareTo).orElse(null);

        /* a study row with no date is bad data, not a reason to 500 the whole roster */
        int weekMinutes = studyDays.findByLearnerIdOrderByDayDesc(l.getId()).stream()
                .filter(d -> d.getDay() != null)
                .filter(d -> !d.getDay().isBefore(LocalDate.now(IST).with(DayOfWeek.MONDAY)))
                .mapToInt(StudyDay::getMinutes).sum();

        long idleDays = u == null || u.getLastLoginAt() == null ? 999
                : ChronoUnit.DAYS.between(u.getLastLoginAt(), Instant.now());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("learnerId", l.getId());
        m.put("name", u == null ? "Learner" : u.getFullName());
        m.put("email", u == null ? null : u.getEmail());
        m.put("phone", u == null ? null : u.getPhone());
        m.put("trackType", l.getTrackType());
        m.put("batch", l.getBatchId() == null ? null
                : batches.findById(l.getBatchId()).map(Batch::getCode).orElse(null));
        m.put("bundle", l.getBundleId() == null ? null
                : bundles.findById(l.getBundleId()).map(Bundle::getName).orElse(null));
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
        List<String> risk = mentorService.riskReasons(l);
        m.put("atRisk", !risk.isEmpty());
        m.put("riskReasons", risk);
        m.put("mentor", l.getMentorId() == null ? null
                : users.findById(l.getMentorId()).map(User::getFullName).orElse(null));
        /*
         * The roster is built here, not by MentorService.learnerRow, so adding the watch
         * numbers there left this list showing a dash in the Videos column. Two row
         * builders for the same thing is the underlying problem; until they are merged,
         * both carry the same stats.
         */
        m.putAll(progressView.rowStats(l));
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

        List<Map<String, Object>> sessions = slots.findByMentorId(mentorId).stream()
                .filter(s -> s.getStartsAt() != null)
                .filter(s -> s.getStartsAt().atZone(IST).toLocalDate().equals(today))
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

        List<Map<String, Object>> callsDue = cover.learnersVisibleTo(mentorId).stream()
                .filter(l -> l.getTrackType() == Learner.TrackType.PREMIUM && !l.isOnHold())
                .map(l -> {
                    var next = calls.findByLearnerIdOrderByScheduledForDesc(l.getId()).stream()
                            .filter(c -> c.getHeldAt() == null && c.getScheduledFor() != null)
                            .min(Comparator.comparing(ProgressCall::getScheduledFor));
                    if (next.isEmpty()) return null;
                    Instant when = next.get().getScheduledFor();
                    if (when.isAfter(now.plus(7, ChronoUnit.DAYS))) return null;
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("learnerId", l.getId());
                    m.put("name", users.findById(l.getUserId()).map(User::getFullName).orElse(""));
                    m.put("scheduledFor", when);
                    m.put("overdue", when.isBefore(now));
                    return m;
                }).filter(Objects::nonNull)
                .sorted(Comparator.comparing(m -> (Instant) m.get("scheduledFor")))
                .collect(Collectors.toList());

        List<String> ids = cover.learnersVisibleTo(mentorId).stream().map(Learner::getId).toList();
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
        out.put("reviewedThisWeek", assignments.findByLearnerIdIn(ids.isEmpty() ? List.of("-") : ids)
                .stream().filter(a -> a.getReviewedAt() != null)
                .filter(a -> !a.getReviewedAt().atZone(IST).toLocalDate()
                        .isBefore(today.with(DayOfWeek.MONDAY)))
                .count());
        out.put("cover", cover.stateFor(mentorId));
        out.put("callsHeldThisWeek", calls.findByMentorId(mentorId).stream()
                .filter(c -> c.getHeldAt() != null)
                .filter(c -> !c.getHeldAt().atZone(IST).toLocalDate()
                        .isBefore(today.with(DayOfWeek.MONDAY)))
                .count());
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

    public ResumeVersion markResumeReviewed(String resumeId) {
        ResumeVersion r = resumes.findById(resumeId).orElseThrow();
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

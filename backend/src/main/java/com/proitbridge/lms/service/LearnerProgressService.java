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
 * Everything known about one learner's progress, in one answer.
 *
 * The product already recorded all of this. It was recording where a learner stopped in
 * every video, which topics inside a chapter were finished, how many minutes were spent
 * on which days, which sessions were attended, which questions were asked. A mentor
 * could see none of it: the learner record drew one bar per module, and one bar per
 * module cannot answer "has he watched the videos", which is the question mentors
 * actually ask.
 *
 * This is deliberately a separate service rather than six more repositories on
 * MentorService, which already takes nineteen constructor arguments.
 */
@Service
public class LearnerProgressService {

    private final BundleRepository bundles;
    private final ModuleRepository modules;
    private final ChapterRepository chapters;
    private final TopicRepository topics;
    private final ProgressRepository progress;
    private final WatchPositionRepository watch;
    private final QuizAttemptRepository quizAttempts;
    private final AssignmentRepository assignments;
    private final AttendanceRepository attendance;
    private final SlotRepository slots;
    private final BookingRepository bookings;
    private final StudyDayRepository studyDays;
    private final ChapterNoteRepository notes;
    private final AiInteractionRepository ai;
    private final VideoRepository videos;

    public LearnerProgressService(BundleRepository bundles, ModuleRepository modules,
                                  ChapterRepository chapters, TopicRepository topics,
                                  ProgressRepository progress, WatchPositionRepository watch,
                                  QuizAttemptRepository quizAttempts, AssignmentRepository assignments,
                                  AttendanceRepository attendance, SlotRepository slots,
                                  BookingRepository bookings, StudyDayRepository studyDays,
                                  ChapterNoteRepository notes, AiInteractionRepository ai,
                                  VideoRepository videos) {
        this.bundles = bundles; this.modules = modules; this.chapters = chapters;
        this.topics = topics; this.progress = progress; this.watch = watch;
        this.quizAttempts = quizAttempts; this.assignments = assignments;
        this.attendance = attendance; this.slots = slots; this.bookings = bookings;
        this.studyDays = studyDays; this.notes = notes; this.ai = ai; this.videos = videos;
    }

    /**
     * The full picture, module by module, chapter by chapter, video by video.
     *
     * Watch position is the part that was missing entirely. A topic carries a video, and
     * the learner's position in that video is recorded on every play, so "started but
     * abandoned at 12%" is a state the product knew about and never showed anyone. That
     * distinction is the whole point: a chapter that is not ticked because nothing was
     * opened is a different conversation from one abandoned two minutes in.
     */
    public Map<String, Object> tree(Learner l) {
        Bundle bundle = l.getBundleId() == null ? null : bundles.findById(l.getBundleId()).orElse(null);
        if (bundle == null) {
            return Map.of("modules", List.of(), "summary", Map.of("hasCourse", false));
        }

        Map<String, Progress> byChapter = progress.findByLearnerId(l.getId()).stream()
                .filter(p -> p.getChapterId() != null)
                .collect(Collectors.toMap(Progress::getChapterId, p -> p, (a, b) -> a));
        Map<String, WatchPosition> byVideo = watch.findByLearnerIdOrderByUpdatedAtDesc(l.getId()).stream()
                .filter(w -> w.getVideoRef() != null)
                .collect(Collectors.toMap(WatchPosition::getVideoRef, w -> w, (a, b) -> a));
        Map<String, List<QuizAttempt>> attemptsByChapter = quizAttempts.findByLearnerId(l.getId()).stream()
                .filter(q -> q.getChapterId() != null)
                .collect(Collectors.groupingBy(QuizAttempt::getChapterId));
        Map<String, Assignment> taskByChapter = assignments.findByLearnerId(l.getId()).stream()
                .filter(a -> a.getChapterId() != null)
                .collect(Collectors.toMap(Assignment::getChapterId, a -> a, (a, b) -> a));
        Set<String> chaptersWithNotes = notes.findByLearnerId(l.getId()).stream()
                .map(ChapterNote::getChapterId).collect(Collectors.toSet());

        List<Map<String, Object>> moduleViews = new ArrayList<>();
        int totalTopics = 0, watchedTopics = 0, startedTopics = 0;
        int totalMinutes = 0, watchedMinutes = 0;
        Instant lastWatchedAt = null;

        for (String moduleId : bundle.getModuleIds()) {
            CourseModule mod = modules.findById(moduleId).orElse(null);
            if (mod == null) continue;

            List<Map<String, Object>> chapterViews = new ArrayList<>();
            int modTopics = 0, modWatched = 0;

            for (Chapter c : chapters.findByModuleIdOrderByPositionAsc(moduleId)) {
                Progress p = byChapter.get(c.getId());
                List<Topic> ts = topics.findByChapterIdOrderByPositionAsc(c.getId()).stream()
                        .filter(Topic::isActive).toList();

                List<Map<String, Object>> topicViews = new ArrayList<>();
                for (Topic t : ts) {
                    boolean done = p != null && p.getWatchedTopicIds() != null
                            && p.getWatchedTopicIds().contains(t.getId());
                    WatchPosition w = t.getVideoRef() == null ? null : byVideo.get(t.getVideoRef());

                    Map<String, Object> tv = new LinkedHashMap<>();
                    tv.put("id", t.getId());
                    tv.put("title", t.getTitle());
                    tv.put("position", t.getPosition());
                    tv.put("durationMin", t.getDurationMin());
                    tv.put("hasVideo", t.getVideoRef() != null);
                    tv.put("videoTitle", t.getVideoRef() == null ? null
                            : videos.findById(t.getVideoRef()).map(Video::getTitle).orElse(null));
                    /* marked done by the learner finishing it */
                    tv.put("done", done);
                    /* and separately, where the player actually got to */
                    tv.put("watchedPercent", w == null ? 0 : w.getPercent());
                    tv.put("watchedSeconds", w == null ? 0 : w.getSeconds());
                    tv.put("videoSeconds", w == null ? 0 : w.getDurationSec());
                    tv.put("lastWatchedAt", w == null ? null : w.getUpdatedAt());
                    tv.put("state", watchState(done, w));
                    topicViews.add(tv);

                    totalTopics++;
                    modTopics++;
                    totalMinutes += t.getDurationMin();
                    if (done) { watchedTopics++; modWatched++; watchedMinutes += t.getDurationMin(); }
                    else if (w != null && w.getPercent() > 0) startedTopics++;
                    /* a row written before updatedAt existed has none, and one null
                       timestamp must not take the whole page down with it */
                    if (w != null && w.getUpdatedAt() != null
                            && (lastWatchedAt == null || w.getUpdatedAt().isAfter(lastWatchedAt))) {
                        lastWatchedAt = w.getUpdatedAt();
                    }
                }

                List<QuizAttempt> attempts = attemptsByChapter.getOrDefault(c.getId(), List.of());
                Assignment task = taskByChapter.get(c.getId());

                Map<String, Object> cv = new LinkedHashMap<>();
                cv.put("id", c.getId());
                cv.put("title", c.getTitle());
                cv.put("position", c.getPosition());
                cv.put("topics", topicViews);
                cv.put("topicCount", ts.size());
                cv.put("topicsWatched", (int) topicViews.stream().filter(t -> Boolean.TRUE.equals(t.get("done"))).count());
                cv.put("topicsStarted", (int) topicViews.stream()
                        .filter(t -> !Boolean.TRUE.equals(t.get("done")) && (int) t.get("watchedPercent") > 0).count());
                cv.put("watched", p != null && p.isWatched());
                cv.put("durationMin", ts.stream().mapToInt(Topic::getDurationMin).sum());

                /*
                 * A chapter created before these nested blocks were added deserialises
                 * with them null, and calling isEnabled on one is a 500 for the whole
                 * learner record. Absent means not enabled.
                 */
                cv.put("hasTest", c.getTest() != null && c.getTest().isEnabled());
                cv.put("quizScore", p == null ? null : p.getQuizScore());
                cv.put("quizAttempts", attempts.size());
                cv.put("quizBest", attempts.stream().mapToDouble(QuizAttempt::getScore).max().orElse(-1) < 0
                        ? null : attempts.stream().mapToDouble(QuizAttempt::getScore).max().getAsDouble());
                cv.put("quizLastAt", attempts.stream().map(QuizAttempt::getTakenAt)
                        .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null));

                cv.put("hasTeachback", c.getTeachback() != null && c.getTeachback().isEnabled());
                cv.put("conceptCheckScore", p == null ? null : p.getConceptCheckScore());
                /* an answer the model could not read: the work is done, the grade is not */
                cv.put("conceptCheckPending", p != null && p.isConceptCheckPending());
                cv.put("conceptCheckAnswer", p == null ? null : p.getConceptCheckAnswer());

                cv.put("hasAssignment", c.getAssignment() != null && c.getAssignment().isEnabled());
                cv.put("taskStatus", p == null ? "NOT_STARTED" : p.getTaskStatus());
                cv.put("taskId", task == null ? null : task.getId());
                cv.put("taskSubmissions", task == null ? 0 : task.getSubmissionCount());
                cv.put("taskScore", task == null ? null : task.getScore());

                cv.put("hasNotes", chaptersWithNotes.contains(c.getId()));
                cv.put("updatedAt", p == null ? null : p.getUpdatedAt());
                chapterViews.add(cv);
            }

            Map<String, Object> mv = new LinkedHashMap<>();
            mv.put("id", mod.getId());
            mv.put("name", mod.getName());
            mv.put("chapters", chapterViews);
            mv.put("topicCount", modTopics);
            mv.put("topicsWatched", modWatched);
            mv.put("percent", modTopics == 0 ? 0 : Math.round(modWatched * 100f / modTopics));
            moduleViews.add(mv);
        }

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("hasCourse", true);
        summary.put("totalTopics", totalTopics);
        summary.put("topicsWatched", watchedTopics);
        summary.put("topicsStarted", startedTopics);
        summary.put("topicsUntouched", totalTopics - watchedTopics - startedTopics);
        summary.put("percent", totalTopics == 0 ? 0 : Math.round(watchedTopics * 100f / totalTopics));
        summary.put("totalMinutes", totalMinutes);
        summary.put("watchedMinutes", watchedMinutes);
        summary.put("lastWatchedAt", lastWatchedAt);
        summary.put("daysSinceWatched", lastWatchedAt == null ? null
                : ChronoUnit.DAYS.between(lastWatchedAt, Instant.now()));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("summary", summary);
        out.put("modules", moduleViews);
        out.put("attendance", attendanceView(l));
        out.put("effort", effortView(l));
        out.put("doubts", doubtsView(l));
        return out;
    }

    /**
     * Four states, because "not ticked" was hiding two very different situations.
     *
     * DONE and NOT_STARTED are obvious. STARTED means the player recorded a position and
     * the learner did not come back. NEARLY means they got past ninety per cent and
     * stopped, which is almost always a session that was interrupted rather than one
     * that was abandoned, and is worth a different sentence on a call.
     */
    private String watchState(boolean done, WatchPosition w) {
        if (done) return "DONE";
        if (w == null || w.getPercent() <= 0) return "NOT_STARTED";
        if (w.getPercent() >= 90) return "NEARLY";
        return "STARTED";
    }

    /** Sessions booked against sessions turned up to, which nothing was reading back. */
    private Map<String, Object> attendanceView(Learner l) {
        List<Attendance> rows = attendance.findByLearnerId(l.getId());
        long present = rows.stream().filter(a -> "PRESENT".equals(a.getState())).count();
        long late = rows.stream().filter(a -> "LATE".equals(a.getState())).count();
        long absent = rows.stream().filter(a -> "ABSENT".equals(a.getState())).count();

        List<Map<String, Object>> recent = rows.stream()
                .sorted(Comparator.comparing(Attendance::getMarkedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(10)
                .map(a -> {
                    Slot s = slots.findById(a.getSlotId()).orElse(null);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("state", a.getState());
                    m.put("at", s == null ? a.getMarkedAt() : s.getStartsAt());
                    m.put("kind", s == null ? null : s.getKind());
                    m.put("topic", s == null ? null : s.getTopic());
                    return m;
                }).collect(Collectors.toList());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("marked", rows.size());
        m.put("present", present);
        m.put("late", late);
        m.put("absent", absent);
        m.put("booked", bookings.findByLearnerId(l.getId()).size());
        m.put("rate", rows.isEmpty() ? null : Math.round((present + late) * 100f / rows.size()));
        m.put("recent", recent);
        return m;
    }

    /** Minutes on the last fourteen days, so effort is visible next to output. */
    private Map<String, Object> effortView(Learner l) {
        List<StudyDay> days = studyDays.findByLearnerIdOrderByDayDesc(l.getId());
        LocalDate today = LocalDate.now();
        Map<LocalDate, Integer> byDay = days.stream()
                .filter(d -> d.getDay() != null)
                .collect(Collectors.toMap(StudyDay::getDay, StudyDay::getMinutes, (a, b) -> a));

        List<Integer> last14 = new ArrayList<>();
        for (int i = 13; i >= 0; i--) last14.add(byDay.getOrDefault(today.minusDays(i), 0));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("last14", last14);
        m.put("weekMinutes", last14.subList(7, 14).stream().mapToInt(Integer::intValue).sum());
        m.put("previousWeekMinutes", last14.subList(0, 7).stream().mapToInt(Integer::intValue).sum());
        m.put("activeDays", last14.stream().filter(v -> v > 0).count());
        return m;
    }

    /** What they asked the assistant, which is the closest thing to a list of doubts. */
    private List<Map<String, Object>> doubtsView(Learner l) {
        return ai.findByLearnerIdAndKind(l.getId(), "DOUBT").stream()
                .sorted(Comparator.comparing(AiInteraction::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(10)
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("at", a.getCreatedAt());
                    m.put("question", a.getPrompt());
                    return m;
                }).collect(Collectors.toList());
    }

    /**
     * The one line version, for a roster row.
     *
     * A roster showed a single percentage, which is the chapters ticked. Two learners on
     * the same percentage can be in completely different places: one working steadily,
     * one who opened four videos, abandoned all of them and has not signed in for a
     * fortnight. These are the numbers that tell them apart.
     *
     * Built from three queries rather than by walking the whole tree, because a mentor
     * with forty learners loads forty of these at once and the tree is dozens of reads
     * each. Nothing here needs the course structure: a watch position already knows
     * whether its video was finished.
     */
    public Map<String, Object> rowStats(Learner l) {
        List<WatchPosition> ws = watch.findByLearnerIdOrderByUpdatedAtDesc(l.getId());
        long finished = ws.stream().filter(WatchPosition::isCompleted).count();
        long started = ws.stream().filter(w -> !w.isCompleted() && w.getPercent() > 0).count();
        Instant last = ws.stream().map(WatchPosition::getUpdatedAt)
                .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);

        List<Attendance> att = attendance.findByLearnerId(l.getId());
        long turnedUp = att.stream().filter(a -> !"ABSENT".equals(a.getState())).count();

        LocalDate today = LocalDate.now();
        int weekMinutes = studyDays.findByLearnerIdOrderByDayDesc(l.getId()).stream()
                .filter(d -> d.getDay() != null && !d.getDay().isBefore(today.minusDays(6)))
                .mapToInt(StudyDay::getMinutes).sum();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("videosFinished", finished);
        m.put("videosStarted", started);
        m.put("videosTouched", ws.size());
        m.put("lastWatchedAt", last);
        m.put("daysSinceWatched", last == null ? null : ChronoUnit.DAYS.between(last, Instant.now()));
        m.put("attendanceMarked", att.size());
        m.put("attendanceRate", att.isEmpty() ? null : Math.round(turnedUp * 100f / att.size()));
        m.put("weekMinutes", weekMinutes);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : Map.of();
    }
}

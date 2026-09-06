package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Check in, check out, and an honest total.
 *
 * The rule that matters: time is counted from heartbeats, not from a tab left open.
 * A session with no heartbeat for fifteen minutes is closed at its last heartbeat,
 * so a learner who walks away is never credited for the walk. That is what makes
 * the number worth showing at all.
 */
@Service
public class StudyTimeService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int IDLE_CUTOFF_MIN = 15;

    private final StudySessionRepository sessions;
    private final StudyDayRepository days;
    private final LearnerRepository learners;
    private final ProgressRepository progress;

    public StudyTimeService(StudySessionRepository sessions, StudyDayRepository days,
                            LearnerRepository learners, ProgressRepository progress) {
        this.sessions = sessions; this.days = days;
        this.learners = learners; this.progress = progress;
    }

    private static LocalDate today() { return LocalDate.now(IST); }

    public StudySession checkIn(String learnerId, String note) {
        List<StudySession> open = sessions.findByLearnerIdAndClosedBy(learnerId, "OPEN");
        if (!open.isEmpty()) return open.get(0);          // already in, do not double count
        StudySession s = new StudySession();
        s.setLearnerId(learnerId);
        s.setDay(today());
        s.setNote(note);
        return sessions.save(s);
    }

    /** Called every couple of minutes while the learner is actually doing something. */
    public Map<String, Object> heartbeat(String learnerId, String chapterId) {
        List<StudySession> open = sessions.findByLearnerIdAndClosedBy(learnerId, "OPEN");
        if (open.isEmpty()) return Map.of("checkedIn", false, "minutes", 0);
        StudySession s = open.get(0);
        s.setLastHeartbeatAt(Instant.now());
        if (chapterId != null) s.getChaptersTouched().add(chapterId);
        sessions.save(s);
        return Map.of("checkedIn", true, "minutes", liveMinutes(s));
    }

    public Map<String, Object> checkOut(String learnerId, String closedBy) {
        List<StudySession> open = sessions.findByLearnerIdAndClosedBy(learnerId, "OPEN");
        if (open.isEmpty()) return Map.of("checkedIn", false, "minutes", 0);
        StudySession s = open.get(0);
        close(s, closedBy);
        return Map.of("checkedIn", false, "minutes", s.getMinutes(),
                "todayMinutes", minutesOn(learnerId, today()));
    }

    private void close(StudySession s, String closedBy) {
        Instant end = s.getLastHeartbeatAt();
        s.setCheckedOutAt(Instant.now());
        s.setMinutes((int) Math.max(0, ChronoUnit.MINUTES.between(s.getCheckedInAt(), end)));
        s.setClosedBy(closedBy);
        sessions.save(s);
        settle(s.getLearnerId(), s.getDay());
    }

    private int liveMinutes(StudySession s) {
        return (int) Math.max(0, ChronoUnit.MINUTES.between(s.getCheckedInAt(), Instant.now()));
    }

    /** Forgotten check outs are closed at the last heartbeat, never at the sweep time. */
    @Scheduled(fixedDelay = 300_000)
    public void closeIdle() {
        Instant cutoff = Instant.now().minus(IDLE_CUTOFF_MIN, ChronoUnit.MINUTES);
        for (StudySession s : sessions.findByClosedBy("OPEN")) {
            if (s.getLastHeartbeatAt().isBefore(cutoff)) close(s, "IDLE");
        }
    }

    private void settle(String learnerId, LocalDate day) {
        List<StudySession> all = sessions.findByLearnerIdAndDay(learnerId, day);
        StudyDay d = days.findByLearnerIdAndDay(learnerId, day).orElseGet(StudyDay::new);
        d.setId(learnerId + ":" + day);
        d.setLearnerId(learnerId);
        d.setDay(day);
        d.setMinutes(all.stream().mapToInt(StudySession::getMinutes).sum());
        d.setSessions(all.size());
        d.setChaptersWatched((int) all.stream()
                .flatMap(x -> x.getChaptersTouched().stream()).distinct().count());
        days.save(d);
    }

    public int minutesOn(String learnerId, LocalDate day) {
        return days.findByLearnerIdAndDay(learnerId, day).map(StudyDay::getMinutes).orElse(0);
    }

    /** Everything the learner sees about their own time, in one payload. */
    public Map<String, Object> summary(String learnerId) {
        List<StudySession> open = sessions.findByLearnerIdAndClosedBy(learnerId, "OPEN");
        List<StudyDay> history = days.findByLearnerIdOrderByDayDesc(learnerId);
        Map<LocalDate, Integer> byDay = history.stream()
                .collect(Collectors.toMap(StudyDay::getDay, StudyDay::getMinutes, (a, b) -> a));

        LocalDate today = today();
        int todayMin = byDay.getOrDefault(today, 0) + (open.isEmpty() ? 0 : liveMinutes(open.get(0)));

        int week = 0;
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        for (LocalDate d = weekStart; !d.isAfter(today); d = d.plusDays(1)) {
            week += byDay.getOrDefault(d, 0);
        }

        // a streak breaks on a day with nothing, not on a quiet one
        int streak = 0;
        for (LocalDate d = today; ; d = d.minusDays(1)) {
            int m = byDay.getOrDefault(d, 0) + (d.equals(today) && !open.isEmpty() ? 1 : 0);
            if (m >= 10) streak++;
            else if (!d.equals(today)) break;
            else if (m < 10) { /* today still open, does not break it */ }
            if (streak > 400) break;
        }

        List<Map<String, Object>> last14 = new ArrayList<>();
        for (int i = 13; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            last14.add(Map.of("day", d.toString(), "minutes", byDay.getOrDefault(d, 0)));
        }

        int total = history.stream().mapToInt(StudyDay::getMinutes).sum() + todayMin
                - byDay.getOrDefault(today, 0);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("checkedIn", !open.isEmpty());
        m.put("since", open.isEmpty() ? null : open.get(0).getCheckedInAt());
        m.put("note", open.isEmpty() ? null : open.get(0).getNote());
        m.put("liveMinutes", open.isEmpty() ? 0 : liveMinutes(open.get(0)));
        m.put("todayMinutes", todayMin);
        m.put("weekMinutes", week);
        m.put("totalMinutes", total);
        m.put("streakDays", streak);
        m.put("last14", last14);
        m.put("recent", history.stream().limit(10).map(d -> Map.of(
                "day", d.getDay().toString(), "minutes", d.getMinutes(),
                "sessions", d.getSessions(), "chapters", d.getChaptersWatched())).toList());
        return m;
    }

    /** What a mentor sees: effort, not just output. */
    public Map<String, Object> forMentor(String learnerId) {
        List<StudyDay> history = days.findByLearnerIdOrderByDayDesc(learnerId);
        LocalDate today = today();
        int week = history.stream()
                .filter(d -> !d.getDay().isBefore(today.with(DayOfWeek.MONDAY)))
                .mapToInt(StudyDay::getMinutes).sum();
        int prevWeek = history.stream()
                .filter(d -> !d.getDay().isBefore(today.with(DayOfWeek.MONDAY).minusWeeks(1))
                        && d.getDay().isBefore(today.with(DayOfWeek.MONDAY)))
                .mapToInt(StudyDay::getMinutes).sum();
        return Map.of(
                "weekMinutes", week,
                "previousWeekMinutes", prevWeek,
                "trend", week >= prevWeek ? "UP" : "DOWN",
                "activeDaysLast14", history.stream()
                        .filter(d -> !d.getDay().isBefore(today.minusDays(13)))
                        .filter(d -> d.getMinutes() >= 10).count(),
                "last14", history.stream().limit(14).map(d -> Map.of(
                        "day", d.getDay().toString(), "minutes", d.getMinutes())).toList());
    }

    public Learner requireLearner(String userId) {
        return learners.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "This account is not a learner account."));
    }
}

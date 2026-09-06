package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * What today looks like.
 *
 * Self paced learners drift because nothing ever tells them where to start. This is
 * one short answer: the next chapter, anything waiting on them, and the next session
 * they can join. Everything here is derived, so it can never disagree with the rest
 * of the LMS.
 */
@Service
public class ThisWeekService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LearnerRepository learners;
    private final ChapterRepository chapters;
    private final TopicRepository topics;
    private final ModuleRepository modules;
    private final BundleRepository bundles;
    private final ProgressRepository progress;
    private final AssignmentRepository assignments;
    private final SlotRepository slots;
    private final BookingRepository bookings;
    private final ProgressCallRepository calls;
    private final MockRequestRepository mocks;
    private final StudyTimeService studyTime;
    private final LearnerService learnerService;

    public ThisWeekService(LearnerRepository learners, ChapterRepository chapters,
                           TopicRepository topics,
                           ModuleRepository modules, BundleRepository bundles,
                           ProgressRepository progress, AssignmentRepository assignments,
                           SlotRepository slots, BookingRepository bookings,
                           ProgressCallRepository calls, MockRequestRepository mocks,
                           StudyTimeService studyTime, LearnerService learnerService) {
        this.learners = learners; this.chapters = chapters; this.topics = topics;
        this.modules = modules;
        this.bundles = bundles; this.progress = progress; this.assignments = assignments;
        this.slots = slots; this.bookings = bookings; this.calls = calls; this.mocks = mocks;
        this.studyTime = studyTime; this.learnerService = learnerService;
    }

    public Map<String, Object> forLearner(String userId) {
        Learner l = learnerService.require(userId);
        Map<String, Object> out = new LinkedHashMap<>();

        if (l.isOnHold()) {
            out.put("onHold", true);
            out.put("holdReason", l.getHoldReason());
            out.put("headline", "Your learning is on hold. Talk to your mentor when you are ready to restart.");
            return out;
        }

        if (!l.modulesUnlocked()) {
            out.put("headline", "Finish onboarding and your roadmap opens.");
            out.put("nextAction", Map.of("label", "Go to onboarding", "to", "/learn"));
            out.put("items", List.of());
            return out;
        }

        List<Map<String, Object>> items = new ArrayList<>();

        /* the single next thing to watch */
        Map<String, Object> next = nextChapter(l);
        if (next != null) {
            items.add(Map.of("kind", "CHAPTER", "title", next.get("title"),
                    "detail", next.get("module") + " \u00b7 " + next.get("durationMin") + " minutes",
                    "to", "/learn/chapter/" + next.get("id"), "cta", "Continue"));
        }

        /* anything sitting with the learner, not with the mentor */
        assignments.findByLearnerId(l.getId()).stream()
                .filter(a -> "CHANGES".equals(a.getStatus()))
                .forEach(a -> items.add(Map.of("kind", "TASK", "title", a.getTitle(),
                        "detail", "Your mentor asked for changes", "to", "/learn/projects",
                        "cta", "Open")));

        /* the next session they can actually attend */
        Instant now = Instant.now();
        slots.findAll().stream()
                .filter(s -> s.getStartsAt() != null && s.getStartsAt().isAfter(now))
                .filter(s -> "BOTH".equals(s.getTrackScope())
                        || s.getTrackScope().equals(l.getTrackType().name()))
                .filter(s -> s.getBatchId() == null || s.getBatchId().equals(l.getBatchId()))
                .min(Comparator.comparing(Slot::getStartsAt))
                .ifPresent(s -> items.add(Map.of("kind", "SESSION",
                        "title", label(s.getKind()),
                        "detail", pretty(s.getStartsAt())
                                + (bookings.findBySlotIdAndLearnerId(s.getId(), l.getId()).isPresent()
                                    ? " \u00b7 booked" : ""),
                        "to", "/learn/sessions", "cta", "Open")));

        /* a scheduled call is a commitment, so it belongs here */
        calls.findByLearnerIdOrderByScheduledForDesc(l.getId()).stream()
                .filter(c -> c.getHeldAt() == null && c.getScheduledFor() != null)
                .filter(c -> c.getScheduledFor().isAfter(now))
                .min(Comparator.comparing(ProgressCall::getScheduledFor))
                .ifPresent(c -> items.add(Map.of("kind", "CALL", "title", "Progress call",
                        "detail", pretty(c.getScheduledFor()), "to", "/learn/sessions", "cta", "Details")));

        mocks.findByLearnerId(l.getId()).stream()
                .filter(m -> "SCHEDULED".equals(m.getStatus()) && m.getScheduledFor() != null)
                .findFirst()
                .ifPresent(m -> items.add(Map.of("kind", "MOCK", "title", "Mock interview",
                        "detail", pretty(m.getScheduledFor()), "to", "/learn/mock", "cta", "Details")));

        Map<String, Object> study = studyTime.summary(l.getId());
        int week = (Integer) study.get("weekMinutes");

        out.put("headline", headline(items, week));
        out.put("items", items);
        out.put("weekMinutes", week);
        out.put("streakDays", study.get("streakDays"));
        out.put("checkedIn", study.get("checkedIn"));
        return out;
    }

    private String headline(List<Map<String, Object>> items, int weekMinutes) {
        if (items.isEmpty()) return "Nothing waiting on you. Well ahead.";
        long changes = items.stream().filter(i -> "TASK".equals(i.get("kind"))).count();
        if (changes > 0) return "Your mentor sent something back. Start there.";
        if (weekMinutes == 0) return "Nothing logged this week yet. One chapter is a fine start.";
        return "Here is what today looks like.";
    }

    /** The first chapter that is open and not finished. */
    private Map<String, Object> nextChapter(Learner l) {
        Bundle b = l.getBundleId() == null ? null : bundles.findById(l.getBundleId()).orElse(null);
        if (b == null) return null;
        Map<String, Progress> byChapter = new HashMap<>();
        progress.findByLearnerId(l.getId()).forEach(p -> byChapter.put(p.getChapterId(), p));

        for (String moduleId : b.getModuleIds()) {
            if (l.getFastForwardTopicIds().contains(moduleId)) continue;   // marked review only
            for (Chapter c : chapters.findByModuleIdOrderByPositionAsc(moduleId)) {
                Progress p = byChapter.get(c.getId());
                boolean done = p != null && p.isWatched()
                        && (!c.getTest().isEnabled() || p.getQuizScore() != null)
                        && (!c.getAssignment().isEnabled() || "APPROVED".equals(p.getTaskStatus()));
                if (!done) {
                    return Map.of("id", c.getId(), "title", c.getTitle(),
                            "durationMin", topics.findByChapterIdOrderByPositionAsc(c.getId())
                                    .stream().mapToInt(Topic::getDurationMin).sum(),
                            "module", modules.findById(moduleId).map(CourseModule::getName).orElse(""));
                }
            }
        }
        return null;
    }

    private String label(String kind) {
        return switch (kind) {
            case "ONBOARDING" -> "Onboarding call";
            case "INDUCTION" -> "Induction";
            case "DOUBT" -> "Doubt clearing";
            case "GROUP_DOUBT" -> "Group doubt clearing";
            case "LIVE" -> "Live session";
            case "PROJECT" -> "Project session";
            case "MOCK" -> "Mock interview";
            default -> kind;
        };
    }

    private String pretty(Instant at) {
        ZonedDateTime z = at.atZone(IST);
        long days = ChronoUnit.DAYS.between(LocalDate.now(IST), z.toLocalDate());
        String when = days == 0 ? "today" : days == 1 ? "tomorrow"
                : z.getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH);
        return when + ", " + z.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"));
    }
}

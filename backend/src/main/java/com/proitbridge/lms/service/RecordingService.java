package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import com.proitbridge.lms.service.video.VideoRefs;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The session library: what was actually held, published back.
 *
 * The thing that makes this work is not the publishing form, it is the queue. A session
 * whose end time has passed and has no recording sits on the mentor's desk ageing in
 * days, exactly like an unreviewed task. Without that, a library only fills when somebody
 * remembers, and nobody notices the weeks that are missing.
 *
 * Two rules come from how the sessions actually run. Doubt clearing is combined across
 * every batch, so it is never scoped to one. A recap belongs to a module and often runs
 * over two or three days, so it is a series and the parts stay together.
 */
@Service
public class RecordingService {

    /** What each live session kind produces, and who ends up able to watch it. */
    private static final Map<String, String> KIND_FROM_SLOT = Map.of(
            "GROUP_DOUBT", "DOUBT",
            "DOUBT", "DOUBT",
            "PROJECT", "PROJECT",
            "LIVE", "INDUSTRY",
            "INDUCTION", "INDUCTION",
            "MOCK", "MOCK_DEBRIEF");
    /* ONBOARDING is deliberately absent: a premium one to one is not library content. */

    /** Doubt clearing is open to everyone; an induction belongs to its batch. */
    private static final Map<String, String> DEFAULT_AUDIENCE = Map.of(
            "DOUBT", "ALL",
            "RECAP", "ALL",
            "INDUSTRY", "ALL",
            "PROJECT", "ALL",
            "INDUCTION", "BATCH",
            "MOCK_DEBRIEF", "TRACK");

    private final RecordingRepository recordings;
    private final SlotRepository slots;
    private final VideoRepository videos;
    private final ModuleRepository modules;
    private final ChapterRepository chapters;
    private final BatchRepository batches;
    private final LearnerRepository learners;
    private final UserRepository users;
    private final AttendanceRepository attendance;
    private final AiInteractionRepository aiLog;
    private final ActivityService activity;

    public RecordingService(RecordingRepository recordings, SlotRepository slots,
                            VideoRepository videos, ModuleRepository modules,
                            ChapterRepository chapters, BatchRepository batches,
                            LearnerRepository learners, UserRepository users,
                            AttendanceRepository attendance, AiInteractionRepository aiLog,
                            ActivityService activity) {
        this.recordings = recordings; this.slots = slots; this.videos = videos;
        this.modules = modules; this.chapters = chapters; this.batches = batches;
        this.learners = learners; this.users = users; this.attendance = attendance;
        this.aiLog = aiLog; this.activity = activity;
    }

    /* ============================================================ the queue */

    /**
     * Sessions that have finished and have no recording. This is the whole point:
     * ageing makes a missing week visible instead of silently absent.
     */
    public List<Map<String, Object>> pending(String mentorId) {
        Instant now = Instant.now();
        Set<String> recorded = recordings.findAll().stream()
                .map(Recording::getSlotId).filter(Objects::nonNull).collect(Collectors.toSet());

        return slots.findAll().stream()
                .filter(s -> mentorId == null || mentorId.equals(s.getMentorId()))
                .filter(s -> !s.isCancelled())
                .filter(s -> s.getStartsAt() != null)
                .filter(s -> s.getStartsAt().plus(s.getDurationMin(), ChronoUnit.MINUTES).isBefore(now))
                .filter(s -> !recorded.contains(s.getId()))
                // a session from six months ago is not a queue item, it is history
                .filter(s -> s.getStartsAt().isAfter(now.minus(60, ChronoUnit.DAYS)))
                .sorted(Comparator.comparing(Slot::getStartsAt))
                .map(s -> {
                    long days = ChronoUnit.DAYS.between(s.getStartsAt(), now);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("slotId", s.getId());
                    m.put("kind", s.getKind());
                    m.put("recordingKind", KIND_FROM_SLOT.getOrDefault(s.getKind(), "DOUBT"));
                    m.put("heldOn", s.getStartsAt());
                    m.put("durationMin", s.getDurationMin());
                    m.put("daysWaiting", days);
                    // a week without a recording is a gap somebody has to chase
                    m.put("overdue", days >= 7);
                    m.put("batch", s.getBatchId() == null ? null
                            : batches.findById(s.getBatchId()).map(Batch::getCode).orElse(null));
                    m.put("suggestedTitle", suggestTitle(s));
                    m.put("notes", s.getNotes());
                    m.put("attended", attendance.findBySlotId(s.getId()).stream()
                            .filter(a -> "PRESENT".equals(a.getState())).count());
                    return m;
                }).collect(Collectors.toList());
    }

    /** "Monday doubt clearing, 18 Aug" writes itself from what the slot already knows. */
    private String suggestTitle(Slot s) {
        String kind = switch (s.getKind()) {
            case "GROUP_DOUBT", "DOUBT" -> "Doubt clearing";
            case "PROJECT" -> "Project session";
            case "LIVE" -> "Live session";
            case "INDUCTION" -> "Induction";
            case "MOCK" -> "Mock debrief";
            default -> "Session";
        };
        var z = s.getStartsAt().atZone(java.time.ZoneId.of("Asia/Kolkata"));
        return kind + ", " + z.format(java.time.format.DateTimeFormatter.ofPattern("d MMM"));
    }

    /* ============================================================ publishing */

    public record PublishInput(String slotId, String kind, String title, String videoLink,
                               String fileId, String provider, String moduleId, String chapterId,
                               String seriesLabel, Integer partNumber, Integer totalParts,
                               String coveredSummary, String audience, String batchId,
                               String trackScope, Integer durationMin,
                               java.util.List<String> materialIds) {}

    public Recording publish(PublishInput in, String actorId, String actorEmail) {
        if ((in.videoLink() == null || in.videoLink().isBlank())
                && (in.fileId() == null || in.fileId().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Paste the recording link, or upload the file.");
        }
        Slot slot = in.slotId() == null ? null : slots.findById(in.slotId()).orElse(null);
        String kind = in.kind() != null ? in.kind()
                : slot != null ? KIND_FROM_SLOT.getOrDefault(slot.getKind(), "DOUBT") : "DOUBT";

        if ("RECAP".equals(kind) && (in.moduleId() == null || in.moduleId().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A recap belongs to a subject. Choose the module it covers.");
        }

        Video v = new Video();
        v.setTitle(in.title());
        v.setKind("RECORDING");
        v.setUploadedBy(actorId);
        if (in.videoLink() != null && !in.videoLink().isBlank()) {
            v.setProvider(in.provider() == null ? "YOUTUBE" : in.provider());
            v.setExternalId(VideoRefs.parse(in.videoLink(), v.getProvider()));
        } else {
            /* uploaded straight into the LMS: the file is the video, and it plays
               through the same grant flow as everything else */
            v.setProvider("UPLOAD");
            v.setExternalId(in.fileId());
        }
        v = videos.save(v);

        Recording r = new Recording();
        r.setTitle(in.title());
        r.setVideoRef(v.getId());
        r.setSlotId(in.slotId());
        r.setKind(kind);
        r.setModuleId(in.moduleId());
        r.setChapterId(in.chapterId());
        r.setSeriesLabel(in.seriesLabel());
        r.setPartNumber(in.partNumber() == null ? 0 : in.partNumber());
        r.setTotalParts(in.totalParts() == null ? 0 : in.totalParts());
        r.setCoveredSummary(in.coveredSummary());
        /* slides, datasets and handouts that went with the session */
        r.setFileIds(in.materialIds());
        r.setUploadedBy(actorId);
        r.setDurationMin(in.durationMin() != null ? in.durationMin()
                : slot != null ? slot.getDurationMin() : 0);
        r.setHeldOn(slot != null ? slot.getStartsAt() : Instant.now());

        String audience = in.audience() != null ? in.audience()
                : DEFAULT_AUDIENCE.getOrDefault(kind, "ALL");
        r.setAudience(audience);
        /* doubt clearing is combined across batches, so scoping it to one would hide it
           from most of the people who were actually in the room */
        r.setBatchId("BATCH".equals(audience)
                ? (in.batchId() != null ? in.batchId() : slot == null ? null : slot.getBatchId())
                : null);
        r.setTrackScope("TRACK".equals(audience) && in.trackScope() != null
                ? in.trackScope() : "BOTH");

        /* the session notes and what the cohort asked that week are already recorded
           elsewhere; attaching them is what makes a two hour video searchable */
        if (slot != null && slot.getNotes() != null) r.setNotes(slot.getNotes());
        r.setQuestionsCovered(questionsAround(r.getHeldOn(), in.chapterId()));

        Recording saved = recordings.save(r);
        if (slot != null) {
            slot.setRecordingId(saved.getId());
            slots.save(slot);
        }
        activity.log(actorId, actorEmail, "PUBLISH_RECORDING", "recording",
                kind + ": " + saved.getTitle());
        return saved;
    }

    /** What learners asked the assistant around that session, as the contents list. */
    private List<String> questionsAround(Instant heldOn, String chapterId) {
        if (heldOn == null) return List.of();
        Instant from = heldOn.minus(7, ChronoUnit.DAYS);
        return aiLog.findTop100ByOrderByCreatedAtDesc().stream()
                .filter(i -> "DOUBT".equals(i.getKind()))
                .filter(i -> i.getCreatedAt().isAfter(from) && i.getCreatedAt().isBefore(heldOn))
                .filter(i -> chapterId == null || chapterId.equals(i.getChapterId()))
                .map(i -> {
                    String p = i.getPrompt() == null ? "" : i.getPrompt();
                    int at = p.lastIndexOf("Question:");
                    String q = at >= 0 ? p.substring(at + 9).trim() : p;
                    return q.length() > 140 ? q.substring(0, 137) + "\u2026" : q;
                })
                .distinct().limit(8).collect(Collectors.toList());
    }

    public void unpublish(String id, String actorEmail) {
        Recording r = recordings.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such recording."));
        if (r.getSlotId() != null) {
            slots.findById(r.getSlotId()).ifPresent(s -> { s.setRecordingId(null); slots.save(s); });
        }
        if (r.getVideoRef() != null) {
            videos.findById(r.getVideoRef()).ifPresent(v -> { v.setActive(false); videos.save(v); });
        }
        recordings.delete(r);
        activity.log(null, actorEmail, "UNPUBLISH_RECORDING", "recording", r.getTitle());
    }

    /* ============================================================ the library */

    /** What this learner may watch, grouped the way they would look for it. */
    public Map<String, Object> libraryFor(Learner l) {
        List<Recording> mine = recordings.findAllByOrderByHeldOnDesc().stream()
                .filter(r -> visibleTo(r, l))
                .collect(Collectors.toList());

        Map<String, List<Map<String, Object>>> byKind = new LinkedHashMap<>();
        for (String kind : List.of("RECAP", "DOUBT", "PROJECT", "INDUSTRY", "INDUCTION", "MOCK_DEBRIEF")) {
            List<Map<String, Object>> rows = mine.stream()
                    .filter(r -> kind.equals(r.getKind()))
                    .map(this::view).collect(Collectors.toList());
            if (!rows.isEmpty()) byKind.put(kind, rows);
        }

        /* a recap runs over several days, so the parts belong together rather than
           scattered through a list by date */
        Map<String, List<Map<String, Object>>> series = mine.stream()
                .filter(r -> r.getSeriesLabel() != null && !r.getSeriesLabel().isBlank())
                .sorted(Comparator.comparingInt(Recording::getPartNumber))
                .collect(Collectors.groupingBy(Recording::getSeriesLabel, LinkedHashMap::new,
                        Collectors.mapping(this::view, Collectors.toList())));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("byKind", byKind);
        out.put("series", series);
        out.put("total", mine.size());
        return out;
    }

    /** Recap and doubt sessions that belong to a module, shown on the module itself. */
    public List<Map<String, Object>> forTopic(String moduleId, Learner l) {
        return recordings.findByModuleIdOrderByPartNumberAsc(moduleId).stream()
                .filter(r -> visibleTo(r, l))
                .map(this::view).collect(Collectors.toList());
    }

    public List<Map<String, Object>> forChapter(String chapterId, Learner l) {
        return recordings.findByChapterId(chapterId).stream()
                .filter(r -> visibleTo(r, l))
                .map(this::view).collect(Collectors.toList());
    }

    private boolean visibleTo(Recording r, Learner l) {
        return switch (r.getAudience() == null ? "ALL" : r.getAudience()) {
            case "BATCH" -> r.getBatchId() != null && r.getBatchId().equals(l.getBatchId());
            case "TRACK" -> "BOTH".equals(r.getTrackScope())
                    || r.getTrackScope().equals(l.getTrackType().name());
            default -> true;      // doubt clearing and recaps are open to everyone
        };
    }

    private Map<String, Object> view(Recording r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("title", r.getTitle());
        m.put("kind", r.getKind());
        m.put("videoRef", r.getVideoRef());
        m.put("heldOn", r.getHeldOn());
        m.put("durationMin", r.getDurationMin());
        m.put("notes", r.getNotes());
        m.put("coveredSummary", r.getCoveredSummary());
        m.put("questionsCovered", r.getQuestionsCovered());
        m.put("seriesLabel", r.getSeriesLabel());
        m.put("partNumber", r.getPartNumber());
        m.put("totalParts", r.getTotalParts());
        m.put("module", r.getModuleId() == null ? null
                : modules.findById(r.getModuleId()).map(CourseModule::getName).orElse(null));
        m.put("chapter", r.getChapterId() == null ? null
                : chapters.findById(r.getChapterId()).map(Chapter::getTitle).orElse(null));
        return m;
    }
}

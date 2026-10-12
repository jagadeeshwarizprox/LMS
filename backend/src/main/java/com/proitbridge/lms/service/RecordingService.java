package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import com.proitbridge.lms.service.video.VideoRefs;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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
    private final MentorCoverService cover;
    private final MongoTemplate mongo;

    public RecordingService(RecordingRepository recordings, SlotRepository slots,
                            VideoRepository videos, ModuleRepository modules,
                            ChapterRepository chapters, BatchRepository batches,
                            LearnerRepository learners, UserRepository users,
                            AttendanceRepository attendance, AiInteractionRepository aiLog,
                            ActivityService activity, MentorCoverService cover,
                            MongoTemplate mongo) {
        this.mongo = mongo;
        this.recordings = recordings; this.slots = slots; this.videos = videos;
        this.modules = modules; this.chapters = chapters; this.batches = batches;
        this.learners = learners; this.users = users; this.attendance = attendance;
        this.aiLog = aiLog; this.activity = activity; this.cover = cover;
    }

    /* ============================================================ the queue */

    /**
     * Sessions that have finished and have no recording. This is the whole point:
     * ageing makes a missing week visible instead of silently absent.
     */
    public List<Map<String, Object>> pending(String mentorId) {
        Instant now = Instant.now();
        /*
         * Only the window that can be in the queue is read: sessions of a recordable kind
         * that started in the last sixty days (a session from six months ago is not a
         * queue item, it is history), over the (mentorId, startsAt) or startsAt index.
         * Which of them already have a recording is one slotId $in read, not the whole
         * recordings collection.
         */
        List<Slot> window = heldBetween(mentorId, now.minus(60, ChronoUnit.DAYS), now);
        Set<String> recorded = recordedSlotIds(window);
        List<Slot> due = window.stream()
                .filter(s -> !recorded.contains(s.getId()))
                .collect(Collectors.toList());
        Map<String, String> batchCodes = new HashMap<>();
        batches.findAllById(due.stream().map(Slot::getBatchId).filter(Objects::nonNull)
                .collect(Collectors.toSet())).forEach(b -> batchCodes.put(b.getId(), b.getCode()));
        Map<String, Long> present = presentCounts(due.stream().map(Slot::getId).toList());

        return due.stream()
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
                    m.put("batch", s.getBatchId() == null ? null : batchCodes.get(s.getBatchId()));
                    m.put("suggestedTitle", suggestTitle(s));
                    m.put("notes", s.getNotes());
                    m.put("attended", present.getOrDefault(s.getId(), 0L));
                    return m;
                }).collect(Collectors.toList());
    }

    /**
     * Finished, uncancelled sessions of a recordable kind that started after `from`,
     * oldest first. A premium one to one is not library content, so it is never read.
     */
    private List<Slot> heldBetween(String mentorId, Instant from, Instant now) {
        Criteria c = mentorId == null ? Criteria.where("startsAt").gt(from).lt(now)
                : Criteria.where("mentorId").is(mentorId).and("startsAt").gt(from).lt(now);
        c = c.and("kind").in(KIND_FROM_SLOT.keySet()).and("cancelled").ne(true);
        return mongo.find(Query.query(c).with(Sort.by(Sort.Direction.ASC, "startsAt")), Slot.class).stream()
                .filter(s -> s.getStartsAt().plus(s.getDurationMin(), ChronoUnit.MINUTES).isBefore(now))
                .collect(Collectors.toList());
    }

    /** Which of these sessions already have a recording, over the slotId index. */
    private Set<String> recordedSlotIds(Collection<Slot> held) {
        if (held.isEmpty()) return new HashSet<>();
        return recordings.findBySlotIdIn(held.stream().map(Slot::getId).toList()).stream()
                .map(Recording::getSlotId).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    /** PRESENT marks per session, as one grouped count over the attendance slotId index. */
    private Map<String, Long> presentCounts(Collection<String> slotIds) {
        Map<String, Long> out = new HashMap<>();
        if (slotIds.isEmpty()) return out;
        for (org.bson.Document d : mongo.aggregate(Aggregation.newAggregation(
                        Aggregation.match(Criteria.where("slotId").in(slotIds).and("state").is("PRESENT")),
                        Aggregation.group("slotId").count().as("n")),
                Attendance.class, org.bson.Document.class).getMappedResults()) {
            Object n = d.get("n");
            out.put(String.valueOf(d.get("_id")), n instanceof Number x ? x.longValue() : 0L);
        }
        return out;
    }

    /** "Monday doubt clearing, 18 Aug" writes itself from what the slot already knows. */
    private String suggestTitle(Slot s) {
        String kind = switch (s.getKind()) {
            case "GROUP_DOUBT", "DOUBT" -> "Doubt clearing";
            case "PROJECT" -> "Project session";
            case "LIVE" -> "Live session";
            case "INTERACTIVE" -> "Interactive session";
            case "DEBATE" -> "Debate session";
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
        return publish(in, actorId, actorEmail, "MENTOR");
    }

    /**
     * Publishing, with the caller's role.
     *
     * Any mentor could publish against any session id, and publishing twice for the same
     * session left two recordings with the slot pointing at whichever came last, the
     * other orphaned in the library. A session takes one recording: take the first down
     * to replace it. A mentor publishes for sessions they ran; the office for any.
     */
    public Recording publish(PublishInput in, String actorId, String actorEmail, String actorRole) {
        if (in.slotId() != null && !in.slotId().isBlank()) {
            Slot target = slots.findById(in.slotId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such session."));
            boolean office = "ADMIN".equals(actorRole) || "SUPER_ADMIN".equals(actorRole);
            /* a mentor covering for the host ran the session, so the recording is theirs to publish */
            boolean ran = actorId != null && (actorId.equals(target.getMentorId())
                    || (target.getMentorId() != null && cover.canActOn(actorId, target.getMentorId())));
            if (!office && !ran) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "That session is somebody else's. Whoever ran it, or the office, publishes its recording.");
            }
            if (target.getRecordingId() != null && !target.getRecordingId().isBlank()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This session already has a recording. Take that one down first to replace it.");
            }
        }
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

    /** Who published a recording, so a mentor can only take down their own. */
    public String ownerOf(String id) {
        return recordings.findById(id).map(Recording::getUploadedBy)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such recording."));
    }

    /**
     * What has already gone out, newest first. A mentor sees their own; the office
     * passes null and sees everything. This is where a wrong upload is taken down.
     */
    public List<Map<String, Object>> published(String uploaderId) {
        /* the newest hundred straight off the heldOn index, filtered in the database */
        Query q = (uploaderId == null ? new Query()
                : Query.query(Criteria.where("uploadedBy").is(uploaderId)))
                .with(Sort.by(Sort.Direction.DESC, "heldOn")).limit(100);
        List<Recording> rows = mongo.find(q, Recording.class);
        Map<String, String> names = new HashMap<>();
        users.findAllById(rows.stream().map(Recording::getUploadedBy).filter(Objects::nonNull)
                .collect(Collectors.toSet())).forEach(u -> names.put(u.getId(), u.getFullName()));
        ViewNames vn = viewNames(rows);
        return rows.stream()
                .map(r -> {
                    Map<String, Object> m = view(r, vn);
                    m.put("uploadedById", r.getUploadedBy());
                    m.put("uploadedBy", r.getUploadedBy() == null ? null : names.get(r.getUploadedBy()));
                    m.put("audience", r.getAudience());
                    return m;
                }).collect(Collectors.toList());
    }

    /**
     * Recording coverage: of the sessions that should produce a recording, how many
     * did, per mentor, over the window. The pending queue answers "what do I owe";
     * this answers "who is behind", which is the question the office asks.
     */
    public Map<String, Object> coverage(int days) {
        Instant now = Instant.now();
        Instant from = now.minus(Math.max(1, days), ChronoUnit.DAYS);
        /* the window only, over the startsAt index, and its recordings by slotId */
        List<Slot> held = heldBetween(null, from, now);
        Set<String> recorded = recordedSlotIds(held);
        Map<String, String> mentorNames = new HashMap<>();
        users.findAllById(held.stream().map(Slot::getMentorId).filter(Objects::nonNull)
                .collect(Collectors.toSet())).forEach(u -> mentorNames.put(u.getId(), u.getFullName()));

        Map<String, List<Slot>> byMentor = held.stream().collect(Collectors.groupingBy(
                s -> s.getMentorId() == null ? "" : s.getMentorId(), LinkedHashMap::new, Collectors.toList()));

        List<Map<String, Object>> rows = new ArrayList<>();
        int totalHeld = 0, totalRecorded = 0, totalOverdue = 0;
        for (var e : byMentor.entrySet()) {
            int h = e.getValue().size();
            int r = (int) e.getValue().stream().filter(s -> recorded.contains(s.getId())).count();
            int overdue = (int) e.getValue().stream()
                    .filter(s -> !recorded.contains(s.getId()))
                    .filter(s -> ChronoUnit.DAYS.between(s.getStartsAt(), now) >= 7).count();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("mentorId", e.getKey().isEmpty() ? null : e.getKey());
            m.put("mentor", e.getKey().isEmpty() ? "Nobody assigned"
                    : Optional.ofNullable(mentorNames.get(e.getKey())).orElse("Unknown"));
            m.put("held", h);
            m.put("recorded", r);
            m.put("missing", h - r);
            m.put("overdue", overdue);
            m.put("percent", h == 0 ? 100 : Math.round(r * 100.0 / h));
            rows.add(m);
            totalHeld += h; totalRecorded += r; totalOverdue += overdue;
        }
        rows.sort((a, b) -> Integer.compare((int) b.get("missing"), (int) a.get("missing")));

        List<Map<String, Object>> missing = pending(null).stream()
                .filter(m -> ((Instant) m.get("heldOn")).isAfter(from))
                .peek(m -> {
                    String mentorId = held.stream().filter(s -> s.getId().equals(m.get("slotId")))
                            .map(Slot::getMentorId).findFirst().orElse(null);
                    m.put("mentor", mentorId == null ? null : mentorNames.get(mentorId));
                })
                .collect(Collectors.toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("held", totalHeld);
        out.put("recorded", totalRecorded);
        out.put("overdue", totalOverdue);
        out.put("percent", totalHeld == 0 ? 100 : Math.round(totalRecorded * 100.0 / totalHeld));
        out.put("byMentor", rows);
        out.put("missing", missing);
        return out;
    }

    /* ============================================================ the library */

    /** What this learner may watch, grouped the way they would look for it. */
    public Map<String, Object> libraryFor(Learner l) {
        /*
         * The audience rule as a query, so only what this learner may watch is read: open
         * to all, their batch's, or their track's. visibleTo still runs over the result,
         * so the list and the rule cannot drift apart.
         */
        List<String> tracks = l.getTrackType() == null ? List.of("BOTH")
                : List.of("BOTH", l.getTrackType().name());
        List<Criteria> any = new ArrayList<>();
        any.add(Criteria.where("audience").nin("BATCH", "TRACK"));
        if (l.getBatchId() != null) any.add(Criteria.where("audience").is("BATCH").and("batchId").is(l.getBatchId()));
        any.add(Criteria.where("audience").is("TRACK").and("trackScope").in(tracks));
        List<Recording> mine = mongo.find(Query.query(new Criteria().orOperator(any.toArray(new Criteria[0])))
                        .with(Sort.by(Sort.Direction.DESC, "heldOn")), Recording.class).stream()
                .filter(r -> visibleTo(r, l))
                .collect(Collectors.toList());
        ViewNames vn = viewNames(mine);

        Map<String, List<Map<String, Object>>> byKind = new LinkedHashMap<>();
        for (String kind : List.of("RECAP", "DOUBT", "PROJECT", "INDUSTRY", "INDUCTION", "MOCK_DEBRIEF")) {
            List<Map<String, Object>> rows = mine.stream()
                    .filter(r -> kind.equals(r.getKind()))
                    .map(r -> view(r, vn)).collect(Collectors.toList());
            if (!rows.isEmpty()) byKind.put(kind, rows);
        }

        /* a recap runs over several days, so the parts belong together rather than
           scattered through a list by date */
        Map<String, List<Map<String, Object>>> series = mine.stream()
                .filter(r -> r.getSeriesLabel() != null && !r.getSeriesLabel().isBlank())
                .sorted(Comparator.comparingInt(Recording::getPartNumber))
                .collect(Collectors.groupingBy(Recording::getSeriesLabel, LinkedHashMap::new,
                        Collectors.mapping((Recording r) -> view(r, vn), Collectors.toList())));

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

    /** Module and chapter names for a list of recordings, read once for the list. */
    private record ViewNames(Map<String, String> modules, Map<String, String> chapters) {}

    private ViewNames viewNames(Collection<Recording> rs) {
        Map<String, String> mods = new HashMap<>();
        Map<String, String> chs = new HashMap<>();
        Set<String> mids = rs.stream().map(Recording::getModuleId).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> cids = rs.stream().map(Recording::getChapterId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (!mids.isEmpty()) modules.findAllById(mids).forEach(x -> mods.put(x.getId(), x.getName()));
        if (!cids.isEmpty()) chapters.findAllById(cids).forEach(x -> chs.put(x.getId(), x.getTitle()));
        return new ViewNames(mods, chs);
    }

    private Map<String, Object> view(Recording r) {
        return view(r, viewNames(List.of(r)));
    }

    private Map<String, Object> view(Recording r, ViewNames vn) {
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
        m.put("module", r.getModuleId() == null ? null : vn.modules().get(r.getModuleId()));
        m.put("chapter", r.getChapterId() == null ? null : vn.chapters().get(r.getChapterId()));
        return m;
    }
}

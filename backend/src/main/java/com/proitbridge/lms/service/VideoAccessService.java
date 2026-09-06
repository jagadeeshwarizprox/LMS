package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import com.proitbridge.lms.service.video.VideoProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The only place a provider identifier is ever handed out.
 *
 * Nothing else in the API returns one: not the chapter payload, not the roadmap,
 * not the recordings list. A client asks for one video at the moment it presses
 * play, entitlement is re-checked from scratch, and the grant is logged. So a
 * learner cannot enumerate the catalogue, and a leak has a name on it.
 */
@Service
public class VideoAccessService {

    private final VideoRepository videos;
    private final ChapterRepository chapters;
    private final TopicRepository topics;
    private final RecordingRepository recordings;
    private final LearnerRepository learners;
    private final BatchRepository batches;
    private final BundleRepository bundles;
    private final ProgressRepository progress;
    private final VideoAccessLogRepository logs;
    private final Map<String, VideoProvider> providers = new ConcurrentHashMap<>();
    private final ActivityService activity;
    private final String defaultProvider;

    /** userId -> when their current stream grant expires. One stream per account. */
    private final Map<String, Instant> streaming = new ConcurrentHashMap<>();

    public VideoAccessService(VideoRepository videos, ChapterRepository chapters,
                              TopicRepository topics,
                              RecordingRepository recordings, LearnerRepository learners,
                              BatchRepository batches, BundleRepository bundles,
                              ProgressRepository progress, VideoAccessLogRepository logs,
                              List<VideoProvider> providerList, ActivityService activity,
                              @Value("${lms.video.provider}") String defaultProvider) {
        this.videos = videos; this.chapters = chapters; this.topics = topics; this.recordings = recordings;
        this.learners = learners; this.batches = batches; this.bundles = bundles;
        this.progress = progress; this.logs = logs; this.activity = activity;
        this.defaultProvider = defaultProvider;
        providerList.forEach(p -> providers.put(p.key(), p));
    }

    public record GrantRequest(String userId, String sessionId, String deviceId, String ip) {}

    public Map<String, Object> grant(String videoRef, GrantRequest req) {
        Video v = videos.findById(videoRef)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such video."));
        if (!v.isActive()) {
            refuse(v, req, "INACTIVE");
            throw new ResponseStatusException(HttpStatus.GONE, "This video is no longer available.");
        }

        Learner learner = learners.findByUserId(req.userId()).orElse(null);
        if (learner != null) {
            String denial = denialFor(v, learner);
            if (denial != null) {
                refuse(v, req, denial);
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, denial);
            }
            Instant open = streaming.get(req.userId());
            if (open != null && open.isAfter(Instant.now()) && !req.sessionId().equals(streamSession.get(req.userId()))) {
                refuse(v, req, "CONCURRENT");
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This account is already watching something else. Close that first.");
            }
        }

        VideoProvider provider = providers.get(
                v.getProvider() == null ? defaultProvider : v.getProvider());
        if (provider == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No player is configured for this video.");
        }

        var g = provider.grant(v, req.deviceId());
        streaming.put(req.userId(), Instant.now().plusSeconds(g.ttlSeconds() + 30));
        streamSession.put(req.userId(), req.sessionId());

        VideoAccessLog log = new VideoAccessLog();
        log.setUserId(req.userId());
        log.setLearnerId(learner == null ? null : learner.getId());
        log.setVideoId(v.getId());
        log.setSessionId(req.sessionId());
        log.setDeviceId(req.deviceId());
        log.setIp(req.ip());
        log.setExpiresAt(Instant.now().plusSeconds(g.ttlSeconds()));
        logs.save(log);

        return Map.of(
                "provider", g.provider(),
                "playbackId", g.playbackId() == null ? "" : g.playbackId(),
                "playbackUrl", g.playbackUrl() == null ? "" : g.playbackUrl(),
                "ttlSeconds", g.ttlSeconds(),
                "title", v.getTitle() == null ? "" : v.getTitle());
    }

    private final Map<String, String> streamSession = new ConcurrentHashMap<>();

    /** Entitlement, re-derived rather than trusted from the client. */
    private String denialFor(Video v, Learner l) {
        switch (v.getKind()) {
            case "GUIDE", "PREREQ" -> { return null; }   // part of onboarding itself
            case "INDUCTION" -> {
                return l.getBatchId() != null && l.getBatchId().equals(v.getBatchId())
                        ? null : "This induction belongs to another batch.";
            }
            case "TOPIC" -> {
                if (!l.modulesUnlocked()) return "Finish onboarding to open your modules.";
                Topic tp = topics.findAll().stream()
                        .filter(x -> v.getId().equals(x.getVideoRef())).findFirst().orElse(null);
                if (tp == null) return null;
                Bundle b = l.getBundleId() == null ? null : bundles.findById(l.getBundleId()).orElse(null);
                if (b == null || !b.getModuleIds().contains(tp.getModuleId())) {
                    return "This topic is not part of your course.";
                }
                Chapter c = chapters.findById(tp.getChapterId()).orElse(null);
                if (c == null) return null;
                if (chapterLocked(l, c)) return "Finish the chapter before this one first.";
                /* and inside the chapter, the topics run in order too */
                Progress p = progress.findByLearnerIdAndChapterId(l.getId(), c.getId()).orElse(null);
                for (Topic sib : topics.findByChapterIdOrderByPositionAsc(c.getId())) {
                    if (sib.getId().equals(tp.getId())) break;
                    boolean seen = p != null && p.getWatchedTopicIds().contains(sib.getId());
                    if (sib.isActive() && !seen) return "Finish " + sib.getTitle() + " first.";
                }
                return null;
            }
            case "RECORDING" -> {
                Recording r = recordings.findAll().stream()
                        .filter(x -> v.getId().equals(x.getVideoRef())).findFirst().orElse(null);
                if (r == null) return null;
                if (r.getBatchId() != null && !r.getBatchId().equals(l.getBatchId())) {
                    return "This recording belongs to another batch.";
                }
                if ("PREMIUM".equals(r.getTrackScope()) && l.getTrackType() != Learner.TrackType.PREMIUM) {
                    return "This recording is for premium learners.";
                }
                if ("BATCH".equals(r.getTrackScope()) && l.getTrackType() != Learner.TrackType.BATCH) {
                    return "This recording is for batch learners.";
                }
                return null;
            }
            default -> { return null; }
        }
    }

    private boolean chapterLocked(Learner l, Chapter c) {
        List<Chapter> siblings = chapters.findByModuleIdOrderByPositionAsc(c.getModuleId());
        for (Chapter prev : siblings) {
            if (prev.getId().equals(c.getId())) return false;
            Progress p = progress.findByLearnerIdAndChapterId(l.getId(), prev.getId()).orElse(null);
            boolean done = p != null && p.isWatched()
                    && (!prev.getTest().isEnabled() || p.getQuizScore() != null)
                    && (!prev.getAssignment().isEnabled() || "APPROVED".equals(p.getTaskStatus()));
            if (!done) return true;
        }
        return false;
    }

    private void refuse(Video v, GrantRequest req, String reason) {
        VideoAccessLog log = new VideoAccessLog();
        log.setUserId(req.userId());
        log.setVideoId(v.getId());
        log.setSessionId(req.sessionId());
        log.setDeviceId(req.deviceId());
        log.setIp(req.ip());
        log.setOutcome("REFUSED");
        log.setReason(reason);
        logs.save(log);
    }

    /* ------------------------------------------------------------- admin side */

    public Video save(Video body, String actorEmail) {
        if (body.getProvider() == null) body.setProvider(defaultProvider);
        Video saved = videos.save(body);
        activity.log(null, actorEmail, "SAVE_VIDEO", "video", saved.getTitle());
        return saved;
    }

    /**
     * The answer to a leaked unlisted link: re-upload, paste the new id here, and
     * every chapter, recording and session pointing at this video follows. The old
     * id is orphaned and worthless.
     */
    public Video rotate(String videoId, String newExternalId, String actorEmail) {
        Video v = videos.findById(videoId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such video."));
        String old = v.getExternalId();
        v.setExternalId(com.proitbridge.lms.service.video.VideoRefs.parse(
                newExternalId, v.getProvider()));
        videos.save(v);
        activity.log(null, actorEmail, "ROTATE_VIDEO", "video",
                v.getTitle() + " rotated away from " + mask(old));
        return v;
    }

    public List<Map<String, Object>> library() {
        return videos.findAll().stream().map(v -> Map.<String, Object>of(
                "id", v.getId(),
                "title", v.getTitle() == null ? "" : v.getTitle(),
                "kind", v.getKind(),
                "provider", v.getProvider(),
                "masked", mask(v.getExternalId()),
                "durationSec", v.getDurationSec(),
                "active", v.isActive(),
                "grantsLast30", logs.findByVideoId(v.getId()).stream()
                        .filter(l -> l.getIssuedAt().isAfter(Instant.now().minus(30, ChronoUnit.DAYS)))
                        .count()
        )).toList();
    }

    /** Even an admin listing shows the id masked. It is never needed to run the LMS. */
    private String mask(String id) {
        if (id == null || id.length() < 5) return "hidden";
        return id.substring(0, 2) + "\u2026" + id.substring(id.length() - 2);
    }

    public List<VideoAccessLog> recentGrants() {
        return logs.findTop200ByOrderByIssuedAtDesc();
    }
}

package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Resuming where you stopped.
 *
 * The player already knew the position; it simply threw it away every time somebody
 * closed the tab. A two hour recap watched in three sittings is unusable without this,
 * and finding your place by dragging a scrubber is the kind of small friction that
 * quietly stops people coming back.
 *
 * Positions near either end are not stored. Resuming at eleven seconds is worse than
 * starting, and a video watched to the end should offer to start again rather than
 * resume at the credits.
 */
@Service
public class WatchService {

    private static final int MIN_SECONDS = 20;      // below this, just start again
    private static final int COMPLETE_PERCENT = 95;

    private final WatchPositionRepository positions;
    private final VideoRepository videos;
    private final TopicRepository topics;
    private final RecordingRepository recordings;
    private final MongoTemplate mongo;

    public WatchService(WatchPositionRepository positions, VideoRepository videos,
                        TopicRepository topics, RecordingRepository recordings,
                        MongoTemplate mongo) {
        this.positions = positions; this.videos = videos;
        this.topics = topics; this.recordings = recordings; this.mongo = mongo;
    }

    /** Called every fifteen seconds while playing, and once on close. */
    public Map<String, Object> save(String learnerId, String videoRef, int seconds, int durationSec) {
        return save(learnerId, videoRef, seconds, durationSec, -1);
    }

    /**
     * The same, with the seconds the player counted as actually played.
     *
     * When the player sends it, progress is measured from that rather than from the
     * playhead, so a drag to the end is a resume point and not a finished video. A
     * caller that does not send it (-1) keeps the old position-based reading.
     */
    public Map<String, Object> save(String learnerId, String videoRef, int seconds, int durationSec,
                                    int watchedSec) {
        if (videoRef == null || durationSec <= 0) return Map.of("saved", false);

        int basis = watchedSec >= 0 ? Math.min(watchedSec, durationSec) : seconds;
        int percent = Math.min(100, (int) Math.round(basis * 100.0 / durationSec));
        WatchPosition w = positions.findByLearnerIdAndVideoRef(learnerId, videoRef)
                .orElseGet(WatchPosition::new);
        w.setLearnerId(learnerId);
        w.setVideoRef(videoRef);
        /*
         * The position moves freely; the progress only goes up.
         *
         * Percent used to be wherever the playhead was last reported, so rewinding to
         * rewatch a section knocked a finished video back to unfinished, dropped it into
         * continue watching again and un-did the "watched" the server now checks for.
         * Seconds is the resume point and follows the player both ways. Percent is how
         * far they have got, and completed, once reached, stays reached.
         */
        w.setSeconds(seconds);
        w.setDurationSec(durationSec);
        int reached = Math.max(w.getPercent(), percent);
        w.setPercent(reached);
        w.setCompleted(w.isCompleted() || reached >= COMPLETE_PERCENT);
        w.setUpdatedAt(Instant.now());

        if (w.getTitle() == null) describe(w);
        positions.save(w);
        return Map.of("saved", true, "percent", reached);
    }

    /** What the player asks for before it starts, so it knows whether to offer a resume. */
    public Map<String, Object> resumeFor(String learnerId, String videoRef) {
        return positions.findByLearnerIdAndVideoRef(learnerId, videoRef)
                .filter(w -> w.getSeconds() >= MIN_SECONDS)
                .filter(w -> !w.isCompleted())
                .map(w -> Map.<String, Object>of(
                        "resume", true,
                        "seconds", w.getSeconds(),
                        "percent", positionPercent(w),
                        /* how far they have actually got, which the player starts its
                           played-time count from on a resume */
                        "reached", w.getPercent(),
                        "label", clock(w.getSeconds())))
                .orElse(Map.of("resume", false));
    }

    /**
     * The furthest any of these videos has been watched, or -1 when none has a row at
     * all, so a caller can tell "never played" apart from "played a little".
     */
    public int bestPercent(String learnerId, Collection<String> refs) {
        int best = -1;
        if (refs == null || refs.isEmpty()) return best;
        /* one read over the (learnerId, videoRef) index instead of one per video */
        for (WatchPosition w : positions.findByLearnerIdAndVideoRefIn(learnerId, refs)) {
            best = Math.max(best, w.isCompleted() ? 100 : w.getPercent());
        }
        return best;
    }

    /** Progress on every video in a list, for the bar under each material. */
    public Map<String, Integer> percentsFor(String learnerId, Collection<String> refs) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (refs == null || refs.isEmpty()) return out;
        Map<String, WatchPosition> byRef = new HashMap<>();
        for (WatchPosition w : positions.findByLearnerIdAndVideoRefIn(learnerId, refs)) {
            byRef.putIfAbsent(w.getVideoRef(), w);
        }
        /* keeps the caller's order, as the per-ref loop did */
        for (String ref : refs) {
            WatchPosition w = byRef.get(ref);
            if (w != null) out.put(ref, w.getPercent());
        }
        return out;
    }

    /**
     * The continue watching row: started, not finished, most recent first. Across
     * topics and session recordings both, because a half watched recap is exactly
     * the thing somebody means to come back to.
     */
    public List<Map<String, Object>> continueWatching(String learnerId, int limit) {
        /*
         * The filter and the limit run in the database over the (learnerId, updatedAt)
         * index, so this reads at most `limit` rows rather than every video the learner
         * ever opened. A row with no title yet is described once by indexed lookups and
         * saved, so the next call does not work it out again.
         */
        Query q = Query.query(Criteria.where("learnerId").is(learnerId)
                        .and("completed").is(false)
                        .and("seconds").gte(MIN_SECONDS))
                .with(Sort.by(Sort.Direction.DESC, "updatedAt"))
                .limit(Math.max(0, limit));
        return mongo.find(q, WatchPosition.class).stream()
                .map(w -> {
                    if (w.getTitle() == null) { describe(w); positions.save(w); }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("videoRef", w.getVideoRef());
                    m.put("title", w.getTitle());
                    m.put("kind", w.getKind());
                    m.put("topicId", w.getTopicId());
                    m.put("chapterId", w.getChapterId());
                    /* where they will resume, to match "left" below; the furthest
                       reached is w.getPercent() and is what progress screens read */
                    m.put("percent", positionPercent(w));
                    m.put("seconds", w.getSeconds());
                    m.put("label", clock(w.getSeconds()));
                    m.put("left", clock(Math.max(0, w.getDurationSec() - w.getSeconds())));
                    m.put("updatedAt", w.getUpdatedAt());
                    return m;
                }).collect(Collectors.toList());
    }

    /** Where it came from, worked out once and kept, so the row can name it. */
    private void describe(WatchPosition w) {
        /* indexed lookups on videoRef (topics, then recordings), never a collection scan */
        topics.findFirstByVideoRef(w.getVideoRef())
                .ifPresent(t -> {
                    w.setTitle(t.getTitle());
                    w.setTopicId(t.getId());
                    w.setChapterId(t.getChapterId());
                    w.setKind("TOPIC");
                });
        if (w.getTitle() == null) {
            recordings.findFirstByVideoRef(w.getVideoRef())
                    .ifPresent(r -> {
                        w.setTitle(r.getTitle());
                        w.setKind("RECORDING");
                    });
        }
        if (w.getTitle() == null) {
            w.setTitle(videos.findById(w.getVideoRef()).map(Video::getTitle).orElse("Video"));
            w.setKind("GUIDE");
        }
    }

    /** The playhead as a percent, which is what a resume bar shows. */
    private int positionPercent(WatchPosition w) {
        if (w.getDurationSec() <= 0) return w.getPercent();
        return Math.min(100, (int) Math.round(w.getSeconds() * 100.0 / w.getDurationSec()));
    }

    private String clock(int seconds) {
        int h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }
}

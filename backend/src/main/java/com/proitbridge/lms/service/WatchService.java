package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
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

    public WatchService(WatchPositionRepository positions, VideoRepository videos,
                        TopicRepository topics, RecordingRepository recordings) {
        this.positions = positions; this.videos = videos;
        this.topics = topics; this.recordings = recordings;
    }

    /** Called every fifteen seconds while playing, and once on close. */
    public Map<String, Object> save(String learnerId, String videoRef, int seconds, int durationSec) {
        if (videoRef == null || durationSec <= 0) return Map.of("saved", false);

        int percent = Math.min(100, (int) Math.round(seconds * 100.0 / durationSec));
        WatchPosition w = positions.findByLearnerIdAndVideoRef(learnerId, videoRef)
                .orElseGet(WatchPosition::new);
        w.setLearnerId(learnerId);
        w.setVideoRef(videoRef);
        w.setSeconds(seconds);
        w.setDurationSec(durationSec);
        w.setPercent(percent);
        w.setCompleted(percent >= COMPLETE_PERCENT);
        w.setUpdatedAt(Instant.now());

        if (w.getTitle() == null) describe(w);
        positions.save(w);
        return Map.of("saved", true, "percent", percent);
    }

    /** What the player asks for before it starts, so it knows whether to offer a resume. */
    public Map<String, Object> resumeFor(String learnerId, String videoRef) {
        return positions.findByLearnerIdAndVideoRef(learnerId, videoRef)
                .filter(w -> w.getSeconds() >= MIN_SECONDS)
                .filter(w -> !w.isCompleted())
                .map(w -> Map.<String, Object>of(
                        "resume", true,
                        "seconds", w.getSeconds(),
                        "percent", w.getPercent(),
                        "label", clock(w.getSeconds())))
                .orElse(Map.of("resume", false));
    }

    /** Progress on every video in a list, for the bar under each material. */
    public Map<String, Integer> percentsFor(String learnerId, Collection<String> refs) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String ref : refs) {
            positions.findByLearnerIdAndVideoRef(learnerId, ref)
                    .ifPresent(w -> out.put(ref, w.getPercent()));
        }
        return out;
    }

    /**
     * The continue watching row: started, not finished, most recent first. Across
     * topics and session recordings both, because a half watched recap is exactly
     * the thing somebody means to come back to.
     */
    public List<Map<String, Object>> continueWatching(String learnerId, int limit) {
        return positions.findByLearnerIdOrderByUpdatedAtDesc(learnerId).stream()
                .filter(w -> !w.isCompleted())
                .filter(w -> w.getSeconds() >= MIN_SECONDS)
                .limit(limit)
                .map(w -> {
                    if (w.getTitle() == null) describe(w);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("videoRef", w.getVideoRef());
                    m.put("title", w.getTitle());
                    m.put("kind", w.getKind());
                    m.put("topicId", w.getTopicId());
                    m.put("chapterId", w.getChapterId());
                    m.put("percent", w.getPercent());
                    m.put("seconds", w.getSeconds());
                    m.put("label", clock(w.getSeconds()));
                    m.put("left", clock(Math.max(0, w.getDurationSec() - w.getSeconds())));
                    m.put("updatedAt", w.getUpdatedAt());
                    return m;
                }).collect(Collectors.toList());
    }

    /** Where it came from, worked out once and kept, so the row can name it. */
    private void describe(WatchPosition w) {
        topics.findAll().stream()
                .filter(t -> w.getVideoRef().equals(t.getVideoRef()))
                .findFirst()
                .ifPresent(t -> {
                    w.setTitle(t.getTitle());
                    w.setTopicId(t.getId());
                    w.setChapterId(t.getChapterId());
                    w.setKind("TOPIC");
                });
        if (w.getTitle() == null) {
            recordings.findAll().stream()
                    .filter(r -> w.getVideoRef().equals(r.getVideoRef()))
                    .findFirst()
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

    private String clock(int seconds) {
        int h = seconds / 3600, m = (seconds % 3600) / 60, s = seconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }
}

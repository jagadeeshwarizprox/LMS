package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import com.proitbridge.lms.service.video.VideoRefs;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Materials on a topic.
 *
 * Two rules run through this file. A resource carries exactly one payload, decided by
 * its kind, and anything else is refused rather than half saved: a dataset holding a
 * video reference is a bug that reaches a learner as an empty download. And the single
 * `videoRef` every topic used to carry is folded in as the first resource the moment
 * one is read, so nothing written before this existed is stranded.
 */
@Service
public class ResourceService {

    private static final Set<String> KINDS =
            Set.of("VIDEO", "NOTES", "CODE", "DATASET", "SLIDES", "LINK", "TEXT");

    /** What each kind must carry, and must not. */
    private static final Map<String, String> PAYLOAD = Map.of(
            "VIDEO", "video", "NOTES", "file", "CODE", "file", "DATASET", "file",
            "SLIDES", "file", "LINK", "url", "TEXT", "body");

    private final ResourceRepository resources;
    private final TopicRepository topics;
    private final VideoRepository videos;
    private final StoredFileRepository files;
    private final ActivityService activity;

    public ResourceService(ResourceRepository resources, TopicRepository topics,
                           VideoRepository videos, StoredFileRepository files,
                           ActivityService activity) {
        this.resources = resources; this.topics = topics; this.videos = videos;
        this.files = files; this.activity = activity;
    }

    /* ---------------------------------------------------------------- reading */

    /**
     * Everything on a topic, with the legacy single video folded in first.
     * The fold happens once and is written back, so it is a migration that needs
     * nobody to run it.
     */
    public List<Resource> forTopic(String topicId) {
        List<Resource> list = resources.findByTopicIdOrderByPositionAsc(topicId);
        Topic c = topics.findById(topicId).orElse(null);

        if (c != null && c.getVideoRef() != null && !c.getVideoRef().isBlank()
                && list.stream().noneMatch(r -> c.getVideoRef().equals(r.getVideoRef()))) {
            Resource r = new Resource();
            r.setTopicId(topicId);
            r.setKind("VIDEO");
            r.setTitle(c.getTitle());
            r.setVideoRef(c.getVideoRef());
            r.setPosition(0);
            r.setRequired(true);
            resources.save(r);
            list = resources.findByTopicIdOrderByPositionAsc(topicId);
        }
        return list;
    }

    /** What a learner sees: no file keys, no video ids, only refs and labels. */
    public List<Map<String, Object>> viewFor(String topicId, String track, boolean sessionHeld) {
        return forTopic(topicId).stream()
                .filter(Resource::isActive)
                .filter(r -> "BOTH".equals(r.getTrackScope()) || r.getTrackScope().equals(track))
                // material held back until after the session stays held back
                .filter(r -> r.isBeforeSession() || sessionHeld)
                .map(r -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", r.getId());
                    m.put("kind", r.getKind());
                    m.put("title", r.getTitle());
                    m.put("note", r.getNote());
                    m.put("required", r.isRequired());
                    m.put("downloadable", r.isDownloadable());
                    if ("VIDEO".equals(r.getKind())) {
                        m.put("videoRef", r.getVideoRef());
                    } else if ("LINK".equals(r.getKind())) {
                        m.put("url", r.getUrl());
                    } else if ("TEXT".equals(r.getKind())) {
                        m.put("body", r.getBody());
                    } else if (r.getFileId() != null) {
                        files.findById(r.getFileId()).ifPresent(f -> {
                            m.put("fileId", f.getId());
                            m.put("filename", f.getFilename());
                            m.put("sizeBytes", f.getSizeBytes());
                        });
                    }
                    return m;
                }).collect(Collectors.toList());
    }

    /** Builder view: counts, and a masked video id rather than the id itself. */
    public List<Map<String, Object>> adminView(String topicId) {
        return forTopic(topicId).stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("kind", r.getKind());
            m.put("title", r.getTitle());
            m.put("note", r.getNote());
            m.put("position", r.getPosition());
            m.put("required", r.isRequired());
            m.put("beforeSession", r.isBeforeSession());
            m.put("downloadable", r.isDownloadable());
            m.put("trackScope", r.getTrackScope());
            m.put("active", r.isActive());
            m.put("url", r.getUrl());
            m.put("body", r.getBody());
            if (r.getVideoRef() != null) {
                videos.findById(r.getVideoRef()).ifPresent(v -> {
                    m.put("videoMasked", mask(v.getExternalId()));
                    m.put("videoProvider", v.getProvider());
                });
            }
            if (r.getFileId() != null) {
                files.findById(r.getFileId()).ifPresent(f -> {
                    m.put("fileId", f.getId());
                    m.put("filename", f.getFilename());
                    m.put("sizeBytes", f.getSizeBytes());
                });
            }
            return m;
        }).collect(Collectors.toList());
    }

    /* ---------------------------------------------------------------- writing */

    public record ResourceInput(String id, String topicId, String kind, String title,
                                String note, String videoLink, String videoProvider,
                                String fileId, String url, String body,
                                Boolean required, Boolean beforeSession,
                                Boolean downloadable, String trackScope) {}

    public Resource save(ResourceInput in, String actorEmail) {
        if (in.topicId() == null || topics.findById(in.topicId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Which topic is this material for?");
        }
        String kind = in.kind() == null ? "NOTES" : in.kind().toUpperCase();
        if (!KINDS.contains(kind)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That is not a kind of material we handle.");
        }

        Resource r = in.id() == null ? new Resource()
                : resources.findById(in.id()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "No such material."));
        boolean isNew = r.getId() == null;

        r.setTopicId(in.topicId());
        r.setKind(kind);
        r.setNote(in.note());
        if (in.required() != null) r.setRequired(in.required());
        if (in.beforeSession() != null) r.setBeforeSession(in.beforeSession());
        if (in.downloadable() != null) r.setDownloadable(in.downloadable());
        if (in.trackScope() != null) r.setTrackScope(in.trackScope());
        if (isNew) r.setPosition(resources.findByTopicIdOrderByPositionAsc(in.topicId()).size());

        /* one payload, matching the kind. Clearing the others is what stops a resource
           that was a video yesterday from still answering as one today. */
        switch (PAYLOAD.get(kind)) {
            case "video" -> {
                if (in.videoLink() != null && !in.videoLink().isBlank()) {
                    r.setVideoRef(attachVideo(r.getVideoRef(), in.title(), in.videoLink(),
                            in.videoProvider(), actorEmail));
                } else if (r.getVideoRef() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Paste the video link.");
                }
                r.setFileId(null); r.setUrl(null); r.setBody(null);
            }
            case "file" -> {
                if (in.fileId() != null && !in.fileId().isBlank()) r.setFileId(in.fileId());
                if (r.getFileId() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Attach the file first.");
                }
                r.setVideoRef(null); r.setUrl(null); r.setBody(null);
            }
            case "url" -> {
                if (in.url() == null || in.url().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the link.");
                }
                r.setUrl(in.url().trim());
                r.setVideoRef(null); r.setFileId(null); r.setBody(null);
            }
            case "body" -> {
                if (in.body() == null || in.body().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Write something.");
                }
                r.setBody(in.body());
                r.setVideoRef(null); r.setFileId(null); r.setUrl(null);
            }
            default -> { }
        }

        /* a filename is a better default title than nothing, and most of the time it is
           the title the teacher would have typed anyway */
        String title = in.title();
        if ((title == null || title.isBlank()) && r.getFileId() != null) {
            title = files.findById(r.getFileId()).map(StoredFile::getFilename).orElse(null);
        }
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give it a title.");
        }
        r.setTitle(title);

        Resource saved = resources.save(r);
        activity.log(null, actorEmail, isNew ? "ADD_RESOURCE" : "UPDATE_RESOURCE",
                "topic", kind + ": " + saved.getTitle());
        return saved;
    }

    private String attachVideo(String existingRef, String title, String link,
                               String provider, String actorEmail) {
        String id = VideoRefs.parse(link, provider);
        Video v = existingRef == null ? new Video()
                : videos.findById(existingRef).orElse(new Video());
        boolean rotating = v.getId() != null && !id.equals(v.getExternalId());
        v.setTitle(title == null ? "Material" : title);
        v.setKind("CHAPTER");
        v.setExternalId(id);
        v.setProvider(provider == null || provider.isBlank() ? "YOUTUBE" : provider);
        v.setActive(true);
        v = videos.save(v);
        if (rotating) activity.log(null, actorEmail, "ROTATE_VIDEO", "video", v.getTitle());
        return v.getId();
    }

    public void delete(String id, String actorEmail) {
        Resource r = resources.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such material."));
        /* the topic's own videoRef is the same video, and leaving it would make the
           fold-in put the resource straight back */
        if (r.getVideoRef() != null) {
            topics.findById(r.getTopicId()).ifPresent(c -> {
                if (r.getVideoRef().equals(c.getVideoRef())) {
                    c.setVideoRef(null);
                    topics.save(c);
                }
            });
        }
        resources.delete(r);
        activity.log(null, actorEmail, "DELETE_RESOURCE", "topic", r.getTitle());
    }

    public List<Resource> reorder(String topicId, List<String> orderedIds, String actorEmail) {
        int i = 0;
        for (String id : orderedIds) {
            Resource r = resources.findById(id).orElse(null);
            if (r == null || !topicId.equals(r.getTopicId())) continue;
            r.setPosition(i++);
            resources.save(r);
        }
        activity.log(null, actorEmail, "REORDER_RESOURCES", "topic", topicId);
        return resources.findByTopicIdOrderByPositionAsc(topicId);
    }

    /** Several files at once: each becomes a resource, named after the file. */
    public Map<String, Object> addFiles(String topicId, List<Map<String, String>> uploaded,
                                        String kind, String actorEmail) {
        int n = 0;
        for (Map<String, String> f : uploaded) {
            save(new ResourceInput(null, topicId, kind, f.get("filename"), null,
                    null, null, f.get("id"), null, null, true, true, true, "BOTH"), actorEmail);
            n++;
        }
        return Map.of("added", n);
    }

    /** Everything on a topic, removed with it. Only reached when nobody has watched it. */
    public void deleteAllFor(String topicId, String actorEmail) {
        for (Resource r : resources.findByTopicIdOrderByPositionAsc(topicId)) {
            resources.delete(r);
        }
        activity.log(null, actorEmail, "DELETE_RESOURCES", "topic", topicId);
    }

    public long countFor(String topicId) {
        long stored = resources.countByTopicId(topicId);
        if (stored > 0) return stored;
        return topics.findById(topicId)
                .map(c -> c.getVideoRef() == null || c.getVideoRef().isBlank() ? 0L : 1L)
                .orElse(0L);
    }

    private String mask(String id) {
        if (id == null || id.length() < 5) return "not set";
        return id.substring(0, 2) + "\u2026" + id.substring(id.length() - 2);
    }
}

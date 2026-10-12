package com.proitbridge.lms.web;

import com.proitbridge.lms.domain.User;
import com.proitbridge.lms.domain.Video;
import com.proitbridge.lms.domain.VideoAccessLog;
import com.proitbridge.lms.repo.UserRepository;
import com.proitbridge.lms.repo.VideoRepository;
import com.proitbridge.lms.security.CurrentUser;
import com.proitbridge.lms.service.AdminQueries;
import com.proitbridge.lms.service.AnomalyService;
import com.proitbridge.lms.service.LoginGuardService;
import com.proitbridge.lms.service.SessionService;
import com.proitbridge.lms.service.VideoAccessService;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Access and sharing: the video library, the flags, the devices, the kill switch. */
@RestController
@RequestMapping("/api/admin/access")
public class AccessController {

    private final VideoAccessService videos;
    private final AnomalyService anomalies;
    private final SessionService sessions;
    private final LoginGuardService guard;
    private final CurrentUser current;
    private final UserRepository users;
    private final VideoRepository videoRepo;
    private final MongoTemplate mongo;

    public AccessController(VideoAccessService videos, AnomalyService anomalies,
                            SessionService sessions, LoginGuardService guard,
                            CurrentUser current, UserRepository users, VideoRepository videoRepo,
                            MongoTemplate mongo) {
        this.mongo = mongo;
        this.videos = videos; this.anomalies = anomalies;
        this.sessions = sessions; this.guard = guard; this.current = current;
        this.users = users; this.videoRepo = videoRepo;
    }

    private String actor() { return current.get().email(); }

    @GetMapping("/videos")
    public List<Map<String, Object>> library() { return videos.library(); }

    @PostMapping("/videos")
    public Video saveVideo(@RequestBody Video body) { return videos.save(body, actor()); }

    /** The fix for a leaked link: new upload, new id, every reference follows. */
    @PostMapping("/videos/{id}/rotate")
    public Video rotate(@PathVariable String id, @RequestBody Map<String, String> body) {
        return videos.rotate(id, body.get("externalId"), actor());
    }

    /**
     * The playback log, with a name and a title on every row. The raw rows carry only
     * ids, and a screen full of ids tells the office nothing about who shared what.
     */
    @GetMapping("/grants")
    public Map<String, Object> grants(@RequestParam(required = false) String userId,
                                      @RequestParam(required = false) String outcome,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size) {
        /*
         * A page at a time, newest first, off the (issuedAt) index, or (userId, issuedAt)
         * when narrowed to one account. This was the latest two hundred grants, which at
         * a thousand people watching is the last few minutes: anything worth looking into
         * had scrolled off before anybody opened the screen.
         */
        int p = AdminQueries.page(page);
        int n = AdminQueries.size(size);
        List<Criteria> parts = new ArrayList<>();
        if (userId != null && !userId.isBlank()) parts.add(Criteria.where("userId").is(userId));
        if (outcome != null && !outcome.isBlank()) parts.add(Criteria.where("outcome").is(outcome.trim()));
        Criteria where = AdminQueries.all(parts);
        long total = mongo.count(new Query(where), VideoAccessLog.class);
        List<VideoAccessLog> rows = mongo.find(new Query(where)
                .with(Sort.by(Sort.Direction.DESC, "issuedAt"))
                .skip((long) p * n).limit(n), VideoAccessLog.class);

        Set<String> userIds = rows.stream().map(VideoAccessLog::getUserId)
                .filter(x -> x != null).collect(Collectors.toSet());
        Set<String> videoIds = rows.stream().map(VideoAccessLog::getVideoId)
                .filter(x -> x != null).collect(Collectors.toSet());
        Map<String, User> who = new HashMap<>();
        users.findAllById(userIds).forEach(u -> who.put(u.getId(), u));
        Map<String, String> title = new HashMap<>();
        videoRepo.findAllById(videoIds).forEach(v -> title.put(v.getId(), v.getTitle()));
        List<Map<String, Object>> out = rows.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            User u = r.getUserId() == null ? null : who.get(r.getUserId());
            m.put("id", r.getId());
            m.put("userId", r.getUserId());
            m.put("name", u == null ? null : u.getFullName());
            m.put("email", u == null ? null : u.getEmail());
            m.put("video", title.getOrDefault(r.getVideoId(), null));
            m.put("issuedAt", r.getIssuedAt());
            m.put("outcome", r.getOutcome());
            m.put("reason", r.getReason());
            m.put("ip", r.getIp());
            m.put("deviceId", r.getDeviceId());
            return m;
        }).toList();
        return AdminQueries.pageOf(out, total, p, n);
    }

    /** Accounts currently shut out by the rate limit, and the way to clear one. */
    @GetMapping("/lockouts")
    public List<Map<String, Object>> lockouts() { return guard.locked(); }

    @PostMapping("/lockouts/unlock")
    public Map<String, Object> unlock(@RequestBody Map<String, String> body) {
        guard.unlock(body.get("email"));
        return Map.of("unlocked", true);
    }

    @GetMapping("/flags")
    public List<Map<String, Object>> flags() { return anomalies.open(); }

    @PostMapping("/flags/{id}/clear")
    public Map<String, Object> clearFlag(@PathVariable String id) {
        anomalies.clear(id, actor());
        return Map.of("cleared", true);
    }

    @PostMapping("/sweep")
    public Map<String, Object> sweep() { return Map.of("raised", anomalies.sweep()); }

    @GetMapping("/users/{userId}/devices")
    public List<?> devices(@PathVariable String userId) { return sessions.devicesFor(userId); }

    @PostMapping("/devices/{rowId}/release")
    public Map<String, Object> release(@PathVariable String rowId) {
        sessions.releaseDevice(rowId, actor());
        return Map.of("released", true);
    }

    @PostMapping("/users/{userId}/suspend")
    public Map<String, Object> suspend(@PathVariable String userId, @RequestBody Map<String, Object> body) {
        boolean on = !Boolean.FALSE.equals(body.get("suspended"));
        User target = users.findById(userId).orElseThrow(() ->
                new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "No such account."));
        /* the kill switch is for learners and mentors. An admin cannot reach another
           admin, and nobody suspends a super admin: that is how the product gets locked */
        boolean callerSuper = current.get().isSuperAdmin();
        if (on && target.getRole() == User.Role.SUPER_ADMIN) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "A super admin account cannot be suspended.");
        }
        if (!callerSuper && (target.getRole() == User.Role.ADMIN || target.getRole() == User.Role.SUPER_ADMIN)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "An admin account is managed by the super admin.");
        }
        sessions.suspend(userId, on, (String) body.get("reason"), actor());
        return Map.of("suspended", on);
    }
}

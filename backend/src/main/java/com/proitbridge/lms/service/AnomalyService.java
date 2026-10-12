package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.AccessFlag;
import com.proitbridge.lms.domain.ActiveSession;
import com.proitbridge.lms.domain.DeviceRegistration;
import com.proitbridge.lms.domain.User;
import com.proitbridge.lms.domain.VideoAccessLog;
import com.proitbridge.lms.repo.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Sharing shows up as a shape in the logs long before anyone reports it: one
 * account playing from several networks, or minting far more grants than one
 * person could watch. This raises those for a human, and never acts on its own.
 */
@Service
public class AnomalyService {

    private final VideoAccessLogRepository logs;
    private final ActiveSessionRepository sessions;
    private final DeviceRegistrationRepository devices;
    private final UserRepository users;
    private final AccessFlagRepository flags;
    private final MongoTemplate mongo;

    public AnomalyService(VideoAccessLogRepository logs, ActiveSessionRepository sessions,
                          DeviceRegistrationRepository devices, UserRepository users,
                          AccessFlagRepository flags, MongoTemplate mongo) {
        this.mongo = mongo;
        this.logs = logs; this.sessions = sessions; this.devices = devices;
        this.users = users; this.flags = flags;
    }

    @Scheduled(cron = "0 30 2 * * *")
    public void nightly() { sweep(); }

    /**
     * One day of playback, read by time window, a chunk of accounts at a time.
     *
     * The sweep used to start from every learner account and make four reads for each,
     * eighty thousand queries a night at twenty thousand learners, nearly all of them for
     * people who had not played anything. Only accounts with a grant in the window can
     * trip any of the four rules (the old loop skipped everyone else on its first read),
     * so it now starts from the window itself: the distinct user ids in the last 24
     * hours, off the (issuedAt) index. Those are handled 500 at a time, and each chunk
     * costs a fixed handful of reads: the accounts, their grants in the window (off
     * (userId, issuedAt)), their devices, their replaced sessions and their open flags.
     */
    public int sweep() {
        Instant since = Instant.now().minus(1, ChronoUnit.DAYS);
        List<String> active = mongo.findDistinct(
                new Query(Criteria.where("issuedAt").gt(since)), "userId",
                VideoAccessLog.class, String.class);
        int[] raised = {0};
        AdminQueries.inChunks(active.stream().filter(Objects::nonNull).toList(),
                ids -> raised[0] += sweepChunk(ids, since));
        return raised[0];
    }

    private int sweepChunk(List<String> ids, Instant since) {
        Map<String, User> who = new HashMap<>();
        users.findAllById(ids).forEach(u -> {
            if (u.getRole() == User.Role.LEARNER) who.put(u.getId(), u);
        });
        if (who.isEmpty()) return 0;
        Set<String> learnerIds = who.keySet();

        Query grantsQ = new Query(Criteria.where("userId").in(learnerIds).and("issuedAt").gt(since));
        grantsQ.fields().include("userId", "ip");
        Map<String, List<VideoAccessLog>> grants = mongo.find(grantsQ, VideoAccessLog.class).stream()
                .collect(Collectors.groupingBy(VideoAccessLog::getUserId));

        Query devQ = new Query(Criteria.where("userId").in(learnerIds));
        devQ.fields().include("userId");
        Map<String, Long> deviceCount = mongo.find(devQ, DeviceRegistration.class).stream()
                .collect(Collectors.groupingBy(DeviceRegistration::getUserId, Collectors.counting()));

        Query replacedQ = new Query(Criteria.where("userId").in(learnerIds)
                .and("endedReason").is("REPLACED").and("startedAt").gt(since));
        replacedQ.fields().include("userId");
        Map<String, Long> replaced = mongo.find(replacedQ, ActiveSession.class).stream()
                .collect(Collectors.groupingBy(ActiveSession::getUserId, Collectors.counting()));

        /* flags already open for these accounts, so raise() does not ask per account */
        Set<String> open = new HashSet<>();
        mongo.find(new Query(Criteria.where("userId").in(learnerIds).and("cleared").is(false)),
                AccessFlag.class).forEach(f -> open.add(f.getUserId() + "|" + f.getKind()));

        int raised = 0;
        for (User u : who.values()) {
            List<VideoAccessLog> recent = grants.getOrDefault(u.getId(), List.of());
            if (recent.isEmpty()) continue;

            Set<String> ips = recent.stream().map(l -> l.getIp())
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            if (ips.size() >= 4) {
                raised += raise(u, "MANY_IPS", ips.size() + " networks in 24 hours", open);
            }
            if (recent.size() >= 60) {
                raised += raise(u, "TOKEN_BURST",
                        recent.size() + " playback grants in 24 hours, more than one person watches", open);
            }
            long devicesSeen = deviceCount.getOrDefault(u.getId(), 0L);
            if (devicesSeen >= 4) {
                raised += raise(u, "DEVICE_CHURN", devicesSeen + " devices seen on this account", open);
            }
            long pushedOut = replaced.getOrDefault(u.getId(), 0L);
            if (pushedOut >= 6) {
                raised += raise(u, "IMPOSSIBLE_TRAVEL",
                        pushedOut + " sign ins pushed each other out in 24 hours, which is what "
                                + "two people using one account looks like", open);
            }
        }
        return raised;
    }

    private int raise(User u, String kind, String detail, Set<String> open) {
        if (!open.add(u.getId() + "|" + kind)) return 0;
        AccessFlag f = new AccessFlag();
        f.setUserId(u.getId());
        f.setUserName(u.getFullName());
        f.setKind(kind);
        f.setDetail(detail);
        flags.save(f);
        return 1;
    }

    public List<Map<String, Object>> open() {
        List<AccessFlag> rows = flags.findByClearedFalseOrderByCreatedAtDesc();
        /* the suspended state for every flagged account in one read */
        Map<String, Boolean> suspended = new HashMap<>();
        users.findAllById(rows.stream().map(AccessFlag::getUserId).filter(Objects::nonNull)
                .collect(Collectors.toSet())).forEach(u -> suspended.put(u.getId(), u.isSuspended()));
        return rows.stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.getId());
            m.put("userId", f.getUserId());
            m.put("name", f.getUserName());
            m.put("kind", f.getKind());
            m.put("detail", f.getDetail());
            m.put("createdAt", f.getCreatedAt());
            m.put("suspended", suspended.getOrDefault(f.getUserId(), false));
            return m;
        }).collect(Collectors.toList());
    }

    public void clear(String flagId, String actorEmail) {
        flags.findById(flagId).ifPresent(f -> {
            f.setCleared(true);
            f.setClearedBy(actorEmail);
            flags.save(f);
        });
    }
}

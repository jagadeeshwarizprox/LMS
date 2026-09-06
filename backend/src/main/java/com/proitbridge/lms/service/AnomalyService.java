package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.AccessFlag;
import com.proitbridge.lms.domain.User;
import com.proitbridge.lms.repo.*;
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

    public AnomalyService(VideoAccessLogRepository logs, ActiveSessionRepository sessions,
                          DeviceRegistrationRepository devices, UserRepository users,
                          AccessFlagRepository flags) {
        this.logs = logs; this.sessions = sessions; this.devices = devices;
        this.users = users; this.flags = flags;
    }

    @Scheduled(cron = "0 30 2 * * *")
    public void nightly() { sweep(); }

    public int sweep() {
        Instant since = Instant.now().minus(1, ChronoUnit.DAYS);
        int raised = 0;
        for (User u : users.findByRole(User.Role.LEARNER)) {
            var recent = logs.findByUserIdAndIssuedAtAfter(u.getId(), since);
            if (recent.isEmpty()) continue;

            Set<String> ips = recent.stream().map(l -> l.getIp())
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            if (ips.size() >= 4) {
                raised += raise(u, "MANY_IPS", ips.size() + " networks in 24 hours");
            }
            if (recent.size() >= 60) {
                raised += raise(u, "TOKEN_BURST",
                        recent.size() + " playback grants in 24 hours, more than one person watches");
            }
            long deviceCount = devices.findByUserId(u.getId()).size();
            if (deviceCount >= 4) {
                raised += raise(u, "DEVICE_CHURN", deviceCount + " devices seen on this account");
            }
            long replaced = sessions.findByUserId(u.getId()).stream()
                    .filter(s -> "REPLACED".equals(s.getEndedReason()))
                    .filter(s -> s.getStartedAt().isAfter(since))
                    .count();
            if (replaced >= 6) {
                raised += raise(u, "IMPOSSIBLE_TRAVEL",
                        replaced + " sign ins pushed each other out in 24 hours, which is what "
                                + "two people using one account looks like");
            }
        }
        return raised;
    }

    private int raise(User u, String kind, String detail) {
        if (!flags.findByUserIdAndKindAndClearedFalse(u.getId(), kind).isEmpty()) return 0;
        AccessFlag f = new AccessFlag();
        f.setUserId(u.getId());
        f.setUserName(u.getFullName());
        f.setKind(kind);
        f.setDetail(detail);
        flags.save(f);
        return 1;
    }

    public List<Map<String, Object>> open() {
        return flags.findByClearedFalseOrderByCreatedAtDesc().stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", f.getId());
            m.put("userId", f.getUserId());
            m.put("name", f.getUserName());
            m.put("kind", f.getKind());
            m.put("detail", f.getDetail());
            m.put("createdAt", f.getCreatedAt());
            m.put("suspended", users.findById(f.getUserId()).map(User::isSuspended).orElse(false));
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

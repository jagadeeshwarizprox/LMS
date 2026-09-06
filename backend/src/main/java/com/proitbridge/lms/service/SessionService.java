package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.ActiveSession;
import com.proitbridge.lms.domain.DeviceRegistration;
import com.proitbridge.lms.domain.User;
import com.proitbridge.lms.repo.ActiveSessionRepository;
import com.proitbridge.lms.repo.DeviceRegistrationRepository;
import com.proitbridge.lms.repo.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Two people cannot comfortably share one account here. Signing in ends the
 * previous session, and only a small number of devices are ever registered, so a
 * shared password means both parties keep knocking each other out.
 */
@Service
public class SessionService {

    private final ActiveSessionRepository sessions;
    private final DeviceRegistrationRepository devices;
    private final UserRepository users;
    private final ActivityService activity;
    private final int deviceLimit;
    private final int idleMinutes;

    public SessionService(ActiveSessionRepository sessions, DeviceRegistrationRepository devices,
                          UserRepository users, ActivityService activity,
                          @Value("${lms.access.device-limit}") int deviceLimit,
                          @Value("${lms.access.idle-minutes}") int idleMinutes) {
        this.sessions = sessions; this.devices = devices; this.users = users;
        this.activity = activity; this.deviceLimit = deviceLimit; this.idleMinutes = idleMinutes;
    }

    /** Called on a successful sign in, after the password has already been checked. */
    public ActiveSession open(User user, String deviceId, String label, String ip, String userAgent) {
        if (user.isSuspended()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This account is suspended. Contact your mentor.");
        }
        registerDevice(user, deviceId, label, ip);

        // one at a time: the previous session is ended, not merely ignored
        for (ActiveSession old : sessions.findByUserIdAndCurrentTrue(user.getId())) {
            old.setCurrent(false);
            old.setEndedReason("REPLACED");
            sessions.save(old);
        }

        ActiveSession s = new ActiveSession();
        s.setUserId(user.getId());
        s.setDeviceId(deviceId);
        s.setIp(ip);
        s.setUserAgent(userAgent);
        return sessions.save(s);
    }

    private void registerDevice(User user, String deviceId, String label, String ip) {
        if (deviceId == null || deviceId.isBlank()) return;
        var existing = devices.findByUserIdAndDeviceId(user.getId(), deviceId);
        if (existing.isPresent()) {
            DeviceRegistration d = existing.get();
            if (d.isRevoked()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "This device was removed from your account. Ask an admin to allow it again.");
            }
            d.setLastSeen(Instant.now());
            d.setLastIp(ip);
            devices.save(d);
            return;
        }
        List<DeviceRegistration> live = devices.findByUserIdAndRevokedFalse(user.getId());
        if (live.size() >= deviceLimit) {
            activity.log(user.getId(), user.getEmail(), "DEVICE_LIMIT_HIT", "user",
                    live.size() + " devices already registered");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You are signed in on " + live.size() + " devices already. "
                            + "Ask an admin to release one before adding another.");
        }
        DeviceRegistration d = new DeviceRegistration();
        d.setUserId(user.getId());
        d.setDeviceId(deviceId);
        d.setLabel(label);
        d.setLastIp(ip);
        devices.save(d);
    }

    /** Every authenticated request passes through here, so a dropped session dies at once. */
    public boolean isLive(String sessionId) {
        if (sessionId == null) return false;
        return sessions.findById(sessionId).map(s -> {
            if (!s.isCurrent()) return false;
            if (users.findById(s.getUserId()).map(User::isSuspended).orElse(true)) return false;
            if (ChronoUnit.MINUTES.between(s.getLastSeenAt(), Instant.now()) > idleMinutes) {
                s.setCurrent(false);
                s.setEndedReason("IDLE");
                sessions.save(s);
                return false;
            }
            s.setLastSeenAt(Instant.now());
            sessions.save(s);
            return true;
        }).orElse(false);
    }

    public void close(String sessionId, String reason) {
        sessions.findById(sessionId).ifPresent(s -> {
            s.setCurrent(false);
            s.setEndedReason(reason);
            sessions.save(s);
        });
    }

    public void closeAllFor(String userId, String reason) {
        for (ActiveSession s : sessions.findByUserIdAndCurrentTrue(userId)) {
            s.setCurrent(false);
            s.setEndedReason(reason);
            sessions.save(s);
        }
    }

    public List<DeviceRegistration> devicesFor(String userId) {
        return devices.findByUserId(userId);
    }

    public void releaseDevice(String deviceRowId, String actorEmail) {
        devices.findById(deviceRowId).ifPresent(d -> {
            d.setRevoked(true);
            devices.save(d);
            closeAllFor(d.getUserId(), "REPLACED");
            activity.log(null, actorEmail, "RELEASE_DEVICE", "user", d.getUserId());
        });
    }

    public void suspend(String userId, boolean on, String reason, String actorEmail) {
        User u = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such account."));
        u.setSuspended(on);
        u.setSuspendedReason(on ? reason : null);
        users.save(u);
        if (on) closeAllFor(userId, "SUSPENDED");
        activity.log(null, actorEmail, on ? "SUSPEND_ACCOUNT" : "RESTORE_ACCOUNT", "user",
                u.getEmail() + (reason == null ? "" : " (" + reason + ")"));
    }
}

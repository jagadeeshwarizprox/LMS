package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.ActiveSession;
import com.proitbridge.lms.domain.DeviceRegistration;
import com.proitbridge.lms.domain.User;
import com.proitbridge.lms.repo.ActiveSessionRepository;
import com.proitbridge.lms.repo.DeviceRegistrationRepository;
import com.proitbridge.lms.repo.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
    private final SettingsService settings;

    public SessionService(ActiveSessionRepository sessions, DeviceRegistrationRepository devices,
                          UserRepository users, ActivityService activity,
                          SettingsService settings) {
        this.sessions = sessions; this.devices = devices; this.users = users;
        this.activity = activity; this.settings = settings;
    }

    /*
     * The limits are read on every sign in rather than held in a field, so a change made
     * in Settings applies to the next attempt and nothing has to be restarted.
     *
     * Staff work from more places than a learner does: an office machine, a laptop, a
     * phone, a class they are taking from somewhere else. Learners get a smaller number
     * because the limit is there to stop one paid account being passed around.
     */
    private int limitFor(User user) {
        return user.getRole() == User.Role.LEARNER
                ? settings.getInt("access.deviceLimit.learner", 3)
                : settings.getInt("access.deviceLimit.staff", 10);
    }

    private int idleMinutes() { return settings.getInt("access.idleMinutes", 120); }

    /** A device nobody has signed in from for this long stops holding a slot. */
    private int forgetDays() { return settings.getInt("access.deviceForgetDays", 30); }

    /** Called on a successful sign in, after the password has already been checked. */
    public ActiveSession open(User user, String deviceId, String label, String ip, String userAgent) {
        if (user.isSuspended()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This account is suspended. Contact your mentor.");
        }
        registerDevice(user, deviceId, label, ip);

        /*
         * One session at a time, for learners.
         *
         * The rule exists to stop one paid account being passed around, and for a learner
         * that is exactly right. It was being applied to staff as well, and staff work
         * from more than one place at once: an office machine with the register open and a
         * laptop in a session is an ordinary Tuesday, not account sharing. Two people on
         * one super admin account knocked each other out every few minutes and it read as
         * the product logging them out at random.
         *
         * Staff now keep their sessions unless an admin turns this on, which is a real
         * choice for an organisation that would rather everyone had their own account and
         * wants the sharing to be obvious. The device limit still applies to everybody, so
         * an account cannot quietly spread across a dozen machines either way.
         */
        boolean oneAtATime = user.getRole() == User.Role.LEARNER
                || settings.getBool("access.singleSession.staff", false);
        if (oneAtATime) {
            for (ActiveSession old : sessions.findByUserIdAndCurrentTrue(user.getId())) {
                old.setCurrent(false);
                old.setEndedReason("REPLACED");
                sessions.save(old);
            }
        }

        ActiveSession s = new ActiveSession();
        s.setUserId(user.getId());
        s.setDeviceId(deviceId);
        s.setIp(ip);
        s.setUserAgent(userAgent);
        return sessions.save(s);
    }

    /**
     * A device this account has used before is always let back in.
     *
     * This is the rule the old version got wrong. The limit counted registration rows,
     * which nothing ever removed, so signing out did not give the slot back and the same
     * browser could be refused on its own second sign in. A known device now only has its
     * last seen stamp refreshed, and a device an admin released is simply re-admitted
     * rather than blacklisted, because releasing a slot is not a ban.
     *
     * The count is only ever consulted for a device that is genuinely new.
     */
    private void registerDevice(User user, String deviceId, String label, String ip) {
        if (deviceId == null || deviceId.isBlank()) return;
        var existing = devices.findByUserIdAndDeviceId(user.getId(), deviceId);
        if (existing.isPresent()) {
            DeviceRegistration d = existing.get();
            if (d.isRevoked()) {
                d.setRevoked(false);
                activity.log(user.getId(), user.getEmail(), "DEVICE_REJOINED", "user",
                        "a released device signed in again");
            }
            d.setLastSeen(Instant.now());
            d.setLastIp(ip);
            if (label != null && !label.isBlank()) d.setLabel(label);
            devices.save(d);
            return;
        }

        List<DeviceRegistration> live = new ArrayList<>(devices.findByUserIdAndRevokedFalse(user.getId()));

        /* a browser that cleared its storage, or one used once from a friend's machine,
           leaves a row behind that nobody will ever sign in from again. Those are dropped
           here so a slot is never held by a device that stopped existing */
        int forget = forgetDays();
        if (forget > 0) {
            Instant cutoff = Instant.now().minus(forget, ChronoUnit.DAYS);
            List<DeviceRegistration> stale = live.stream()
                    .filter(d -> d.getLastSeen() == null || d.getLastSeen().isBefore(cutoff))
                    .toList();
            if (!stale.isEmpty()) {
                devices.deleteAll(stale);
                live.removeAll(stale);
            }
        }

        int limit = limitFor(user);
        if (live.size() >= limit) {
            activity.log(user.getId(), user.getEmail(), "DEVICE_LIMIT_HIT", "user",
                    live.size() + " of " + limit + " devices already registered");
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This account is already set up on " + live.size()
                            + (limit == 1 ? " device" : " devices")
                            + ", which is the limit. Ask an admin to release one.");
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
            if (ChronoUnit.MINUTES.between(s.getLastSeenAt(), Instant.now()) > idleMinutes()) {
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

    /**
     * Releasing frees the slot and forgets the device. It is not a ban: if the learner
     * comes back on that same browser it registers again like any other, which is what
     * an admin means when they release one to unstick somebody.
     */
    public void releaseDevice(String deviceRowId, String actorEmail) {
        devices.findById(deviceRowId).ifPresent(d -> {
            for (ActiveSession s : sessions.findByUserIdAndCurrentTrue(d.getUserId())) {
                if (d.getDeviceId() != null && d.getDeviceId().equals(s.getDeviceId())) {
                    s.setCurrent(false);
                    s.setEndedReason("RELEASED");
                    sessions.save(s);
                }
            }
            devices.delete(d);
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

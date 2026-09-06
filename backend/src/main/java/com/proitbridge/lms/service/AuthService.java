package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.User;
import com.proitbridge.lms.repo.LearnerRepository;
import com.proitbridge.lms.repo.UserRepository;
import com.proitbridge.lms.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class AuthService {

    private final UserRepository users;
    private final LearnerRepository learners;
    private final PasswordEncoder encoder;
    private final JwtService jwt;
    private final ActivityService activity;
    private final SessionService sessions;
    private final LoginGuardService guard;
    private final SettingsService settings;
    private final CredentialService credentials;

    public AuthService(UserRepository users, LearnerRepository learners, PasswordEncoder encoder,
                       JwtService jwt, ActivityService activity, SessionService sessions,
                       LoginGuardService guard, SettingsService settings,
                       CredentialService credentials) {
        this.users = users; this.learners = learners; this.encoder = encoder;
        this.jwt = jwt; this.activity = activity; this.sessions = sessions; this.guard = guard;
        this.settings = settings; this.credentials = credentials;
    }

    public record Client(String deviceId, String deviceLabel, String ip, String userAgent) {}

    /** Which door they came through. */
    public enum Portal { LEARNER, STAFF }

    private static Portal portalFor(User.Role role) {
        return role == User.Role.LEARNER ? Portal.LEARNER : Portal.STAFF;
    }

    public Map<String, Object> login(String loginId, String password, Client client) {
        return login(loginId, password, client, null);
    }

    /**
     * Learners and staff sign in at different places.
     *
     * The portal is checked after the password, not before, so a wrong guess still gets
     * the same answer everywhere and nobody can map which addresses are staff. Someone
     * holding correct credentials already knows what kind of account they have, so
     * telling them which door to use costs nothing and saves a support message.
     */
    public Map<String, Object> login(String typed, String password, Client client, Portal portal) {
        /* before anything else: a locked account never reaches the password check, so a
           list attack costs the attacker time rather than the account */
        guard.check(typed, client.ip());
        final String id = typed == null ? "" : typed.trim();
        /* two ways in against one password: the derived handle, or the email. Which one
           somebody types depends on whether they were read it out or mailed it, and
           making them remember which is a support message we do not need. */
        User u = users.findByLoginIdIgnoreCase(id)
                .or(() -> users.findByEmailIgnoreCase(id))
                .orElseGet(() -> {
                    guard.record(id, client.ip(), false, "NO_SUCH_ACCOUNT");
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                            "That login ID and password do not match.");
                });
        if (!u.isActive()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is switched off.");
        }
        if (!encoder.matches(password == null ? "" : password, u.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "That login ID and password do not match.");
        }
        /* a derived first password is a one time key. Never used, it stops working, so a
           name shaped password on a dormant account is a window rather than a standing
           invitation. Somebody who has already changed theirs is unaffected. */
        if (u.isMustChangePassword() && u.getDefaultPasswordSetAt() != null) {
            long days = settings.getInt("auth.defaultPasswordDays", 14);
            if (days > 0
                    && u.getDefaultPasswordSetAt().plus(days, ChronoUnit.DAYS).isBefore(Instant.now())) {
                guard.record(id, client.ip(), false, "DEFAULT_EXPIRED");
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "This first password has expired. Ask your admin to reissue it.");
            }
        }
        if (u.isSuspended()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This account is suspended. Contact your mentor.");
        }
        if (portal != null && portalFor(u.getRole()) != portal) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    portal == Portal.STAFF
                        ? "That is a learner account. Sign in on the learner page instead."
                        : "That is a staff account. Sign in on the team page instead.");
        }
        u.setLastLoginAt(Instant.now());
        users.save(u);
        guard.record(id, client.ip(), true, null);
        var s = sessions.open(u, client.deviceId(), client.deviceLabel(), client.ip(), client.userAgent());
        activity.log(u.getId(), u.getEmail(), "LOGIN", "user", client.deviceLabel());
        return session(u, s.getId());
    }

    public void logout(String sessionId) {
        sessions.close(sessionId, "SIGNED_OUT");
    }

    /** Nothing else in the LMS opens until the generated password is replaced. */
    public Map<String, Object> changePassword(String userId, String sessionId, String current, String next) {
        User u = users.findById(userId).orElseThrow();
        if (!encoder.matches(current == null ? "" : current, u.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Your current password is wrong.");
        }
        if (next == null || next.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choose a password of at least 8 characters.");
        }
        /* refuse the one they were handed: keeping name@123 as the real password is the
           single way this scheme goes wrong, so it is rejected by name rather than left
           to the length rule, which it happens to pass */
        if (next.equalsIgnoreCase(credentials.firstPasswordFor(u.getFullName(), u.getLoginId()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That is the password you were given. Choose a different one.");
        }
        u.setPasswordHash(encoder.encode(next));
        u.setMustChangePassword(false);
        u.setDefaultPasswordSetAt(null);
        users.save(u);
        activity.log(u.getId(), u.getEmail(), "CHANGE_PASSWORD", "user", null);
        return session(u, sessionId);
    }

    public Map<String, Object> me(String userId, String sessionId) {
        return session(users.findById(userId).orElseThrow(), sessionId);
    }

    private Map<String, Object> session(User u, String sessionId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", jwt.issue(u, sessionId));
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("id", u.getId());
        user.put("name", u.getFullName());
        user.put("email", u.getEmail());
        user.put("loginId", u.getLoginId());
        user.put("role", u.getRole());
        user.put("mustChangePassword", u.isMustChangePassword());
        if (u.getRole() == User.Role.LEARNER) {
            learners.findByUserId(u.getId()).ifPresent(l -> {
                user.put("learnerId", l.getId());
                user.put("trackType", l.getTrackType());
                user.put("guideVideoWatched", l.isGuideVideoWatched());
                user.put("modulesUnlocked", l.modulesUnlocked());
            });
        }
        m.put("user", user);
        return m;
    }
}

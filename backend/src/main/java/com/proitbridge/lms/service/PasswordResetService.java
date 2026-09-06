package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.PasswordReset;
import com.proitbridge.lms.domain.User;
import com.proitbridge.lms.repo.PasswordResetRepository;
import com.proitbridge.lms.repo.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;

/**
 * Setting a new password without asking anyone.
 *
 * Three rules do most of the work here. The answer is identical whether the address
 * exists or not, so nobody can use this form to find out which accounts are real. The
 * token is stored hashed, because a table of live reset tokens is a list of account
 * takeovers for anyone who can read the database. And using one ends every session on
 * the account, since a reset usually means something has gone wrong.
 */
@Service
public class PasswordResetService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final PasswordResetRepository resets;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final SessionService sessions;
    private final MailService mail;
    private final ActivityService activity;
    private final String baseUrl;
    private final int ttlMinutes;
    private final int maxPerHour;

    public PasswordResetService(PasswordResetRepository resets, UserRepository users,
                                PasswordEncoder encoder, SessionService sessions,
                                MailService mail, ActivityService activity,
                                @Value("${lms.app.base-url}") String baseUrl,
                                @Value("${lms.auth.reset-ttl-minutes}") int ttlMinutes,
                                @Value("${lms.auth.max-resets-per-hour}") int maxPerHour) {
        this.resets = resets; this.users = users; this.encoder = encoder;
        this.sessions = sessions; this.mail = mail; this.activity = activity;
        this.baseUrl = baseUrl; this.ttlMinutes = ttlMinutes; this.maxPerHour = maxPerHour;
    }

    /**
     * Always the same answer. An unknown address, a suspended account and a successful
     * send are indistinguishable from outside, which is the entire point of the form.
     */
    public Map<String, Object> request(String email, String ip) {
        String said = "If that address has an account, a link is on its way. It lasts "
                + ttlMinutes + " minutes.";
        if (email == null || email.isBlank()) return Map.of("sent", true, "message", said);

        users.findByEmailIgnoreCase(email.trim()).ifPresent(u -> {
            if (u.isSuspended()) return;

            long recent = resets.findByUserIdAndCreatedAtAfter(
                    u.getId(), Instant.now().minus(1, ChronoUnit.HOURS)).size();
            if (recent >= maxPerHour) return;   // silently, so the count cannot be probed

            /* any earlier link stops working: two live tokens means two ways in */
            resets.findByUserIdAndUsedFalse(u.getId()).forEach(old -> {
                old.setUsed(true);
                old.setUsedAt(Instant.now());
                resets.save(old);
            });

            byte[] raw = new byte[32];
            RANDOM.nextBytes(raw);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

            PasswordReset r = new PasswordReset();
            r.setUserId(u.getId());
            r.setTokenHash(hash(token));
            r.setRequestedIp(ip);
            r.setExpiresAt(Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES));
            resets.save(r);

            String link = baseUrl + "/reset?token=" + token;
            mail.send(u.getEmail(), "Set a new password",
                    "Hello " + u.getFullName() + ",\n\n"
                    + "Use this link to set a new password. It works once and lasts "
                    + ttlMinutes + " minutes.\n\n" + link + "\n\n"
                    + "If you did not ask for this, ignore it. Your password has not changed.\n\n"
                    + "Team ProITBridge", "PASSWORD_RESET");
            activity.log(u.getId(), u.getEmail(), "REQUEST_PASSWORD_RESET", "user", ip);
        });

        return Map.of("sent", true, "message", said);
    }

    /** Checked before the form is shown, so nobody types a password into a dead link. */
    public Map<String, Object> check(String token) {
        return resets.findByTokenHash(hash(token))
                .filter(r -> !r.isUsed())
                .filter(r -> r.getExpiresAt().isAfter(Instant.now()))
                .map(r -> Map.<String, Object>of("valid", true,
                        "name", users.findById(r.getUserId()).map(User::getFullName).orElse("")))
                .orElse(Map.of("valid", false,
                        "message", "That link has expired or has already been used. Ask for a new one."));
    }

    public Map<String, Object> complete(String token, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Use at least eight characters.");
        }
        PasswordReset r = resets.findByTokenHash(hash(token))
                .filter(x -> !x.isUsed())
                .filter(x -> x.getExpiresAt().isAfter(Instant.now()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE,
                        "That link has expired or has already been used. Ask for a new one."));

        User u = users.findById(r.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "No such account."));

        u.setPasswordHash(encoder.encode(newPassword));
        u.setMustChangePassword(false);
        users.save(u);

        r.setUsed(true);
        r.setUsedAt(Instant.now());
        resets.save(r);

        /* whoever was signed in is signed out, on every device. If the reset happened
           because somebody else had the account, this is what removes them. */
        sessions.closeAllFor(u.getId(), "PASSWORD_RESET");

        mail.send(u.getEmail(), "Your password was changed",
                "Hello " + u.getFullName() + ",\n\n"
                + "Your password has just been changed and every device has been signed out.\n\n"
                + "If this was not you, tell your mentor straight away.\n\n"
                + "Team ProITBridge", "PASSWORD_CHANGED");
        activity.log(u.getId(), u.getEmail(), "COMPLETE_PASSWORD_RESET", "user", null);

        return Map.of("done", true,
                "message", "Password set. Sign in with the new one.");
    }

    private String hash(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(d);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }
}

package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.LoginAttempt;
import com.proitbridge.lms.repo.LoginAttemptRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Rate limiting on sign in.
 *
 * Two counters, because they stop different things. Per account stops somebody working
 * through a password list against one person. Per address stops one machine sweeping many
 * accounts, which the per account counter never sees because each individual account only
 * gets a few tries.
 *
 * A lockout says so plainly rather than pretending the password was wrong. Hiding it does
 * not slow an attacker down, who can measure it anyway, and it wastes the time of the
 * person who simply mistyped twice and is now confused.
 */
@Service
public class LoginGuardService {

    private final LoginAttemptRepository attempts;
    private final int perAccount;
    private final int lockMinutes;
    private final int perIp;
    private final int ipWindowMinutes;

    public LoginGuardService(LoginAttemptRepository attempts,
                             @Value("${lms.auth.max-per-account}") int perAccount,
                             @Value("${lms.auth.lock-minutes}") int lockMinutes,
                             @Value("${lms.auth.max-per-ip}") int perIp,
                             @Value("${lms.auth.ip-window-minutes}") int ipWindowMinutes) {
        this.attempts = attempts;
        this.perAccount = perAccount;
        this.lockMinutes = lockMinutes;
        this.perIp = perIp;
        this.ipWindowMinutes = ipWindowMinutes;
    }

    /** Called before the password is checked. Throws when the door is shut. */
    public void check(String email, String ip) {
        String key = email == null ? "" : email.trim().toLowerCase();

        Instant lockWindow = Instant.now().minus(lockMinutes, ChronoUnit.MINUTES);
        List<LoginAttempt> recent = attempts.findByEmailAndAtAfter(key, lockWindow);

        /* a success clears the slate, so five failures then a correct password then two
           more failures is two, not seven */
        long failuresSinceSuccess = sinceLastSuccess(recent);
        if (failuresSinceSuccess >= perAccount) {
            long wait = waitMinutes(recent);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Too many attempts on this account. Try again in " + wait
                    + (wait == 1 ? " minute" : " minutes") + ", or reset your password.");
        }

        if (ip != null && !ip.isBlank()) {
            Instant ipWindow = Instant.now().minus(ipWindowMinutes, ChronoUnit.MINUTES);
            long fromIp = attempts.findByIpAndAtAfter(ip, ipWindow).stream()
                    .filter(a -> !a.isSuccess()).count();
            if (fromIp >= perIp) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                        "Too many attempts from this connection. Wait a few minutes.");
            }
        }
    }

    public void record(String email, String ip, boolean success, String reason) {
        LoginAttempt a = new LoginAttempt();
        a.setEmail(email == null ? "" : email.trim().toLowerCase());
        a.setIp(ip);
        a.setSuccess(success);
        a.setReason(reason);
        attempts.save(a);
    }

    private long sinceLastSuccess(List<LoginAttempt> recent) {
        List<LoginAttempt> ordered = recent.stream()
                .sorted(Comparator.comparing(LoginAttempt::getAt).reversed()).toList();
        long n = 0;
        for (LoginAttempt a : ordered) {
            if (a.isSuccess()) break;
            n++;
        }
        return n;
    }

    private long waitMinutes(List<LoginAttempt> recent) {
        Instant last = recent.stream().filter(a -> !a.isSuccess())
                .map(LoginAttempt::getAt).max(Instant::compareTo).orElse(Instant.now());
        long mins = lockMinutes - ChronoUnit.MINUTES.between(last, Instant.now());
        return Math.max(1, mins);
    }

    /** What an admin sees: accounts currently shut out, and who to unlock. */
    public List<Map<String, Object>> locked() {
        Instant window = Instant.now().minus(lockMinutes, ChronoUnit.MINUTES);
        return attempts.findByAtAfter(window).stream()
                .filter(a -> !a.isSuccess())
                .collect(Collectors.groupingBy(LoginAttempt::getEmail))
                .entrySet().stream()
                .filter(e -> sinceLastSuccess(e.getValue()) >= perAccount)
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("email", e.getKey());
                    m.put("failures", e.getValue().size());
                    m.put("lastAt", e.getValue().stream().map(LoginAttempt::getAt)
                            .max(Instant::compareTo).orElse(null));
                    m.put("ips", e.getValue().stream().map(LoginAttempt::getIp)
                            .filter(Objects::nonNull).distinct().toList());
                    m.put("unlocksIn", waitMinutes(e.getValue()));
                    return m;
                }).collect(Collectors.toList());
    }

    /** Clearing a lockout is deleting the failures, so the count starts again at zero. */
    public void unlock(String email) {
        String key = email.trim().toLowerCase();
        attempts.findByEmailAndAtAfter(key, Instant.now().minus(lockMinutes, ChronoUnit.MINUTES))
                .stream().filter(a -> !a.isSuccess())
                .forEach(attempts::delete);
    }

    /** Attempts are only useful while they are recent. Anything older is noise. */
    @Scheduled(cron = "0 40 3 * * *")
    public void prune() {
        Instant cutoff = Instant.now().minus(7, ChronoUnit.DAYS);
        attempts.findAll().stream()
                .filter(a -> a.getAt().isBefore(cutoff))
                .forEach(attempts::delete);
    }
}

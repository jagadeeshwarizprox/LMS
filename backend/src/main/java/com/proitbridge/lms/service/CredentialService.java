package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.User;
import com.proitbridge.lms.repo.UserRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Login IDs and first passwords, in one place.
 *
 * Both are derived from the person's name rather than generated, because these accounts
 * are handed over on WhatsApp by somebody reading them out. A random twelve character
 * string is safer on paper and in practice gets mistyped four times and then screenshotted
 * into a group chat, which is worse than the thing it was protecting against.
 *
 * What makes it defensible is that the derived password is a one time key, not a password.
 * It is forced out at first sign in and it expires unused, so the window where a guessable
 * value opens an account is measured in days rather than forever.
 */
@Service
public class CredentialService {

    private final UserRepository users;
    private final SettingsService settings;

    private final MongoTemplate mongo;

    public CredentialService(UserRepository users, SettingsService settings, MongoTemplate mongo) {
        this.users = users;
        this.mongo = mongo;
        this.settings = settings;
    }

    /** How many days an unused first password keeps working. */
    public int defaultPasswordDays() {
        return settings.getInt("auth.defaultPasswordDays", 14);
    }

    /**
     * priya.sharma, and priya.sharma2 when that is taken.
     *
     * The number goes on the end rather than in the middle because somebody reading it
     * out loud gets to the disambiguating part last, when they already know there was a
     * clash. Accents are folded rather than dropped so that Ramírez becomes ramirez and
     * not ramrez.
     */
    public String loginIdFor(String fullName, String fallbackEmail, String excludeUserId) {
        String base = slug(fullName);
        if (base.isBlank() && fallbackEmail != null && fallbackEmail.contains("@")) {
            base = slug(fallbackEmail.substring(0, fallbackEmail.indexOf('@')).replace('.', ' '));
        }
        if (base.isBlank()) base = "user";

        String candidate = base;
        int n = 2;
        while (taken(candidate, excludeUserId)) {
            candidate = base + n;
            n++;
        }
        return candidate;
    }

    private boolean taken(String loginId, String excludeUserId) {
        return accountByLoginId(loginId)
                .filter(u -> excludeUserId == null || !excludeUserId.equals(u.getId()))
                .isPresent();
    }

    /*
     * Finding an account by email or login ID without a case-insensitive scan.
     *
     * findBy...IgnoreCase is an anchored regex with the i flag, and Mongo answers that by
     * walking every key of the unique index: twenty thousand comparisons per lookup, and
     * the import makes one or two per row, mostly misses. Addresses are stored lower case
     * and login IDs are lower-case slugs, so the exact match on the folded value is the
     * normal path and rides the unique index. The fallback covers the few staff accounts
     * saved before staff addresses were folded too: it is restricted to staff roles, so
     * the case-insensitive comparison runs over a few dozen rows found through the
     * role index, never over the learners.
     */
    private static final List<User.Role> STAFF =
            List.of(User.Role.MENTOR, User.Role.ADMIN, User.Role.SUPER_ADMIN);

    public Optional<User> accountByEmail(String email) {
        if (email == null || email.isBlank()) return Optional.empty();
        String folded = email.trim().toLowerCase(Locale.ROOT);
        Optional<User> exact = users.findByEmail(folded);
        return exact.isPresent() ? exact : staffMatch("email", folded);
    }

    public Optional<User> accountByLoginId(String loginId) {
        if (loginId == null || loginId.isBlank()) return Optional.empty();
        String folded = loginId.trim().toLowerCase(Locale.ROOT);
        Optional<User> exact = users.findByLoginId(folded);
        return exact.isPresent() ? exact : staffMatch("loginId", folded);
    }

    private Optional<User> staffMatch(String field, String folded) {
        Query q = new Query(new Criteria().andOperator(
                Criteria.where("role").in(STAFF),
                Criteria.where(field).regex("^" + java.util.regex.Pattern.quote(folded) + "$", "i")))
                .limit(1);
        return Optional.ofNullable(mongo.findOne(q, User.class));
    }

    /** priya@123, from the first word of the name. */
    public String firstPasswordFor(String fullName, String loginId) {
        String first = slug(fullName);
        int dot = first.indexOf('.');
        if (dot > 0) first = first.substring(0, dot);
        if (first.isBlank()) first = loginId == null ? "user" : loginId;
        return first + "@123";
    }

    /** Both at once, for a person who does not exist yet. */
    public Issued issueFor(String fullName, String email) {
        String loginId = loginIdFor(fullName, email, null);
        return new Issued(loginId, firstPasswordFor(fullName, loginId));
    }

    public record Issued(String loginId, String password) { }

    /**
     * ramirez, priya.sharma, jean.luc.picard. Everything that is not a letter or a digit
     * becomes a separator, and runs of separators collapse to one dot.
     */
    public static String slug(String name) {
        if (name == null) return "";
        String folded = Normalizer.normalize(name.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        String cleaned = folded.replaceAll("[^a-z0-9]+", ".");
        return cleaned.replaceAll("^\\.+", "").replaceAll("\\.+$", "");
    }

    /** The message the learner is read out or mailed. */
    public String credentialLines(User u, String password) {
        return "  Login ID: " + u.getLoginId() + "\n"
             + "  Or your email: " + u.getEmail() + "\n"
             + "  Password: " + password + "\n";
    }
}

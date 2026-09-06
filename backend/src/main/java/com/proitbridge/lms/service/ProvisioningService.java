package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/**
 * There is no self sign up. Accounts are created from the record sheet only.
 * A row exists only after the sale closes, so row added means payment confirmed.
 */
@Service
public class ProvisioningService {

    private static final String ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository users;
    private final LearnerRepository learners;
    private final BatchRepository batches;
    private final BundleRepository bundles;
    private final IntakeFormRepository intakeForms;
    private final PasswordEncoder encoder;
    private final MailService mail;
    private final ActivityService activity;
    private final CredentialService credentials;
    private final String walkthroughUrl;
    private final String baseUrl;

    public ProvisioningService(UserRepository users, LearnerRepository learners,
                               BatchRepository batches, BundleRepository bundles,
                               IntakeFormRepository intakeForms, PasswordEncoder encoder,
                               MailService mail, ActivityService activity,
                               CredentialService credentials,
                               @Value("${lms.app.walkthrough-url}") String walkthroughUrl,
                               @Value("${lms.app.base-url}") String baseUrl) {
        this.users = users;
        this.learners = learners;
        this.batches = batches;
        this.bundles = bundles;
        this.intakeForms = intakeForms;
        this.encoder = encoder;
        this.mail = mail;
        this.activity = activity;
        this.credentials = credentials;
        this.walkthroughUrl = walkthroughUrl;
        this.baseUrl = baseUrl;
    }

    public record RecordRow(String fullName, String email, String phone, String whatsapp,
                            String bundleName, Learner.TrackType trackType, String batchCode,
                            LocalDate joinedOn, String sourceRow) {}

    public record Outcome(String email, String status, String detail, String learnerId) {}

    /**
     * What {@link #provision} would do, without doing any of it.
     *
     * Kept deliberately thin: it answers new, existing or would be upgraded, and stops
     * there. Anything more would be a second copy of the provisioning rules, and a
     * preview that disagrees with the commit is worse than no preview.
     */
    public Outcome wouldProvision(RecordRow row) {
        if (row.email() == null || row.email().isBlank()) {
            return new Outcome(row.email(), "SKIPPED", "No email on the row.", null);
        }
        String email = row.email().trim().toLowerCase();
        Optional<User> existing = users.findByEmailIgnoreCase(email);
        if (existing.isEmpty()) {
            return new Outcome(email, "CREATED", "New. An account would be made.", null);
        }
        User u = existing.get();
        Learner l = learners.findByUserId(u.getId()).orElse(null);
        if (l == null) {
            return new Outcome(email, "SKIPPED", "Email already belongs to a staff account.", null);
        }
        if (l.getTrackType() == Learner.TrackType.BATCH
                && row.trackType() == Learner.TrackType.PREMIUM) {
            return new Outcome(email, "UPGRADED", "On batch now, would move to premium.", l.getId());
        }
        return new Outcome(email, "EXISTING", "Already on the platform. Nothing would change.",
                l.getId());
    }

    /**
     * Idempotent on email. An existing batch learner appearing in the premium sheet is
     * upgraded in place, never duplicated, so progress and history survive.
     */
    public Outcome provision(RecordRow row, boolean dispatchMail, String actorEmail) {
        if (row.email() == null || row.email().isBlank()) {
            return new Outcome(row.email(), "SKIPPED", "No email on the row.", null);
        }
        String email = row.email().trim().toLowerCase();

        Optional<User> existing = users.findByEmailIgnoreCase(email);
        if (existing.isPresent()) {
            User u = existing.get();
            Learner l = learners.findByUserId(u.getId()).orElse(null);
            if (l == null) {
                return new Outcome(email, "SKIPPED", "Email already belongs to a staff account.", null);
            }
            if (l.getTrackType() == Learner.TrackType.BATCH
                    && row.trackType() == Learner.TrackType.PREMIUM) {
                l.setTrackType(Learner.TrackType.PREMIUM);
                l.setUpgradedFromBatch(true);
                l.setBatchId(null);
                resolveBundle(row.bundleName()).ifPresent(b -> l.setBundleId(b.getId()));
                learners.save(l);
                activity.log(null, actorEmail, "UPGRADE_TO_PREMIUM", "learner", email);
                return new Outcome(email, "UPGRADED", "Batch account moved to Premium, progress kept.", l.getId());
            }
            return new Outcome(email, "EXISTS", "Already provisioned, nothing changed.", l.getId());
        }

        User u = new User();
        u.setEmail(email);
        u.setFullName(row.fullName() == null ? email : row.fullName().trim());
        u.setPhone(row.phone());
        u.setWhatsapp(row.whatsapp());
        u.setRole(User.Role.LEARNER);
        var issued = credentials.issueFor(u.getFullName(), email);
        u.setLoginId(issued.loginId());
        u.setPasswordHash(encoder.encode(issued.password()));
        u.setMustChangePassword(true);
        u.setDefaultPasswordSetAt(Instant.now());
        users.save(u);
        String password = issued.password();

        Learner l = new Learner();
        l.setUserId(u.getId());
        l.setTrackType(row.trackType());
        l.setJoinedOn(row.joinedOn() == null ? LocalDate.now() : row.joinedOn());
        l.setSourceRow(row.sourceRow());
        resolveBundle(row.bundleName()).ifPresent(b -> l.setBundleId(b.getId()));
        if (row.trackType() == Learner.TrackType.BATCH) {
            Batch batch = null;
            if (row.batchCode() != null && !row.batchCode().isBlank()) {
                String code = row.batchCode().trim().toUpperCase();
                batch = batches.findByCode(code).orElseGet(() -> createBatch(code, l.getBundleId()));
            } else {
                /* the Wednesday rule: a new batch starts every Tuesday, and someone who
                   joins on a Wednesday goes into the batch that has just started rather
                   than waiting six days for the next one */
                batch = batchForJoinDate(l.getJoinedOn(), l.getBundleId());
            }
            if (batch != null) {
                l.setBatchId(batch.getId());
                l.setWhatsappGroupLink(batch.getWhatsappLink());
            }
        }
        learners.save(l);

        IntakeForm form = new IntakeForm();
        form.setLearnerId(l.getId());
        intakeForms.save(form);

        if (dispatchMail) {
            dispatchCredentials(u, password);
        }
        activity.log(null, actorEmail, "PROVISION_LEARNER", "learner",
                email + " (" + row.trackType() + ")");
        return new Outcome(email, "CREATED", "Account created and credentials dispatched.", l.getId());
    }

    /**
     * Used on create and whenever an admin reissues.
     *
     * This puts the account back to the password derived from the name rather than to a
     * new random one, which is the whole point: an admin fielding "I cannot get in" on
     * WhatsApp can say what the password is without looking it up anywhere.
     */
    public String resetAndDispatch(User u) {
        if (u.getLoginId() == null || u.getLoginId().isBlank()) {
            u.setLoginId(credentials.loginIdFor(u.getFullName(), u.getEmail(), u.getId()));
        }
        String password = credentials.firstPasswordFor(u.getFullName(), u.getLoginId());
        u.setPasswordHash(encoder.encode(password));
        u.setMustChangePassword(true);
        u.setDefaultPasswordSetAt(Instant.now());
        users.save(u);
        dispatchCredentials(u, password);
        return password;
    }

    private void dispatchCredentials(User u, String password) {
        String body = """
                Hello %s,

                Your ProITBridge LMS account is ready.

                Sign in at: %s
                Login ID:   %s
                Or use your email: %s
                Password:   %s

                You will be asked to set your own password the first time you sign in,
                and this one stops working if the account is left unused for %d days.

                Before you start, watch the LMS walkthrough: %s

                Team ProITBridge
                """.formatted(u.getFullName(), baseUrl, u.getLoginId(), u.getEmail(), password,
                               credentials.defaultPasswordDays(), walkthroughUrl);
        mail.send(u.getEmail(), "Your ProITBridge LMS login", body, "CREDENTIALS");
    }

    private Batch createBatch(String code, String bundleId) {
        Batch b = new Batch();
        b.setCode(code);
        b.setName("Batch " + code);
        b.setBundleId(bundleId);
        LocalDate tuesday = nextTuesday(LocalDate.now());
        b.setStartDate(tuesday);
        b.setInductionDate(tuesday);
        return batches.save(b);
    }

    /** Wednesday joins fall back into the batch that started the day before. */
    private Batch batchForJoinDate(LocalDate joined, String bundleId) {
        var open = batches.findAllByOrderByStartDateDesc().stream()
                .filter(Batch::isOpen).filter(b -> b.getStartDate() != null).toList();
        if (joined.getDayOfWeek() == java.time.DayOfWeek.WEDNESDAY) {
            var previous = open.stream()
                    .filter(b -> !b.getStartDate().isAfter(joined))
                    .findFirst();
            if (previous.isPresent()) return previous.get();
        }
        LocalDate tuesday = nextTuesday(joined);
        return open.stream()
                .filter(b -> !b.getStartDate().isBefore(tuesday))
                .reduce((a, b) -> a.getStartDate().isBefore(b.getStartDate()) ? a : b)
                .orElseGet(() -> createBatch("B" + (batches.count() + 56), bundleId));
    }

    public static LocalDate nextTuesday(LocalDate from) {
        LocalDate d = from;
        while (d.getDayOfWeek().getValue() != 2) {
            d = d.plusDays(1);
        }
        return d;
    }

    private Optional<Bundle> resolveBundle(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String wanted = name.trim().toLowerCase();
        return bundles.findAll().stream()
                .filter(b -> b.getName().toLowerCase().equals(wanted)
                        || b.getName().toLowerCase().contains(wanted)
                        || wanted.contains(b.getName().toLowerCase()))
                .findFirst();
    }

    public static String generatePassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }
}

package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** The admin runs the machine. Anything that changes what the machine is belongs to Super Admin. */
@Service
public class AdminService {

    private final UserRepository users;
    private final LearnerRepository learners;
    private final BatchRepository batches;
    private final BundleRepository bundles;
    private final AssignmentRepository assignments;
    private final ProjectWorkRepository projects;
    private final MockRequestRepository mocks;
    private final LearnerFeatureOverrideRepository overrides;
    private final AnnouncementRepository announcements;
    private final MentorAssignmentRepository mentorHistory;
    private final SlotRepository slots;
    private final AttendanceRepository attendance;
    private final ProvisioningService provisioning;
    private final MentorService mentorService;
    private final MailService mail;
    private final ActivityService activity;
    private final CredentialService credentials;
    private final PasswordEncoder encoder;

    private final AtomicInteger roundRobin = new AtomicInteger(0);

    public AdminService(UserRepository users, LearnerRepository learners, BatchRepository batches,
                        BundleRepository bundles, AssignmentRepository assignments,
                        ProjectWorkRepository projects, MockRequestRepository mocks,
                        LearnerFeatureOverrideRepository overrides,
                        AnnouncementRepository announcements, MentorAssignmentRepository mentorHistory,
                        ProvisioningService provisioning,
                        MentorService mentorService, MailService mail, ActivityService activity,
                        CredentialService credentials, PasswordEncoder encoder,
                        SlotRepository slots, AttendanceRepository attendance) {
        this.users = users; this.learners = learners; this.batches = batches; this.bundles = bundles;
        this.assignments = assignments; this.projects = projects; this.mocks = mocks;
        this.overrides = overrides; this.announcements = announcements; this.mentorHistory = mentorHistory;
        this.provisioning = provisioning; this.mentorService = mentorService;
        this.mail = mail; this.activity = activity;
        this.credentials = credentials; this.encoder = encoder;
        this.slots = slots; this.attendance = attendance;
    }

    public Map<String, Object> overview() {
        List<Learner> all = learners.findAll();
        long premium = all.stream().filter(l -> l.getTrackType() == Learner.TrackType.PREMIUM).count();
        long unassigned = all.stream().filter(l -> l.getMentorId() == null).count();
        long onboarding = all.stream().filter(l -> !l.gatesCleared()).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("learners", all.size());
        m.put("premium", premium);
        m.put("batch", all.size() - premium);
        m.put("batches", batches.count());
        m.put("mentors", users.findByRole(User.Role.MENTOR).size());
        m.put("unassigned", unassigned);
        m.put("onboardingOpen", onboarding);
        m.put("tasksWaiting", assignments.findAll().stream()
                .filter(a -> "SUBMITTED".equals(a.getStatus())).count());
        m.put("projectsWaiting", projects.findAll().stream()
                .filter(p -> "SUBMITTED".equals(p.getStatus())).count());
        m.put("mocksWaiting", mocks.findAll().stream()
                .filter(r -> "PENDING".equals(r.getStatus())).count());
        return m;
    }

    /**
     * The register, optionally narrowed to one track, one batch or one mentor.
     *
     * The mentor filter is what the Mentors screen links into: clicking a name there used
     * to leave you scanning the whole register for their learners by eye.
     *
     * Search deliberately reads the learner's own name and email and nothing else. A
     * search that also matched the mentor column returned that mentor's entire caseload
     * for a query that looked like one person's name, which reads as the wrong rows
     * rather than as a feature.
     */
    public List<Map<String, Object>> register(String trackType, String batchId,
                                              String mentorId, String query) {
        String needle = query == null || query.isBlank() ? null : query.trim().toLowerCase();
        return learners.findAll().stream()
                .filter(l -> trackType == null || l.getTrackType().name().equalsIgnoreCase(trackType))
                .filter(l -> batchId == null || batchId.equals(l.getBatchId()))
                .filter(l -> mentorId == null || mentorId.equals(l.getMentorId()))
                .map(mentorService::learnerRow)
                .filter(r -> needle == null
                        || String.valueOf(r.get("name")).toLowerCase().contains(needle)
                        || String.valueOf(r.get("email")).toLowerCase().contains(needle))
                .collect(Collectors.toList());
    }

    /**
     * The mentors an admin can assign work to.
     *
     * The register and the cover screen both need this list, and both used to read the
     * full staff hierarchy, which is super admin only: an admin got a 403 and an empty
     * dropdown on the one screen whose whole job is assigning mentors. This is the same
     * data cut down to what assignment needs, and open to admins.
     */
    public List<Map<String, Object>> mentors() {
        return users.findAll().stream()
                .filter(u -> u.getRole() == User.Role.MENTOR)
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", u.getId());
                    m.put("name", u.getFullName());
                    m.put("email", u.getEmail());
                    m.put("role", u.getRole());
                    m.put("active", u.isActive());
                    m.put("learners", learners.findByMentorId(u.getId()).size());
                    return m;
                })
                .collect(Collectors.toList());
    }

    /**
     * A mentor is assigned once and then left alone. The relationship is the product,
     * so round robin only fills an empty seat: it never reshuffles a learner who
     * already has someone. Moving a learner is a deliberate act that needs a reason
     * and is kept on the record.
     */
    /**
     * One learner, entered by hand, with everything the sheet import would have carried.
     *
     * Provisioning stays the single path so the Tuesday batch rule, the intake form and
     * the credential mail cannot drift apart from the bulk route. What this adds is the
     * things a form can offer and a spreadsheet column cannot: a mentor chosen at the
     * moment of creation, and the credentials handed straight back so whoever is on the
     * phone with the learner can read them out.
     */
    public Map<String, Object> enrolOne(Map<String, String> body, String actorEmail) {
        String bundleName = body.get("bundleName");
        if (body.get("bundleId") != null && !body.get("bundleId").isBlank()) {
            bundleName = bundles.findById(body.get("bundleId")).map(Bundle::getName).orElse(bundleName);
        }
        /* a learner with no course has an empty roadmap and every video answers that it
           is not part of their course, which nobody ever connects back to a blank
           dropdown at enrolment */
        if (bundleName == null || bundleName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choose the course this learner is on.");
        }
        Learner.TrackType track =
                Learner.TrackType.valueOf(body.getOrDefault("trackType", "PREMIUM").toUpperCase());

        String batchCode = body.get("batchCode");
        if (body.get("batchId") != null && !body.get("batchId").isBlank()) {
            batchCode = batches.findById(body.get("batchId")).map(Batch::getCode).orElse(batchCode);
        }
        /*
         * A batch learner has to be put in a named batch.
         *
         * Leaving it empty used to fall through to the Tuesday placement rule, which
         * quietly dropped the learner into whichever cohort was running. That rule is
         * right for the sheet import, where nobody is watching and a batch column is
         * often blank, and wrong here: somebody is on the form, has the batch in front of
         * them, and left the box empty by accident. The learner then turned up in a
         * cohort nobody had chosen, with that cohort's mentor.
         */
        if (track == Learner.TrackType.BATCH && (batchCode == null || batchCode.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Choose the batch. A batch learner is not placed automatically from here.");
        }

        /*
         * Credentials go out by mail, and everything after that happens on WhatsApp: the
         * group link, the reminder when they have not signed in, the nudge from their
         * mentor. An account with no number is one nobody can reach.
         */
        String phone = Validate.phone(body.get("phone"), "Phone number");
        String whatsapp = Validate.phone(body.get("whatsapp"), "WhatsApp number");
        if (phone == null && whatsapp == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Give a phone or a WhatsApp number. Credentials and everything after "
                    + "them are followed up on one of the two.");
        }

        LocalDate joined = body.get("joinedOn") == null || body.get("joinedOn").isBlank()
                ? LocalDate.now() : LocalDate.parse(body.get("joinedOn"));

        var row = new ProvisioningService.RecordRow(
                body.get("fullName"), body.get("email"), phone, whatsapp,
                bundleName, track, batchCode, joined, "manual");
        var outcome = provisioning.provision(row, true, actorEmail);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", outcome.status());
        out.put("detail", outcome.detail());
        out.put("email", outcome.email());
        out.put("learnerId", outcome.learnerId());

        if (outcome.learnerId() != null) {
            String mentorId = body.get("mentorId");
            if (mentorId != null && !mentorId.isBlank()) {
                assignMentor(outcome.learnerId(), mentorId, "Chosen at enrolment", actorEmail);
            }
            learners.findById(outcome.learnerId())
                    .flatMap(l -> users.findById(l.getUserId()))
                    .ifPresent(u -> {
                        out.put("loginId", u.getLoginId());
                        /* only for an account that has not been used yet: once somebody
                           has chosen their own password this is no longer what it is */
                        if (u.isMustChangePassword()) {
                            out.put("password",
                                    credentials.firstPasswordFor(u.getFullName(), u.getLoginId()));
                        }
                    });
        }
        return out;
    }

    /** What would be issued, before anything is created. */
    public Map<String, Object> credentialPreview(String fullName, String email) {
        var issued = credentials.issueFor(fullName, email);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loginId", issued.loginId());
        m.put("password", issued.password());
        m.put("expiresInDays", credentials.defaultPasswordDays());
        return m;
    }

    /** The button for "I cannot get in". Same scheme, clock restarted. */
    public Map<String, Object> resetLearnerPassword(String learnerId, String actorEmail) {
        Learner l = learners.findById(learnerId).orElseThrow();
        User u = users.findById(l.getUserId()).orElseThrow();
        if (u.getLoginId() == null || u.getLoginId().isBlank()) {
            u.setLoginId(credentials.loginIdFor(u.getFullName(), u.getEmail(), u.getId()));
        }
        String pw = credentials.firstPasswordFor(u.getFullName(), u.getLoginId());
        u.setPasswordHash(encoder.encode(pw));
        u.setMustChangePassword(true);
        u.setDefaultPasswordSetAt(Instant.now());
        users.save(u);
        mail.send(u.getEmail(), "Your ProITBridge login has been reissued",
                "Hello " + u.getFullName() + ",\n\n"
                + "Your password has been set back to the one you were first given.\n\n"
                + credentials.credentialLines(u, pw)
                + "\nYou will be asked to choose your own the moment you sign in.\n\n"
                + "Team ProITBridge", "CREDENTIALS");
        activity.log(null, actorEmail, "RESET_PASSWORD", "learner", u.getEmail());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loginId", u.getLoginId());
        m.put("password", pw);
        m.put("expiresInDays", credentials.defaultPasswordDays());
        return m;
    }

    public Learner assignMentor(String learnerId, String mentorId, String reason, String actorEmail) {
        Learner l = learners.findById(learnerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Learner not found."));
        String existing = l.getMentorId();

        if (existing != null && (mentorId == null || mentorId.isBlank())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This learner already has a mentor. Pick the new mentor explicitly if you mean to move them.");
        }
        if (existing != null && existing.equals(mentorId)) return l;
        if (existing != null && (reason == null || reason.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Changing a mentor needs a reason. It stays on the learner's record.");
        }

        String chosen = mentorId;
        if (chosen == null || chosen.isBlank()) {
            /*
             * A batch has one mentor, set when the batch was made, and every learner in
             * it goes to that person. Round robin was spreading a cohort across whoever
             * happened to be next, which is the opposite of how a batch is taught.
             */
            if (l.getBatchId() != null) {
                chosen = batches.findById(l.getBatchId()).map(Batch::getMentorId).orElse(null);
            }
        }
        if (chosen == null || chosen.isBlank()) {
            List<User> mentors = users.findByRole(User.Role.MENTOR).stream()
                    .filter(User::isActive).toList();
            if (mentors.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "No active mentors to assign.");
            }
            chosen = mentors.get(Math.abs(roundRobin.getAndIncrement()) % mentors.size()).getId();
        }

        l.setMentorId(chosen);
        learners.save(l);

        MentorAssignment h = new MentorAssignment();
        h.setLearnerId(learnerId);
        h.setFromMentorId(existing);
        h.setToMentorId(chosen);
        h.setReason(existing == null ? "First assignment" : reason);
        h.setActorEmail(actorEmail);
        mentorHistory.save(h);

        activity.log(null, actorEmail, existing == null ? "ASSIGN_MENTOR" : "CHANGE_MENTOR",
                "learner", learnerId + " -> " + chosen
                        + (existing == null ? "" : " (" + reason + ")"));
        return l;
    }

    /** Only ever fills empty seats. An import or an upgrade can never move anyone. */
    public int assignAllUnassigned(String actorEmail) {
        int n = 0;
        for (Learner l : learners.findAll()) {
            if (l.getMentorId() == null) {
                assignMentor(l.getId(), null, null, actorEmail);
                n++;
            }
        }
        return n;
    }

    public List<Map<String, Object>> mentorHistory(String learnerId) {
        return mentorHistory.findByLearnerIdOrderByAtDesc(learnerId).stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", h.getAt());
            m.put("from", h.getFromMentorId() == null ? null
                    : users.findById(h.getFromMentorId()).map(User::getFullName).orElse(null));
            m.put("to", users.findById(h.getToMentorId()).map(User::getFullName).orElse(null));
            m.put("reason", h.getReason());
            m.put("by", h.getActorEmail());
            return m;
        }).collect(Collectors.toList());
    }

    public String resendCredentials(String learnerId, String actorEmail) {
        Learner l = learners.findById(learnerId).orElseThrow();
        User u = users.findById(l.getUserId()).orElseThrow();
        provisioning.resetAndDispatch(u);
        activity.log(null, actorEmail, "RESEND_CREDENTIALS", "learner", u.getEmail());
        return u.getEmail();
    }

    /* ---------------------------------------------------------------- batches */

    public Batch createBatch(Batch body, String actorEmail) {
        if (body.getCode() == null || body.getCode().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A batch needs a code, for example B56.");
        }
        body.setCode(body.getCode().trim().toUpperCase());
        if (batches.findByCode(body.getCode()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That batch code already exists.");
        }
        if (body.getMentorId() == null || body.getMentorId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A batch needs a mentor. Every learner in it will be theirs.");
        }
        User mentor = users.findById(body.getMentorId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such mentor."));
        if (!mentor.isActive()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    mentor.getFullName() + " is switched off and cannot take a batch.");
        }
        if (body.getStartDate() == null) {
            body.setStartDate(ProvisioningService.nextTuesday(LocalDate.now()));
        }
        if (body.getInductionDate() == null) body.setInductionDate(body.getStartDate());
        body.setWhatsappLink(Validate.link(body.getWhatsappLink(), "WhatsApp link"));
        body.setCommunityLink(Validate.link(body.getCommunityLink(), "Community link"));
        Batch saved = batches.save(body);
        activity.log(null, actorEmail, "CREATE_BATCH", "batch", saved.getCode());
        return saved;
    }

    /**
     * Editing a batch after it exists.
     *
     * There was no way to. Create and mark-induction-done were the only writes, so a
     * batch's mentor could never be changed after the fact, a wrong start date could not
     * be corrected, and a WhatsApp link could not be added later. If a mentor left, every
     * learner had to be moved one at a time.
     *
     * Changing the mentor moves the whole cohort with it, because that is what a batch
     * mentor means. Anything left null is left alone.
     */
    public Batch updateBatch(String batchId, Map<String, String> body, String actorEmail) {
        Batch b = batches.findById(batchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch not found."));

        String name = body.get("name");
        if (name != null) b.setName(name.trim());
        String wa = body.get("whatsappLink");
        if (wa != null) b.setWhatsappLink(Validate.link(wa, "WhatsApp link"));
        String community = body.get("communityLink");
        if (community != null) b.setCommunityLink(Validate.link(community, "Community link"));

        String start = body.get("startDate");
        if (start != null && !start.isBlank()) {
            LocalDate d = LocalDate.parse(start);
            b.setStartDate(d);
            if (b.getInductionDate() == null || !b.isInductionDone()) b.setInductionDate(d);
        }

        String mentorId = body.get("mentorId");
        int moved = 0;
        if (mentorId != null && !mentorId.isBlank() && !mentorId.equals(b.getMentorId())) {
            User m = users.findById(mentorId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such mentor."));
            if (!m.isActive()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        m.getFullName() + " is switched off and cannot take a batch.");
            }
            String from = b.getMentorId();
            b.setMentorId(mentorId);
            for (Learner l : learners.findByBatchId(batchId)) {
                if (mentorId.equals(l.getMentorId())) continue;
                MentorAssignment h = new MentorAssignment();
                h.setLearnerId(l.getId());
                h.setFromMentorId(l.getMentorId());
                h.setToMentorId(mentorId);
                h.setReason("Batch " + b.getCode() + " changed mentor");
                h.setActorEmail(actorEmail);
                mentorHistory.save(h);
                l.setMentorId(mentorId);
                learners.save(l);
                moved++;
            }
            activity.log(null, actorEmail, "CHANGE_BATCH_MENTOR", "batch",
                    b.getCode() + " " + from + " to " + mentorId + ", " + moved + " learners moved");
        }

        Batch saved = batches.save(b);
        activity.log(null, actorEmail, "UPDATE_BATCH", "batch", saved.getCode());
        return saved;
    }

    /**
     * Closing a batch, and reopening one.
     *
     * `Batch.open` existed and was never set false anywhere, so no batch ever closed. The
     * Wednesday auto-placement rule picks among open batches, which means after a year it
     * would have been choosing between fifty of them, and every batch dropdown in the
     * product listed all of them for ever.
     *
     * Closing is about intake only. Nobody is removed, nothing is archived, and the
     * cohort carries on exactly as it was: the batch simply stops being somewhere new
     * learners can land.
     */
    public Batch setBatchOpen(String batchId, boolean open, String actorEmail) {
        Batch b = batches.findById(batchId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Batch not found."));
        b.setOpen(open);
        Batch saved = batches.save(b);
        activity.log(null, actorEmail, open ? "REOPEN_BATCH" : "CLOSE_BATCH", "batch", saved.getCode());
        return saved;
    }

    /**
     * The induction is held once. Everyone in the batch has the gate cleared, and a
     * mid batch joiner watches the recording instead of a second live session.
     */
    public Batch markInductionDone(String batchId, String recordingRef, String actorEmail) {
        Batch b = batches.findById(batchId).orElseThrow();
        b.setInductionDone(true);
        b.setInductionRecordingId(recordingRef);
        batches.save(b);
        /*
         * Held, so the cohort's gate clears -- except for anyone a mentor has already
         * marked absent on an induction slot for this batch. Marking the session done is
         * an assertion that it ran, not that everybody was in it, and overwriting a
         * recorded absence would throw away the better evidence.
         */
        Set<String> absent = slots.findAll().stream()
                .filter(sl -> "INDUCTION".equals(sl.getKind()))
                .filter(sl -> batchId.equals(sl.getBatchId()))
                .flatMap(sl -> attendance.findBySlotId(sl.getId()).stream())
                .filter(a -> "ABSENT".equals(a.getState()))
                .map(Attendance::getLearnerId)
                .collect(java.util.stream.Collectors.toSet());

        for (Learner l : learners.findByBatchId(batchId)) {
            if (absent.contains(l.getId())) continue;
            l.setInductionWatched(true);
            l.setGateCallDone(true);
            learners.save(l);
        }
        activity.log(null, actorEmail, "INDUCTION_DONE", "batch", b.getCode());
        return b;
    }

    public List<Map<String, Object>> batchList() {
        return batches.findAllByOrderByStartDateDesc().stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("code", b.getCode());
            m.put("name", b.getName());
            m.put("startDate", b.getStartDate());
            m.put("inductionDate", b.getInductionDate());
            m.put("inductionDone", b.isInductionDone());
            m.put("whatsappLink", b.getWhatsappLink());
            m.put("size", learners.countByBatchId(b.getId()));
            m.put("bundle", b.getBundleId() == null ? null
                    : bundles.findById(b.getBundleId()).map(Bundle::getName).orElse(null));
            m.put("open", b.isOpen());
            m.put("mentorId", b.getMentorId());
            m.put("mentor", b.getMentorId() == null ? null
                    : users.findById(b.getMentorId()).map(User::getFullName).orElse(null));
            /*
             * Moving one learner to a different mentor is a deliberate act and must not
             * silently rewrite the whole cohort, so the batch keeps its own mentor. What
             * was missing was any sign of the divergence: this screen went on showing the
             * batch mentor and looked simply out of date. The count says how many learners
             * in this batch now answer to somebody else, so the row is honest either way.
             */
            m.put("movedOut", b.getMentorId() == null ? 0
                    : learners.findByBatchId(b.getId()).stream()
                        .filter(l -> l.getMentorId() != null
                                && !l.getMentorId().equals(b.getMentorId()))
                        .count());
            return m;
        }).collect(Collectors.toList());
    }

    /* ---------------------------------------------------------------- exceptions */

    /** A per learner exception inside existing settings. Defaults still live with Super Admin. */
    public LearnerFeatureOverride setOverride(String learnerId, String key, boolean enabled, String actorEmail) {
        LearnerFeatureOverride o = overrides.findByLearnerIdAndFeatureKey(learnerId, key)
                .orElseGet(LearnerFeatureOverride::new);
        o.setLearnerId(learnerId);
        o.setFeatureKey(key);
        o.setEnabled(enabled);
        activity.log(null, actorEmail, "FEATURE_OVERRIDE", "learner", learnerId + " " + key + "=" + enabled);
        return overrides.save(o);
    }

    public Announcement announce(String batchId, String authorId, String authorName, String body) {
        Announcement a = new Announcement();
        a.setBatchId(batchId);
        a.setAuthorId(authorId);
        a.setAuthorName(authorName);
        a.setBody(body);
        return announcements.save(a);
    }

    public List<MailLog> mailLog() {
        return mail.recent();
    }
}

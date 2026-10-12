package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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
    /* targeted reads on collections this service does not own the repository for */
    private final MongoTemplate mongo;
    private final ProgressSnapshotService snapshots;
    private final LearnerProgressService progressView;

    private final AtomicInteger roundRobin = new AtomicInteger(0);

    public AdminService(UserRepository users, LearnerRepository learners, BatchRepository batches,
                        BundleRepository bundles, AssignmentRepository assignments,
                        ProjectWorkRepository projects, MockRequestRepository mocks,
                        LearnerFeatureOverrideRepository overrides,
                        AnnouncementRepository announcements, MentorAssignmentRepository mentorHistory,
                        ProvisioningService provisioning,
                        MentorService mentorService, MailService mail, ActivityService activity,
                        CredentialService credentials, PasswordEncoder encoder,
                        SlotRepository slots, AttendanceRepository attendance,
                        MongoTemplate mongo, ProgressSnapshotService snapshots,
                        LearnerProgressService progressView) {
        this.mongo = mongo; this.snapshots = snapshots; this.progressView = progressView;
        this.users = users; this.learners = learners; this.batches = batches; this.bundles = bundles;
        this.assignments = assignments; this.projects = projects; this.mocks = mocks;
        this.overrides = overrides; this.announcements = announcements; this.mentorHistory = mentorHistory;
        this.provisioning = provisioning; this.mentorService = mentorService;
        this.mail = mail; this.activity = activity;
        this.credentials = credentials; this.encoder = encoder;
        this.slots = slots; this.attendance = attendance;
    }

    /**
     * The admin home numbers, all as counts.
     *
     * This loaded every learner and every assignment, project and mock request into
     * memory to count a few of them, on the screen an admin opens first every morning.
     * Each figure is now one count query; the ones with an index behind them (track,
     * mentor, assignment status, role) are answered from the index alone.
     */
    public Map<String, Object> overview() {
        long total = learners.count();
        long premium = learners.countByTrackType(Learner.TrackType.PREMIUM);
        /* is(null) matches a missing field as well as an explicit null, which is what
           getMentorId() == null meant on the loaded row */
        long unassigned = mongo.count(new Query(Criteria.where("mentorId").is(null)), Learner.class);
        long onboarding = mongo.count(new Query(AdminQueries.stillOnboarding()), Learner.class);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("learners", total);
        m.put("premium", premium);
        m.put("batch", total - premium);
        m.put("batches", batches.count());
        m.put("mentors", users.countByRole(User.Role.MENTOR));
        m.put("unassigned", unassigned);
        m.put("onboardingOpen", onboarding);
        m.put("tasksWaiting", mongo.count(new Query(Criteria.where("status").is("SUBMITTED")), Assignment.class));
        m.put("projectsWaiting", mongo.count(new Query(Criteria.where("status").is("SUBMITTED")), ProjectWork.class));
        m.put("mocksWaiting", mongo.count(new Query(Criteria.where("status").is("PENDING")), MockRequest.class));
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
    public Map<String, Object> register(String trackType, String batchId, String mentorId,
                                        String query, Boolean onboarded,
                                        Integer pageParam, Integer sizeParam) {
        int page = AdminQueries.page(pageParam);
        int size = AdminQueries.size(sizeParam);

        List<Criteria> parts = new ArrayList<>();
        if (AdminQueries.hasText(trackType)) {
            Learner.TrackType t;
            try {
                t = Learner.TrackType.valueOf(trackType.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown track " + trackType + ".");
            }
            parts.add(Criteria.where("trackType").is(t));
        }
        if (AdminQueries.hasText(batchId)) parts.add(Criteria.where("batchId").is(batchId));
        if (AdminQueries.hasText(mentorId)) parts.add(Criteria.where("mentorId").is(mentorId));
        if (onboarded != null) {
            parts.add(onboarded ? AdminQueries.onboarded() : AdminQueries.stillOnboarding());
        }

        /*
         * Search: users first, then their learner rows.
         *
         * The name, email and login ID live on the user, not the learner, so the search
         * runs as two indexed steps: a case-insensitive match over learner accounts that
         * returns ids only, then the learner page restricted to those user ids alongside
         * every other filter. The alternative, a lower-cased search key copied onto the
         * learner, would be faster on paper and wrong in practice: a rename made anywhere
         * that writes users (the learner's own profile, a staff edit, the login ID
         * migration) would have to remember to update it, and the other side of this
         * codebase writes users in places this side does not own. Twenty thousand short
         * user documents is a scan Mongo finishes in milliseconds, and the 300ms debounce
         * on the box means it runs once per pause in typing, not per key.
         */
        if (AdminQueries.hasText(query)) {
            String rx = AdminQueries.literal(query);
            Query uq = new Query(new Criteria().andOperator(
                    Criteria.where("role").is(User.Role.LEARNER),
                    new Criteria().orOperator(
                            Criteria.where("fullName").regex(rx, "i"),
                            Criteria.where("email").regex(rx, "i"),
                            Criteria.where("loginId").regex(rx, "i"))));
            uq.fields().include("_id");
            List<String> userIds = mongo.find(uq, User.class).stream().map(User::getId).toList();
            if (userIds.isEmpty()) return AdminQueries.pageOf(List.of(), 0, page, size);
            parts.add(Criteria.where("userId").in(userIds));
        }

        Criteria where = AdminQueries.all(parts);
        long total = mongo.count(new Query(where), Learner.class);
        /* _id order is creation order, which is the order the unpaged list came back in */
        Query pq = new Query(where).with(Sort.by(Sort.Direction.ASC, "_id"))
                .skip((long) page * size).limit(size);
        List<Learner> slice = mongo.find(pq, Learner.class);
        return AdminQueries.pageOf(registerRows(slice), total, page, size);
    }

    /**
     * The register row for a page of learners, with every lookup done once per page.
     *
     * Same keys, same values as MentorService.learnerRow, which this used to call per
     * row: that did a user, a batch, a course and a mentor lookup each time, two hundred
     * round trips for a page of fifty. Here the users, mentors, batches and courses for
     * the whole page arrive in three findAllById calls. The watch and attendance figures
     * still come from LearnerProgressService.rowStats, one learner at a time, because
     * that is where their definition lives; for fifty rows it is bounded.
     */
    private List<Map<String, Object>> registerRows(List<Learner> slice) {
        if (slice.isEmpty()) return List.of();
        Set<String> userIds = new HashSet<>();
        Set<String> batchIds = new HashSet<>();
        Set<String> bundleIds = new HashSet<>();
        for (Learner l : slice) {
            if (l.getUserId() != null) userIds.add(l.getUserId());
            if (l.getMentorId() != null) userIds.add(l.getMentorId());
            if (l.getBatchId() != null) batchIds.add(l.getBatchId());
            if (l.getBundleId() != null) bundleIds.add(l.getBundleId());
        }
        Map<String, User> who = new HashMap<>();
        users.findAllById(userIds).forEach(u -> who.put(u.getId(), u));
        Map<String, String> batchCode = new HashMap<>();
        batches.findAllById(batchIds).forEach(b -> batchCode.put(b.getId(), b.getCode()));
        Map<String, String> bundleName = new HashMap<>();
        bundles.findAllById(bundleIds).forEach(b -> bundleName.put(b.getId(), b.getName()));

        List<Map<String, Object>> out = new ArrayList<>(slice.size());
        for (Learner l : slice) {
            User u = who.get(l.getUserId());
            User mentor = l.getMentorId() == null ? null : who.get(l.getMentorId());
            Map<String, Object> s = snapshots.stats(l);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("learnerId", l.getId());
            m.put("userId", l.getUserId());
            m.put("name", u == null ? "Learner" : u.getFullName());
            m.put("email", u == null ? null : u.getEmail());
            m.put("loginId", u == null ? null : u.getLoginId());
            m.put("trackType", l.getTrackType());
            m.put("batch", l.getBatchId() == null ? null : batchCode.get(l.getBatchId()));
            m.put("bundle", l.getBundleId() == null ? null : bundleName.get(l.getBundleId()));
            m.put("percent", s.get("percent"));
            m.put("lastLoginAt", u == null ? null : u.getLastLoginAt());
            m.put("onboarded", l.gatesCleared());
            m.put("suspended", u != null && u.isSuspended());
            m.put("active", u == null || u.isActive());
            m.put("mentorId", l.getMentorId());
            m.put("mentor", mentor == null ? null : mentor.getFullName());
            m.putAll(progressView.rowStats(l));
            out.add(m);
        }
        return out;
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
        /* mentors by role off the index, and a count per mentor off the learner mentorId
           index; the old version read every user and every caseload in full */
        return users.findByRole(User.Role.MENTOR).stream()
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", u.getId());
                    m.put("name", u.getFullName());
                    m.put("email", u.getEmail());
                    m.put("role", u.getRole());
                    m.put("active", u.isActive());
                    m.put("learners", mongo.count(
                            new Query(Criteria.where("mentorId").is(u.getId())), Learner.class));
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
        String bundleId = body.get("bundleId");
        if (bundleId != null && !bundleId.isBlank()) {
            /* the id picked on screen is what provisioning uses; the name is only for the
               message. Matching on the name again could land on a similarly named course */
            Bundle chosen = bundles.findById(bundleId).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "That course no longer exists."));
            bundleName = chosen.getName();
        } else {
            bundleId = null;
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
                bundleName, track, batchCode, joined, ProvisioningService.MANUAL_SOURCE, bundleId);
        var outcome = provisioning.provision(row, true, actorEmail);
        /* a row the provisioning rules refused came back as a 200 with status ERROR, so the
           form cleared itself and showed the refusal as an ordinary note. Somebody typed
           this in by hand; they get the reason as an error and keep what they typed */
        if (ProvisioningService.ERROR.equals(outcome.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    outcome.detail() == null ? "This learner could not be enrolled." : outcome.detail());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", outcome.status());
        out.put("detail", outcome.detail());
        out.put("email", outcome.email());
        out.put("learnerId", outcome.learnerId());

        if (outcome.learnerId() != null) {
            String mentorId = body.get("mentorId");
            /* only for an account made or upgraded just now. An email that was already
               here and left alone keeps the mentor it has; moving them is a deliberate
               act with a reason. An upgrade to premium is exactly when a learner needs a
               mentor of their own, and the one picked on the form was being dropped */
            if (mentorId != null && !mentorId.isBlank()) {
                if ("CREATED".equals(outcome.status())) {
                    assignMentor(outcome.learnerId(), mentorId, "Chosen at enrolment", actorEmail);
                } else if ("UPGRADED".equals(outcome.status())) {
                    assignMentor(outcome.learnerId(), mentorId, "Upgraded to premium at enrolment", actorEmail);
                }
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

        if (mentorId != null && !mentorId.isBlank()) {
            User target = users.findById(mentorId).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such mentor."));
            if (target.getRole() != User.Role.MENTOR || !target.isActive()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Pick an active mentor. " + target.getFullName() + " cannot take learners.");
            }
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
        /*
         * Only the empty seats, by query, and only their ids. Walking every learner to
         * find the few without a mentor loaded the whole register to do a little work.
         * Ids first, then one assignment at a time: assignMentor reloads and saves each
         * row, so nothing here holds more than the id list.
         */
        Query q = new Query(Criteria.where("mentorId").is(null))
                .with(Sort.by(Sort.Direction.ASC, "_id"));
        q.fields().include("_id");
        int n = 0;
        for (Learner l : mongo.find(q, Learner.class)) {
            assignMentor(l.getId(), null, null, actorEmail);
            n++;
        }
        return n;
    }

    public List<Map<String, Object>> mentorHistory(String learnerId) {
        List<MentorAssignment> rows = mentorHistory.findByLearnerIdOrderByAtDesc(learnerId);
        Set<String> ids = new HashSet<>();
        rows.forEach(h -> {
            if (h.getFromMentorId() != null) ids.add(h.getFromMentorId());
            if (h.getToMentorId() != null) ids.add(h.getToMentorId());
        });
        Map<String, String> name = new HashMap<>();
        users.findAllById(ids).forEach(u -> name.put(u.getId(), u.getFullName()));
        return rows.stream().map(h -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", h.getAt());
            m.put("from", h.getFromMentorId() == null ? null : name.get(h.getFromMentorId()));
            m.put("to", h.getToMentorId() == null ? null : name.get(h.getToMentorId()));
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
        /*
         * A batch could be created with a code and nothing else, so the register filled
         * up with rows called B57 and nobody could tell one cohort from another.
         */
        if (body.getName() == null || body.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A batch needs a name as well as a code.");
        }
        body.setName(body.getName().trim());
        /*
         * The conflict message used to stop at "that code already exists", which reads as
         * a dead end to somebody whose actual intent was to put one more learner into the
         * batch that already has that code. It says where to go instead.
         */
        if (batches.findByCode(body.getCode()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Batch " + body.getCode() + " already exists. To add a learner to it, use "
                    + "Register and pick it from the batch list rather than creating it again.");
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
        /* this batch's induction slots by query, not every slot ever booked */
        Set<String> absent = mongo.find(new Query(Criteria.where("kind").is("INDUCTION")
                        .and("batchId").is(batchId)), Slot.class).stream()
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
        /* bounded by the number of batches; names for the whole list arrive in two reads */
        List<Batch> all = batches.findAllByOrderByStartDateDesc();
        Map<String, String> bundleName = new HashMap<>();
        bundles.findAllById(all.stream().map(Batch::getBundleId).filter(Objects::nonNull)
                .collect(Collectors.toSet())).forEach(x -> bundleName.put(x.getId(), x.getName()));
        Map<String, String> mentorName = new HashMap<>();
        users.findAllById(all.stream().map(Batch::getMentorId).filter(Objects::nonNull)
                .collect(Collectors.toSet())).forEach(x -> mentorName.put(x.getId(), x.getFullName()));
        return all.stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("code", b.getCode());
            m.put("name", b.getName());
            m.put("startDate", b.getStartDate());
            m.put("inductionDate", b.getInductionDate());
            m.put("inductionDone", b.isInductionDone());
            m.put("whatsappLink", b.getWhatsappLink());
            m.put("size", learners.countByBatchId(b.getId()));
            m.put("bundle", b.getBundleId() == null ? null : bundleName.get(b.getBundleId()));
            m.put("open", b.isOpen());
            m.put("mentorId", b.getMentorId());
            m.put("mentor", b.getMentorId() == null ? null : mentorName.get(b.getMentorId()));
            /*
             * Moving one learner to a different mentor is a deliberate act and must not
             * silently rewrite the whole cohort, so the batch keeps its own mentor. What
             * was missing was any sign of the divergence: this screen went on showing the
             * batch mentor and looked simply out of date. The count says how many learners
             * in this batch now answer to somebody else, so the row is honest either way.
             */
            m.put("movedOut", b.getMentorId() == null ? 0L
                    : mongo.count(new Query(Criteria.where("batchId").is(b.getId())
                            .and("mentorId").ne(null).nin(b.getMentorId())), Learner.class));
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

    /** The mail log, a page at a time, optionally narrowed to one recipient or kind. */
    public Map<String, Object> mailLog(String to, String kind, String status,
                                       Integer page, Integer size) {
        return mail.page(to, kind, status, AdminQueries.page(page), AdminQueries.size(size));
    }
}

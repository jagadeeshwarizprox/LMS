package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/** The super admin configures the system: catalogue, pricing, roles, toggles, deletion. */
@Service
public class SuperAdminService {

    private final UserRepository users;
    private final LearnerRepository learners;
    private final ModuleRepository modules;
    private final BundleRepository bundles;
    private final ChapterRepository chapters;
    private final TopicRepository topics;
    private final FeatureRepository features;
    private final IntakeFormRepository intakeForms;
    private final ProgressRepository progress;
    private final PasswordEncoder encoder;
    private final ActivityService activity;
    private final MailService mail;
    /* carried from the point it is generated to the point the response is written,
       and cleared either way. It is never written to the database. */
    private final ThreadLocal<String> lastPassword = new ThreadLocal<>();
    private final QuizQuestionRepository questions;
    private final OllamaService ai;
    private final CredentialService credentials;
    private final MentorAssignmentRepository mentorHistory;
    private final BatchRepository batches;
    private final MongoTemplate mongo;
    private final MentorHierarchyService hierarchyTree;

    /** Every role that is not a learner. Staff lists read these off the role index. */
    private static final List<User.Role> STAFF =
            List.of(User.Role.MENTOR, User.Role.ADMIN, User.Role.SUPER_ADMIN);

    /** Learners held by one mentor, counted off the mentorId index. */
    private long caseload(String mentorId) {
        return mongo.count(new Query(Criteria.where("mentorId").is(mentorId)), Learner.class);
    }

    public SuperAdminService(UserRepository users, LearnerRepository learners, ModuleRepository modules,
                             BundleRepository bundles, ChapterRepository chapters,
                             TopicRepository topics,
                             FeatureRepository features, IntakeFormRepository intakeForms,
                             ProgressRepository progress, PasswordEncoder encoder,
                             ActivityService activity, QuizQuestionRepository questions,
                             OllamaService ai, MailService mail, CredentialService credentials,
                             MentorAssignmentRepository mentorHistory, BatchRepository batches,
                             MongoTemplate mongo, MentorHierarchyService hierarchyTree) {
        this.mongo = mongo;
        this.hierarchyTree = hierarchyTree;
        this.mail = mail;
        this.mentorHistory = mentorHistory; this.batches = batches;
        this.users = users; this.learners = learners; this.modules = modules; this.bundles = bundles;
        this.chapters = chapters; this.topics = topics; this.features = features; this.intakeForms = intakeForms;
        this.progress = progress; this.encoder = encoder; this.activity = activity;
        this.questions = questions; this.ai = ai; this.credentials = credentials;
    }

    /* ---------------------------------------------------------------- catalogue */

    /* Module, course and chapter writes live in CatalogueService, which merges an edit
       onto the stored record. The whole-object saves that used to sit here had no
       caller and would have wiped fields on any future one, so they are gone. */

    public List<Map<String, Object>> catalogue() {
        return bundles.findAll().stream().map(b -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("name", b.getName());
            m.put("description", b.getDescription());
            m.put("price", b.getPrice());
            m.put("active", b.isActive());
            Map<String, CourseModule> byId = new HashMap<>();
            modules.findAllById(b.getModuleIds()).forEach(x -> byId.put(x.getId(), x));
            m.put("modules", b.getModuleIds().stream()
                    .map(byId::get)
                    .filter(Objects::nonNull)
                    .map(t -> Map.of("id", t.getId(), "name", t.getName(),
                            "chapters", chapters.countByModuleId(t.getId())))
                    .toList());
            /* a count off the (bundleId, ...) index, not a scan of every learner per course */
            m.put("enrolled", mongo.count(new Query(Criteria.where("bundleId").is(b.getId())), Learner.class));
            return m;
        }).collect(Collectors.toList());
    }

    /* ---------------------------------------------------------------- quiz drafting

       The model writes candidates, a person publishes them. Drafts are marked and are
       never served to a learner, so a bad question cannot reach a chapter test by
       accident.                                                                      */

    public List<QuizQuestion> draftQuiz(String chapterId, int count, String actorEmail) {
        Chapter c = chapters.findById(chapterId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such chapter."));
        String module = modules.findById(c.getModuleId()).map(CourseModule::getName).orElse("");

        var drafted = ai.completeJson(
                "You write multiple choice questions for a course chapter. Four options each, "
                + "exactly one correct, no trick wording, and test understanding rather than recall "
                + "of the wording.",
                "CourseModule: " + module + "\nChapter: " + c.getTitle()
                    + "\nTopics in it: " + topics.findByChapterIdOrderByPositionAsc(chapterId)
                        .stream().map(Topic::getTitle).collect(java.util.stream.Collectors.joining(", "))
                    + "\nBrief: " + (c.getAssignment().getBrief() == null
                        ? "" : c.getAssignment().getBrief())
                    + "\nWrite " + count + " questions. Return "
                    + "{\"questions\":[{\"prompt\":string,\"options\":[4 strings],"
                    + "\"correctIndex\":number,\"explanation\":string}]}",
                "QUIZ_DRAFT", null, chapterId);

        if (drafted.isEmpty() || !drafted.get().has("questions")) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The model did not return usable questions. Try again, or write them by hand.");
        }

        List<QuizQuestion> made = new ArrayList<>();
        for (var node : drafted.get().get("questions")) {
            List<String> options = new ArrayList<>();
            node.path("options").forEach(o -> options.add(o.asText()));
            if (options.size() != 4) continue;
            QuizQuestion q = new QuizQuestion();
            q.setChapterId(chapterId);
            q.setPrompt(node.path("prompt").asText());
            q.setOptions(options);
            q.setCorrectIndex(Math.max(0, Math.min(3, node.path("correctIndex").asInt())));
            q.setExplanation(node.path("explanation").asText(""));
            q.setDraft(true);
            made.add(questions.save(q));
        }
        activity.log(null, actorEmail, "DRAFT_QUIZ", "chapter",
                c.getTitle() + ": " + made.size() + " drafted by " + ai.modelName());
        return made;
    }

    public QuizQuestion publishQuestion(String questionId, QuizQuestion edits, String actorEmail) {
        QuizQuestion q = questions.findById(questionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such question."));
        if (edits != null) {
            if (edits.getPrompt() != null) q.setPrompt(edits.getPrompt());
            if (edits.getOptions() != null && edits.getOptions().size() == 4) q.setOptions(edits.getOptions());
            if (edits.getExplanation() != null) q.setExplanation(edits.getExplanation());
            q.setCorrectIndex(edits.getCorrectIndex());
        }
        /* publishing is the moment a question reaches learners, so it meets the same bar
           as one written by hand in the chapter editor */
        CatalogueService.validateQuestion(q);
        q.setDraft(false);
        activity.log(null, actorEmail, "PUBLISH_QUESTION", "chapter", q.getChapterId());
        return questions.save(q);
    }

    public void discardQuestion(String questionId, String actorEmail) {
        questions.deleteById(questionId);
        activity.log(null, actorEmail, "DISCARD_QUESTION", "question", questionId);
    }

    public List<QuizQuestion> questionsFor(String chapterId) {
        return questions.findByChapterId(chapterId);
    }

    /* ---------------------------------------------------------------- people */

    /**
     * A new staff account, and the one thing that has to happen with it.
     *
     * The password used to be generated, hashed, and then dropped on the floor: no mail,
     * no return value, nothing on screen. The account existed and nobody alive could sign
     * in to it. It is now derived from their name, mailed to them, and handed back once so
     * whoever created it can read it out when mail is off.
     *
     * Mentors and admins get the same scheme as learners on purpose. A second rule for
     * staff would mean a second thing to remember on the one occasion a year somebody
     * creates a mentor.
     */
    /** The stored account behind an id, so a caller can see what it is before changing it. */
    public User findStaff(String id) {
        return id == null ? null : users.findById(id).orElse(null);
    }

    public User saveStaff(User body, String plainPassword, String actorEmail) {
        if (body.getId() != null) {
            throw new IllegalStateException("Edits go through updateStaff, which merges.");
        }
        if (body.getEmail() == null || body.getEmail().isBlank()
                || body.getFullName() == null || body.getFullName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A name and an email are both needed.");
        }
        /* stored lower case, like every learner address, so exact lookups find it */
        body.setEmail(body.getEmail().trim().toLowerCase());
        if (users.existsByEmailIgnoreCase(body.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That email already has an account.");
        }
        body.setLoginId(credentials.loginIdFor(body.getFullName(), body.getEmail(), null));
        String pw = (plainPassword == null || plainPassword.isBlank())
                ? credentials.firstPasswordFor(body.getFullName(), body.getLoginId())
                : plainPassword;
        body.setPasswordHash(encoder.encode(pw));
        body.setMustChangePassword(true);
        body.setDefaultPasswordSetAt(Instant.now());
        User saved = users.save(body);
        hierarchyTree.invalidate();
        mail.send(saved.getEmail(), "Your ProITBridge account",
                "Hello " + saved.getFullName() + ",\n\n"
                + "An account has been created for you as a "
                + saved.getRole().name().toLowerCase().replace('_', ' ') + ".\n\n"
                + "Sign in at the team page with:\n"
                + credentials.credentialLines(saved, pw) + "\n"
                + "You will be asked to set your own password the first time.\n\n"
                + "Team ProITBridge", "STAFF_CREDENTIALS");
        /* handed back once, by consumeLastPassword, for the response that created it */
        lastPassword.set(pw);
        activity.log(null, actorEmail, "SAVE_STAFF", "user", saved.getEmail() + " " + saved.getRole());
        return saved;
    }

    /**
     * Editing a staff account changes what was sent and nothing else.
     *
     * The edit used to build a fresh User from the form and save it over the stored one,
     * so a rename wiped the phone numbers the form did not carry, the last sign in, the
     * created date and any suspension. Only the listed fields can be changed here, and
     * only when the request actually carries them. Switching an account off or on has its
     * own route with its own checks, so `active` is ignored.
     */
    public User updateStaff(String id, Map<String, Object> body, boolean roleMayChange, String actorEmail) {
        User u = users.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such account."));
        if (body.containsKey("fullName")) {
            String name = str(body.get("fullName"));
            if (name == null || name.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A name is needed.");
            }
            u.setFullName(name.trim());
        }
        if (body.containsKey("email")) {
            String email = str(body.get("email"));
            if (email == null || email.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An email is needed.");
            }
            email = email.trim().toLowerCase();
            if (!email.equalsIgnoreCase(u.getEmail()) && users.existsByEmailIgnoreCase(email)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "That email already has an account.");
            }
            u.setEmail(email);
        }
        if (body.containsKey("loginId")) {
            String login = str(body.get("loginId"));
            if (login != null && !login.isBlank() && !login.trim().equalsIgnoreCase(u.getLoginId())) {
                if (users.findByLoginIdIgnoreCase(login.trim()).isPresent()) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "That login ID is taken.");
                }
                u.setLoginId(login.trim().toLowerCase());
            }
        }
        if (body.containsKey("phone")) u.setPhone(Validate.phone(str(body.get("phone")), "Phone number"));
        if (body.containsKey("whatsapp")) u.setWhatsapp(Validate.phone(str(body.get("whatsapp")), "WhatsApp number"));
        if (body.containsKey("altPhone")) u.setAltPhone(Validate.phone(str(body.get("altPhone")), "Alternate number"));
        if (body.containsKey("reportsToId")) {
            String to = str(body.get("reportsToId"));
            to = to == null || to.isBlank() ? null : to;
            if (id.equals(to)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nobody reports to themselves.");
            }
            u.setReportsToId(to);
        }
        if (roleMayChange && body.get("role") != null) {
            User.Role role = User.Role.valueOf(String.valueOf(body.get("role")).toUpperCase());
            if (role == User.Role.SUPER_ADMIN || role == User.Role.LEARNER) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "A staff account can be a mentor or an admin from here.");
            }
            u.setRole(role);
        }
        if (u.getLoginId() == null || u.getLoginId().isBlank()) {
            u.setLoginId(credentials.loginIdFor(u.getFullName(), u.getEmail(), u.getId()));
        }
        User saved = users.save(u);
        hierarchyTree.invalidate();
        activity.log(null, actorEmail, "SAVE_STAFF", "user", saved.getEmail() + " " + saved.getRole());
        return saved;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    /**
     * Who may act on whom.
     *
     * An admin runs learners and mentors. Another admin, or a super admin, is not theirs
     * to switch off, reset or suspend: the admin routes used to take any id, so an admin
     * could lock out the super admin. A super admin may act on admins.
     */
    public User guardTarget(String targetId, String callerRole) {
        User t = users.findById(targetId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such account."));
        boolean callerSuper = "SUPER_ADMIN".equals(callerRole);
        if (!callerSuper && (t.getRole() == User.Role.ADMIN || t.getRole() == User.Role.SUPER_ADMIN)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "An admin account is managed by the super admin.");
        }
        return t;
    }

    /**
     * Switch a staff account off, or back on.
     *
     * Never a delete. Their name is on mentor history, activity entries and task reviews,
     * and removing the row would leave all of it pointing at nothing. Switched off, they
     * cannot sign in and cannot be picked as a mentor, but everything they did still
     * resolves to a person.
     *
     * A mentor still holding learners is refused rather than silently switched off,
     * because the learners would keep pointing at an account that can no longer answer
     * them and nobody would find out until one of them asked a question. Move them first;
     * `reassignLearners` does it in one call.
     */
    public Map<String, Object> setStaffActive(String userId, boolean active, String actorEmail) {
        User u = users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such account."));
        if (!active) {
            int held = (int) caseload(userId);
            if (held > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        held + (held == 1 ? " learner is" : " learners are")
                        + " still with this mentor. Move them to somebody else first.");
            }
            long others = users.findByRoleAndActiveTrue(User.Role.SUPER_ADMIN).stream()
                    .filter(x -> !x.getId().equals(userId))
                    .count();
            if (u.getRole() == User.Role.SUPER_ADMIN && others == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "This is the only active super admin. Switching it off would lock "
                        + "everybody out of the product.");
            }
        }
        u.setActive(active);
        users.save(u);
        hierarchyTree.invalidate();
        activity.log(null, actorEmail, active ? "ENABLE_STAFF" : "DISABLE_STAFF",
                "user", u.getEmail());
        return Map.of("id", u.getId(), "active", u.isActive());
    }

    /**
     * Move every learner from one mentor to another in one go.
     *
     * This is what you do before somebody leaves. Doing it learner by learner from the
     * register works, and with twelve learners it is twelve dialogs and one of them gets
     * missed.
     */
    public Map<String, Object> reassignLearners(String fromId, String toId, String reason,
                                                String actorEmail) {
        if (toId == null || toId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick the mentor to move them to.");
        }
        User to = users.findById(toId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such mentor."));
        /* learners are taught by mentors. An admin in this list used to be accepted and
           the learners then had nobody on the mentor desk */
        if (to.getRole() != User.Role.MENTOR || !to.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Pick an active mentor to move them to.");
        }
        if (fromId.equals(toId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That is the same mentor.");
        }
        String why = reason == null || reason.isBlank() ? "Moved with the rest of their mentor's learners" : reason;
        List<Learner> held = learners.findByMentorId(fromId);
        for (Learner l : held) {
            MentorAssignment h = new MentorAssignment();
            h.setLearnerId(l.getId());
            h.setFromMentorId(fromId);
            h.setToMentorId(toId);
            h.setReason(why);
            h.setActorEmail(actorEmail);
            mentorHistory.save(h);
            l.setMentorId(toId);
            learners.save(l);
        }
        /* a batch keeps its own mentor, which is who every new joiner is given. Left on
           the departing mentor, the next learner placed in it would land back with them */
        int batchesMoved = 0;
        for (Batch b : batches.findByMentorId(fromId)) {
            b.setMentorId(toId);
            batches.save(b);
            batchesMoved++;
        }
        activity.log(null, actorEmail, "REASSIGN_LEARNERS", "user",
                held.size() + " learners and " + batchesMoved + " batches to " + to.getEmail()
                        + (reason == null ? "" : " (" + reason + ")"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("moved", held.size());
        out.put("batches", batchesMoved);
        out.put("to", to.getFullName());
        return out;
    }

    /**
     * Put an account back to the password derived from its name.
     *
     * This is the button an admin presses when somebody messages saying they cannot get
     * in. It restarts the unused clock and forces a change again, so a reissue is no
     * weaker than the original.
     */
    public Map<String, Object> resetToDefault(String userId, String actorEmail) {
        User u = users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such account."));
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
        activity.log(null, actorEmail, "RESET_PASSWORD", "user", u.getEmail());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loginId", u.getLoginId());
        m.put("password", pw);
        m.put("expiresInDays", credentials.defaultPasswordDays());
        return m;
    }

    /** What the password was, for the one response that created it. Never stored. */
    /** True only for an existing account already holding the mentor role. Used to stop
     *  the admin mentor routes from reaching an admin or a super admin. */
    public boolean isMentor(String userId) {
        return users.findById(userId).map(u -> u.getRole() == User.Role.MENTOR).orElse(false);
    }

    public String consumeLastPassword() {
        String pw = lastPassword.get();
        lastPassword.remove();
        return pw;
    }

    /** Reporting is transitive: mentor to lead to CTO to CEO. */
    public List<Map<String, Object>> hierarchy() {
        /* staff only, by role off the index: this list never held learners, it just
           read all twenty thousand of them to throw them away */
        List<User> staff = users.findByRoleIn(STAFF);
        Map<String, String> nameOf = new HashMap<>();
        staff.forEach(u -> nameOf.put(u.getId(), u.getFullName()));
        return staff.stream().map(u -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("name", u.getFullName());
            m.put("email", u.getEmail());
            m.put("phone", u.getPhone());
            m.put("whatsapp", u.getWhatsapp());
            m.put("altPhone", u.getAltPhone());
            m.put("role", u.getRole());
            m.put("active", u.isActive());
            m.put("reportsTo", u.getReportsToId() == null ? null : nameOf.get(u.getReportsToId()));
            m.put("reportsToId", u.getReportsToId());
            m.put("learners", caseload(u.getId()));
            m.put("loginId", u.getLoginId());
            /* whether they have ever signed in: a staff account created weeks ago and
               never used is usually somebody who never got the mail */
            m.put("lastLoginAt", u.getLastLoginAt());
            m.put("neverSignedIn", u.getLastLoginAt() == null);
            return m;
        }).collect(Collectors.toList());
    }

    /** Every learner under a manager's whole tree, so a lead can review their teams' work. */
    public List<String> learnerIdsUnder(String userId) {
        Set<String> staff = new LinkedHashSet<>();
        collect(userId, staff);
        Query q = new Query(Criteria.where("mentorId").in(staff));
        q.fields().include("_id");
        return mongo.find(q, Learner.class).stream().map(Learner::getId).toList();
    }

    private void collect(String userId, Set<String> acc) {
        if (!acc.add(userId)) return;
        for (User child : users.findByReportsToId(userId)) {
            collect(child.getId(), acc);
        }
    }

    /* ---------------------------------------------------------------- toggles */

    public Feature saveFeature(String key, boolean forPremium, boolean forBatch, String actorEmail) {
        Feature f = features.findByKey(key)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown feature."));
        f.setForPremium(forPremium);
        f.setForBatch(forBatch);
        activity.log(null, actorEmail, "TOGGLE_FEATURE", "feature",
                key + " premium=" + forPremium + " batch=" + forBatch);
        return features.save(f);
    }

    /* ---------------------------------------------------------------- deletion */

    /** Deletion sits here and nowhere else. It takes the learner record with it. */
    /**
     * Removing a learner switches their account off. It used to delete the user, the
     * learner, their form and their progress, and left every task, project, booking,
     * attendance row and payment-adjacent record pointing at nobody. Nothing is wiped
     * now: they cannot sign in, they drop out of the active lists, and switching them
     * back on restores everything as it was.
     */
    public Map<String, Object> setLearnerActive(String learnerId, boolean active, String actorEmail) {
        Learner l = learners.findById(learnerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Learner not found."));
        User u = users.findById(l.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No account for this learner."));
        u.setActive(active);
        users.save(u);
        activity.log(null, actorEmail, active ? "RESTORE_LEARNER" : "DEACTIVATE_LEARNER",
                "learner", u.getEmail());
        return Map.of("learnerId", learnerId, "userId", u.getId(), "active", active);
    }
}

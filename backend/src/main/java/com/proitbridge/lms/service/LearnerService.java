package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class LearnerService {

    private final UserRepository users;
    private final LearnerRepository learners;
    private final BatchRepository batches;
    private final BundleRepository bundles;
    private final ModuleRepository modules;
    private final ChapterRepository chapters;
    private final TopicRepository topics;
    private final ProgressRepository progress;
    private final QuizQuestionRepository quizQuestions;
    private final QuizAttemptRepository quizAttempts;
    private final AssignmentRepository assignments;
    private final AssignmentService tasks;
    private final ProjectWorkRepository projects;
    private final IntakeFormRepository intakeForms;
    private final ResumeVersionRepository resumes;
    private final SlotRepository slots;
    private final BookingRepository bookings;
    private final ProgressCallRepository calls;
    private final MockRequestRepository mocks;
    private final AnnouncementRepository announcements;
    private final JobPostRepository jobs;
    private final CaseStudyRepository caseStudies;
    private final FeatureService featureService;
    private final OllamaService ai;
    private final ModuleRepository moduleRepo;
    private final RecordingRepository recordingRepo;
    private final MeetingService meetings;
    private final ChapterNoteRepository notes;
    private final SettingsService settings;
    private final ResourceService resourceService;
    private final RecordingService recordingService;
    private final WatchService watch;
    private final FormSectionRepository formSections;
    private final FormFieldRepository formFields;
    private final FileService fileService;

    public LearnerService(UserRepository users, LearnerRepository learners, BatchRepository batches,
                          BundleRepository bundles, ModuleRepository modules, ChapterRepository chapters,
                          TopicRepository topics,
                          ProgressRepository progress, QuizQuestionRepository quizQuestions,
                          QuizAttemptRepository quizAttempts, AssignmentRepository assignments,
                          ProjectWorkRepository projects, IntakeFormRepository intakeForms,
                          ResumeVersionRepository resumes, SlotRepository slots,
                          BookingRepository bookings, ProgressCallRepository calls,
                          MockRequestRepository mocks, AnnouncementRepository announcements,
                          JobPostRepository jobs, CaseStudyRepository caseStudies,
                          FeatureService featureService, OllamaService ai,
                          RecordingRepository recordingRepo, MeetingService meetings,
                          ChapterNoteRepository notes, SettingsService settings,
                          ResourceService resourceService, RecordingService recordingService,
                          WatchService watch, FormSectionRepository formSections,
                          FormFieldRepository formFields, FileService fileService,
                          AssignmentService tasks) {
        this.users = users; this.learners = learners; this.batches = batches; this.bundles = bundles;
        this.modules = modules; this.chapters = chapters; this.topics = topics;
        this.progress = progress;
        this.quizQuestions = quizQuestions; this.quizAttempts = quizAttempts;
        this.assignments = assignments; this.tasks = tasks;
        this.projects = projects; this.intakeForms = intakeForms;
        this.resumes = resumes; this.slots = slots; this.bookings = bookings; this.calls = calls;
        this.mocks = mocks; this.announcements = announcements; this.jobs = jobs;
        this.caseStudies = caseStudies; this.featureService = featureService;
        this.ai = ai; this.moduleRepo = modules;
        this.recordingRepo = recordingRepo; this.meetings = meetings; this.notes = notes;
        this.settings = settings; this.resourceService = resourceService;
        this.recordingService = recordingService; this.watch = watch;
        this.formSections = formSections;
        this.formFields = formFields;
        this.fileService = fileService;
    }

    public Learner require(String userId) {
        return learners.findByUserId(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "This account is not a learner account."));
    }

    /* ---------------------------------------------------------------- dashboard */

    /** The walkthrough, whatever an admin has pointed the setting at. */
    private Map<String, Object> prereqVideo() {
        String ref = settings.get("onboarding.prereqVideoRef", "");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", settings.get("onboarding.prereqTitle", "How this LMS works"));
        m.put("videoRef", ref);
        m.put("set", !ref.isBlank());
        return m;
    }

    public Map<String, Object> dashboard(String userId) {
        Learner l = require(userId);
        User u = users.findById(l.getUserId()).orElseThrow();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("learner", learnerView(l, u));
        out.put("gates", gates(l));
        out.put("features", featureService.resolve(l));
        out.put("roadmap", roadmap(l));
        out.put("stats", stats(l));
        out.put("prereqVideo", prereqVideo());
        /* started and not finished, so the roadmap can offer it back */
        out.put("continueWatching", watch.continueWatching(l.getId(), 4));
        if (l.getTrackType() == Learner.TrackType.BATCH && l.getBatchId() != null) {
            out.put("batch", batchView(l));
        }
        return out;
    }

    public Map<String, Object> learnerView(Learner l, User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("name", u.getFullName());
        m.put("email", u.getEmail());
        m.put("trackType", l.getTrackType());
        m.put("bundle", l.getBundleId() == null ? null :
                bundles.findById(l.getBundleId()).map(Bundle::getName).orElse(null));
        m.put("mentor", l.getMentorId() == null ? null :
                users.findById(l.getMentorId()).map(User::getFullName).orElse(null));
        m.put("mentorEmail", l.getMentorId() == null ? null :
                users.findById(l.getMentorId()).map(User::getEmail).orElse(null));
        m.put("joinedOn", l.getJoinedOn());
        m.put("whatsappGroupLink", l.getWhatsappGroupLink());
        m.put("guideVideoWatched", l.isGuideVideoWatched());
        m.put("modulesUnlocked", l.modulesUnlocked(inductionCounts(l)));
        return m;
    }

    /** Whether the induction blocks this learner, which is the induction_gate toggle. */
    private boolean inductionCounts(Learner l) {
        /* an install that predates this key must keep gating, not silently stop */
        return featureService.allowedUnlessConfiguredOff(l, "induction_gate");
    }

    public Map<String, Object> gates(Learner l) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("form", l.isGateFormDone());
        g.put("prereq", l.isGatePrereqDone());
        g.put("call", l.isGateCallDone());
        g.put("induction", l.getTrackType() == Learner.TrackType.BATCH ? l.isInductionWatched() : null);
        g.put("allDone", l.gatesCleared(inductionCounts(l)));
        /* when onboarding is not enforced these are a checklist rather than a wall, and
           the rail has to say so or it reads as broken */
        g.put("enforced", com.proitbridge.lms.domain.GatePolicy.onboardingRequired());
        return g;
    }

    /* ---------------------------------------------------------------- roadmap */

    /**
     * The roadmap is visible from first login so the learner can see what is ahead,
     * but nothing is playable until every gate is cleared. Modules run in bundle order,
     * In the order the course sets, and each opens when the one before it is finished.
     */
    public List<Map<String, Object>> roadmap(Learner l) {
        Bundle bundle = l.getBundleId() == null ? null : bundles.findById(l.getBundleId()).orElse(null);
        if (bundle == null) return List.of();
        Map<String, Progress> byChapter = progress.findByLearnerId(l.getId()).stream()
                .collect(Collectors.toMap(Progress::getChapterId, p -> p, (a, b) -> a));
        boolean gatesCleared = l.modulesUnlocked(inductionCounts(l));
        boolean previousComplete = true;
        List<Map<String, Object>> out = new ArrayList<>();

        for (String moduleId : bundle.getModuleIds()) {
            CourseModule t = modules.findById(moduleId).orElse(null);
            if (t == null) continue;
            List<Chapter> chs = chapters.findByModuleIdOrderByPositionAsc(moduleId);
            boolean moduleOpen = gatesCleared && previousComplete;
            int done = 0;
            List<Map<String, Object>> chapterViews = new ArrayList<>();
            boolean prevChapterDone = true;
            for (Chapter c : chs) {
                Progress p = byChapter.get(c.getId());
                List<Topic> ts = topics.findByChapterIdOrderByPositionAsc(c.getId()).stream()
                        .filter(Topic::isActive).toList();
                boolean complete = chapterComplete(c, p, ts);
                if (complete) done++;

                /* topics inside a chapter open in order too, but only against each
                   other. Nothing inside a chapter waits on anything outside it. */
                List<Map<String, Object>> topicViews = new ArrayList<>();
                boolean prevTopicDone = true;
                for (Topic tp : ts) {
                    boolean seen = p != null && p.getWatchedTopicIds().contains(tp.getId());
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", tp.getId());
                    item.put("title", tp.getTitle());
                    item.put("position", tp.getPosition());
                    item.put("durationMin", tp.getDurationMin());
                    item.put("watched", seen);
                    item.put("locked", !(moduleOpen && prevChapterDone && prevTopicDone));
                    topicViews.add(item);
                    prevTopicDone = seen;
                }

                Map<String, Object> cv = new LinkedHashMap<>();
                cv.put("id", c.getId());
                cv.put("title", c.getTitle());
                cv.put("position", c.getPosition());
                cv.put("durationMin", ts.stream().mapToInt(Topic::getDurationMin).sum());
                cv.put("topics", topicViews);
                cv.put("topicsWatched", p == null ? 0 : (int) ts.stream()
                        .filter(x -> p.getWatchedTopicIds().contains(x.getId())).count());
                cv.put("watched", p != null && p.isWatched());
                cv.put("hasTest", c.getTest().isEnabled());
                cv.put("hasTeachback", c.getTeachback().isEnabled());
                cv.put("hasAssignment", c.getAssignment().isEnabled());
                cv.put("quizScore", p == null ? null : p.getQuizScore());
                cv.put("conceptCheckScore", p == null ? null : p.getConceptCheckScore());
                cv.put("taskStatus", p == null ? "NOT_STARTED" : p.getTaskStatus());
                cv.put("complete", complete);
                cv.put("locked", !(moduleOpen && prevChapterDone));
                chapterViews.add(cv);
                prevChapterDone = complete;
            }
            Map<String, Object> tv = new LinkedHashMap<>();
            tv.put("moduleId", t.getId());
            tv.put("code", t.getCode());
            tv.put("name", t.getName());
            tv.put("slug", t.getSlug());
            tv.put("chapters", chapterViews);
            tv.put("total", chs.size());
            tv.put("done", done);
            tv.put("locked", !moduleOpen);
            tv.put("lockReason", !gatesCleared ? "Finish onboarding to open your modules."
                    : (!previousComplete ? "Opens when the module before it is complete." : null));
            out.add(tv);
            previousComplete = previousComplete && !chs.isEmpty() && done == chs.size();
        }
        return out;
    }

    /**
     * A chapter is finished when every topic in it has been watched and the three things
     * that are switched on have been done. A chapter with no topics is never finished,
     * because an empty chapter is a content gap, not a free pass.
     */
    private boolean chapterComplete(Chapter c, Progress p, List<Topic> ts) {
        if (p == null || ts.isEmpty()) return false;
        for (Topic t : ts) {
            if (!p.getWatchedTopicIds().contains(t.getId())) return false;
        }
        /* a score is not a pass: the chapter's own pass mark is what opens the next one */
        if (c.getTest().isEnabled()
                && (p.getQuizScore() == null || p.getQuizScore() < c.getTest().getPassMark())) return false;
        /*
         * An ungraded answer still counts, because the learner did the work and the model
         * being down is not their fault. It is flagged pending so a mentor can see it.
         */
        if (c.getTeachback().isEnabled()
                && p.getConceptCheckScore() == null && !p.isConceptCheckPending()) return false;
        /*
         * lockNext, finally read.
         *
         * It sat on the chapter, editable, consulted by nothing, while this line blocked
         * the next chapter on an approved task regardless — so the switch was a lie in
         * both directions. With it off, handing the work in is enough to move on and the
         * mentor marks it in their own time; waiting days on a review queue to carry on
         * studying is the wrong incentive to build in.
         */
        if (c.getAssignment().isEnabled()) {
            String st = p.getTaskStatus();
            if (c.getAssignment().isLockNext()) {
                if (!"APPROVED".equals(st)) return false;
            } else if (!"APPROVED".equals(st) && !"SUBMITTED".equals(st) && !"CHANGES".equals(st)) {
                return false;
            }
        }
        return true;
    }

    public Map<String, Object> stats(Learner l) {
        List<Progress> ps = progress.findByLearnerId(l.getId());
        Bundle bundle = l.getBundleId() == null ? null : bundles.findById(l.getBundleId()).orElse(null);
        long totalChapters = bundle == null ? 0
                : chapters.findByModuleIdIn(bundle.getModuleIds()).size();
        long watched = ps.stream().filter(Progress::isWatched).count();
        double avgQuiz = ps.stream().filter(p -> p.getQuizScore() != null)
                .mapToDouble(Progress::getQuizScore).average().orElse(0);
        long tasksApproved = ps.stream().filter(p -> "APPROVED".equals(p.getTaskStatus())).count();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalChapters", totalChapters);
        m.put("watched", watched);
        /*
         * Ints, deliberately.
         *
         * Math.round(double) returns a long, so these two went into the map as Longs
         * while every reader cast them to Integer. That is a ClassCastException, and it
         * took out the whole mentor roster: one learner with any progress and the
         * endpoint 500s, so a mentor saw none of their learners rather than some of them.
         * The same cast sits behind the leaderboard and the rank card.
         */
        m.put("percent", totalChapters == 0 ? 0 : (int) Math.round(watched * 100.0 / totalChapters));
        m.put("avgQuiz", (int) Math.round(avgQuiz));
        m.put("tasksApproved", tasksApproved);
        m.put("projectsApproved", projects.countByLearnerIdAndStatus(l.getId(), "APPROVED"));
        return m;
    }

    /* ---------------------------------------------------------------- onboarding */

    public void markGuideWatched(String userId) {
        Learner l = require(userId);
        l.setGuideVideoWatched(true);
        learners.save(l);
    }

    public void markPrereqWatched(String userId) {
        Learner l = require(userId);
        l.setGatePrereqDone(true);
        learners.save(l);
    }

    public void markInductionWatched(String userId) {
        Learner l = require(userId);
        if (l.getTrackType() != Learner.TrackType.BATCH) return;
        l.setInductionWatched(true);
        l.setGateCallDone(true);
        learners.save(l);
    }

    public IntakeForm intake(String userId) {
        Learner l = require(userId);
        return intakeForms.findByLearnerId(l.getId()).orElseGet(() -> {
            IntakeForm f = new IntakeForm();
            f.setLearnerId(l.getId());
            return intakeForms.save(f);
        });
    }

    /**
     * The questions, as configured.
     *
     * The learner form used to have every question written into it as JSX, so what it
     * asked could only be changed by shipping code. It renders from these instead. Only
     * active ones are sent: a question switched off stops being asked without losing the
     * answers already given to it.
     */
    public List<FormField> formFields() {
        return formFields.findByActiveTrueOrderByPositionAsc();
    }

    /** Each section saves on its own so a half filled form is never lost. */
    public IntakeForm saveSection(String userId, String section, Map<String, Object> payload) {
        Learner l = require(userId);
        if (formSections.findByKey(section).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown form section: " + section);
        }
        IntakeForm f = intake(userId);
        f.getSections().put(section, payload);
        f.getCompletedSections().add(section);
        if ("professional".equals(section)) {
            Object type = payload.get("profileType");
            if (type != null) f.setProfileType(String.valueOf(type));
        }
        f.setUpdatedAt(Instant.now());
        /* only the required ones gate the form, so adding an optional section later
           never un-finishes somebody who already got through */
        Set<String> needed = formSections.findByActiveTrueOrderByPositionAsc().stream()
                .filter(FormSection::isRequired)
                .map(FormSection::getKey)
                .collect(Collectors.toSet());
        if (f.getCompletedSections().containsAll(needed)) {
            if (f.getSubmittedAt() == null) f.setSubmittedAt(Instant.now());
            l.setGateFormDone(true);
            learners.save(l);
        }
        return intakeForms.save(f);
    }

    /* ---------------------------------------------------------------- chapter flow */

    /**
     * The chapter a learner opens: the topics to work through, then the test, the teach
     * back and the assignment.
     *
     * The video is not here. A chapter groups several of them and each one is fetched by
     * its own call, which is also what keeps the grant flow honest: one play, one
     * entitlement check, one logged id.
     */
    /**
     * May this learner open this chapter's material?
     *
     * One answer to the question, so the file guard and the chapter page cannot come to
     * different conclusions. Onboarding must be cleared and the chapter must sit in a
     * module their course actually contains: a learner is entitled to the library they
     * paid for and not to the rest of it.
     */
    public boolean canOpenChapter(String userId, String chapterId) {
        Learner l = learners.findByUserId(userId).orElse(null);
        if (l == null || chapterId == null) return false;
        if (!l.modulesUnlocked(inductionCounts(l))) return false;
        Chapter c = chapters.findById(chapterId).orElse(null);
        if (c == null || c.getModuleId() == null) return false;
        return bundles.findById(l.getBundleId() == null ? "" : l.getBundleId())
                .map(b -> b.getModuleIds() != null && b.getModuleIds().contains(c.getModuleId()))
                .orElse(false);
    }

    public Map<String, Object> chapter(String userId, String chapterId) {
        Learner l = require(userId);
        Chapter c = chapters.findById(chapterId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chapter not found."));
        if (!l.modulesUnlocked(inductionCounts(l))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Finish onboarding to open your modules.");
        }
        Progress p = progressFor(l, c);
        List<Topic> ts = topics.findByChapterIdOrderByPositionAsc(chapterId).stream()
                .filter(Topic::isActive).toList();

        /* a LinkedHashMap, not Map.of: that caps at ten pairs, and this payload was
           sitting exactly on the limit, so the next field added would not compile */
        Map<String, Object> m = new LinkedHashMap<>();

        Map<String, Object> chapterView = new LinkedHashMap<>();
        chapterView.put("id", c.getId());
        chapterView.put("title", c.getTitle());
        chapterView.put("summary", c.getSummary() == null ? "" : c.getSummary());
        chapterView.put("position", c.getPosition());
        chapterView.put("moduleId", c.getModuleId());
        chapterView.put("moduleName", moduleRepo.findById(c.getModuleId())
                .map(CourseModule::getName).orElse(""));
        chapterView.put("durationMin", ts.stream().mapToInt(Topic::getDurationMin).sum());
        chapterView.put("hasQuiz", c.getTest().isEnabled());
        chapterView.put("hasConceptCheck", c.getTeachback().isEnabled());
        chapterView.put("hasTask", c.getAssignment().isEnabled());
        chapterView.put("passMark", c.getTest().getPassMark());
        chapterView.put("attemptsAllowed", c.getTest().getAttempts());
        /*
         * How many are left, so the learner is told before they sit it rather than after.
         * A limit nobody can see is a trap, not a rule.
         */
        int used = quizAttempts.findByLearnerIdAndChapterId(l.getId(), chapterId).size();
        boolean testPassed = p.getQuizScore() != null
                && p.getQuizScore() >= c.getTest().getPassMark();
        chapterView.put("testPassed", testPassed);
        chapterView.put("attemptsLeft", testPassed ? 0
                : Math.max(0, c.getTest().getAttempts() - used));
        chapterView.put("teachbackPrompt", c.getTeachback().getPrompt() == null
                ? "" : c.getTeachback().getPrompt());
        m.put("chapter", chapterView);

        Map<String, Object> asg = new LinkedHashMap<>();
        asg.put("enabled", c.getAssignment().isEnabled());
        asg.put("title", c.getAssignment().getTitle());
        asg.put("brief", c.getAssignment().getBrief() == null ? "" : c.getAssignment().getBrief());
        asg.put("dueDays", c.getAssignment().getDueDays());
        asg.put("marks", c.getAssignment().getMarks());
        asg.put("allow", c.getAssignment().getAllow());
        asg.put("resubmit", c.getAssignment().isResubmit());
        asg.put("lockNext", c.getAssignment().isLockNext());
        asg.put("fileIds", c.getAssignment().getFileIds());
        /* resolved, so a button can say "rubric.pdf, 240 KB" rather than "Download" */
        asg.put("files", fileService.viewsOf(c.getAssignment().getFileIds()));
        /* the real date, not a number of days the learner has to add up themselves */
        Assignment openRow = c.getAssignment().isEnabled() ? openAssignment(l, c) : null;
        asg.put("dueAt", openRow == null ? null : openRow.getDueAt());
        m.put("assignmentBrief", asg);

        /* the list to work through, each one locked behind the one before it */
        List<Map<String, Object>> topicViews = new ArrayList<>();
        boolean prevDone = true;
        for (Topic t : ts) {
            boolean seen = p.getWatchedTopicIds().contains(t.getId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", t.getId());
            item.put("title", t.getTitle());
            item.put("summary", t.getSummary() == null ? "" : t.getSummary());
            item.put("position", t.getPosition());
            item.put("durationMin", t.getDurationMin());
            item.put("codeRunner", t.getCodeRunner());
            item.put("watched", seen);
            item.put("resources", resourceService.countFor(t.getId()));
            item.put("locked", !prevDone);
            topicViews.add(item);
            prevDone = seen;
        }
        m.put("topics", topicViews);
        m.put("topicsWatched", topicViews.stream().filter(t -> (Boolean) t.get("watched")).count());

        m.put("progress", p);
        m.put("questions", c.getTest().isEnabled() ? quizQuestions.findByChapterId(chapterId).stream()
                .filter(q -> !q.isDraft())          // drafts are never served to a learner
                .map(q -> Map.of("id", q.getId(), "prompt", q.getPrompt(), "options", q.getOptions()))
                .toList() : List.of());
        m.put("attempts", quizAttempts.findByLearnerIdAndChapterId(l.getId(), chapterId));
        m.put("assignment", c.getAssignment().isEnabled() ? openAssignment(l, c) : null);
        return m;
    }

    /**
     * The task exists from the moment the chapter opens, not from the moment it is
     * handed in.
     *
     * It used to be created by the submit call, which meant a configured assignment was
     * invisible until a learner had already done it: nothing on their Projects and tasks
     * page, nothing in any pending count, and no due date for the at-risk sweep to read.
     * Opening the chapter is what sets the clock running, so this is where the row is
     * written, once, at ASSIGNED.
     */
    private Assignment openAssignment(Learner l, Chapter c) {
        return assignments
                .findFirstByLearnerIdAndChapterIdOrderBySubmittedAtDesc(l.getId(), c.getId())
                .orElseGet(() -> {
                    Chapter.AssignmentSpec spec = c.getAssignment();
                    Assignment a = new Assignment();
                    a.setLearnerId(l.getId());
                    a.setChapterId(c.getId());
                    a.setTitle(spec.getTitle() == null || spec.getTitle().isBlank()
                            ? c.getTitle() + " assignment" : spec.getTitle());
                    a.setBrief(spec.getBrief());
                    a.setOrigin("CHAPTER");
                    a.setStatus("ASSIGNED");
                    /* carried onto the row, so the mentor marks against the rubric the
                       chapter named rather than against nothing */
                    a.setRubricId(spec.getRubricId());
                    a.setMaxMarks(spec.getMarks());
                    a.setSubmittedAt(null);
                    a.setDueAt(Instant.now().plus(Math.max(1, spec.getDueDays()), ChronoUnit.DAYS));
                    return assignments.save(a);
                });
    }

    /**
     * One topic: the video reference and everything attached to it.
     *
     * Ordering is enforced here and not only in the roadmap, because a locked topic that
     * is merely greyed out in the UI is not locked.
     */
    public Map<String, Object> topic(String userId, String topicId) {
        Learner l = require(userId);
        Topic t = topics.findById(topicId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Topic not found."));
        if (!l.modulesUnlocked(inductionCounts(l))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Finish onboarding to open your modules.");
        }
        Chapter c = chapters.findById(t.getChapterId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Chapter not found."));
        Progress p = progressFor(l, c);

        for (Topic sibling : topics.findByChapterIdOrderByPositionAsc(c.getId())) {
            if (sibling.getId().equals(topicId)) break;
            if (sibling.isActive() && !p.getWatchedTopicIds().contains(sibling.getId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Finish " + sibling.getTitle() + " first.");
            }
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", t.getId());
        view.put("title", t.getTitle());
        view.put("summary", t.getSummary() == null ? "" : t.getSummary());
        view.put("position", t.getPosition());
        view.put("durationMin", t.getDurationMin());
        view.put("codeRunner", t.getCodeRunner());
        // the reference, never a provider id or a URL
        view.put("videoRef", t.getVideoRef() == null ? "" : t.getVideoRef());
        view.put("watched", p.getWatchedTopicIds().contains(t.getId()));

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("topic", view);
        m.put("chapterId", c.getId());
        m.put("chapterTitle", c.getTitle());
        /* everything the teacher attached: video, notes, notebooks, datasets, links */
        m.put("resources", resourceService.viewFor(t.getId(), l.getTrackType().name(), true));
        /* a recap or doubt session about this topic belongs here, where it is needed,
           not only in the library where it has to be hunted for */
        m.put("sessionRecordings", recordingService.forChapter(c.getId(), l));
        m.put("progress", p);
        return m;
    }

    private Progress progressFor(Learner l, Chapter c) {
        return progress.findByLearnerIdAndChapterId(l.getId(), c.getId()).orElseGet(() -> {
            Progress p = new Progress();
            p.setLearnerId(l.getId());
            p.setChapterId(c.getId());
            p.setModuleId(c.getModuleId());
            return progress.save(p);
        });
    }

    /**
     * Watching is recorded against the topic. The chapter's own watched flag is set only
     * when every topic in it is done, so nothing downstream has to count topics to find
     * out whether a chapter has been sat through.
     */
    public Progress markWatched(String userId, String topicId) {
        Learner l = require(userId);
        Topic t = topics.findById(topicId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Topic not found."));
        Chapter c = chapters.findById(t.getChapterId()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Chapter not found."));
        Progress p = progressFor(l, c);
        p.getWatchedTopicIds().add(topicId);

        List<Topic> ts = topics.findByChapterIdOrderByPositionAsc(c.getId()).stream()
                .filter(Topic::isActive).toList();
        p.setWatched(!ts.isEmpty() && ts.stream()
                .allMatch(x -> p.getWatchedTopicIds().contains(x.getId())));
        p.setUpdatedAt(Instant.now());
        return progress.save(p);
    }

    /**
     * The chapter test, and the rules that were sitting on the chapter doing nothing.
     *
     * Pass mark and attempt limit were both editable per chapter and neither was read.
     * A learner could sit the test any number of times, and a score of zero completed the
     * chapter and opened the next one, because completion only asked whether a score
     * existed. The test was decorative.
     *
     * The best attempt is what counts, not the first. Keeping the first was the harsher
     * half of two policies at once: the score was frozen on a bad day and nothing stopped
     * the learner moving on anyway. If a retake is allowed at all then it has to be able
     * to change the outcome, otherwise it is busywork.
     */
    public Map<String, Object> submitQuiz(String userId, String chapterId, Map<String, Integer> answers) {
        Learner l = require(userId);
        Chapter c = chapters.findById(chapterId).orElseThrow();
        List<QuizQuestion> qs = quizQuestions.findByChapterId(chapterId).stream()
                .filter(q -> !q.isDraft()).toList();
        if (qs.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This chapter has no test.");

        List<QuizAttempt> already = quizAttempts.findByLearnerIdAndChapterId(l.getId(), chapterId);
        int limit = Math.max(1, c.getTest().getAttempts());
        int passMark = c.getTest().getPassMark();
        boolean alreadyPassed = already.stream()
                .anyMatch(a -> a.getScore() >= passMark);
        if (!alreadyPassed && already.size() >= limit) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You have used all " + limit + " attempts on this test. Ask your mentor to reopen it.");
        }
        if (alreadyPassed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "You have already passed this test.");
        }

        int correct = 0;
        List<Map<String, Object>> review = new ArrayList<>();
        for (QuizQuestion q : qs) {
            Integer given = answers.get(q.getId());
            boolean ok = given != null && given == q.getCorrectIndex();
            if (ok) correct++;
            review.add(Map.of("id", q.getId(), "correctIndex", q.getCorrectIndex(),
                    "given", given == null ? -1 : given, "correct", ok,
                    "explanation", q.getExplanation() == null ? "" : q.getExplanation()));
        }
        double score = Math.round(correct * 100.0 / qs.size());

        int attemptNo = already.size() + 1;
        QuizAttempt a = new QuizAttempt();
        a.setLearnerId(l.getId());
        a.setChapterId(chapterId);
        a.setAttemptNo(attemptNo);
        a.setScore(score);
        a.setAnswers(answers);
        quizAttempts.save(a);

        Progress p = progressFor(l, c);
        if (p.getQuizScore() == null || score > p.getQuizScore()) p.setQuizScore(score);
        p.setUpdatedAt(Instant.now());
        progress.save(p);

        boolean passed = score >= passMark;
        int left = passed ? 0 : Math.max(0, limit - attemptNo);
        return Map.of("score", score, "attemptNo", attemptNo,
                "recordedScore", p.getQuizScore(), "review", review,
                "passMark", passMark, "passed", passed, "attemptsLeft", left,
                "note", passed ? "Passed. This chapter's test is done."
                        : left > 0
                            ? "Below " + passMark + ". " + left + " attempt" + (left > 1 ? "s" : "") + " left."
                            : "Below " + passMark + " and no attempts left. Your mentor can reopen it.");
    }

    /**
     * A mentor reopening a test.
     *
     * Attempts have to be exhaustible for the limit to mean anything, and a learner who
     * has run out has to have somewhere to go that is not a support ticket. Clearing the
     * attempts is deliberate rather than raising the cap: the record of what they scored
     * stays in the mentor's view, and the next attempt starts the count again.
     */
    public Map<String, Object> reopenTest(String learnerId, String chapterId) {
        List<QuizAttempt> rows = quizAttempts.findByLearnerIdAndChapterId(learnerId, chapterId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "There is nothing to reopen.");
        }
        quizAttempts.deleteAll(rows);
        progress.findByLearnerIdAndChapterId(learnerId, chapterId).ifPresent(p -> {
            p.setQuizScore(null);
            p.setUpdatedAt(Instant.now());
            progress.save(p);
        });
        return Map.of("cleared", rows.size());
    }

    /**
     * Teach back. The model reads the explanation against the chapter and returns a
     * score with one thing to fix. If the model is unreachable the length rule takes
     * over, because a learner should never be stuck mid chapter waiting on a server.
     */
    public Map<String, Object> conceptCheck(String userId, String chapterId, String explanation) {
        Learner l = require(userId);
        featureService.require(l, "ai_teach_back", "Teach back is not part of your programme.");
        Chapter c = chapters.findById(chapterId).orElseThrow();
        String text = explanation == null ? "" : explanation.trim();

        Double score = null;
        String verdict = null;
        boolean modelUsed = false;

        var judged = ai.completeJson(
                "You grade a learner explaining a concept back in their own words. "
                + "Be fair and specific. Score 0 to 100 on whether they show understanding, "
                + "not on wording or length.",
                "Chapter: " + c.getTitle()
                    + "\nModule: " + moduleRepo.findById(c.getModuleId()).map(CourseModule::getName).orElse("")
                    + "\nLearner explanation: " + text
                    + "\nReturn {\"score\": number, \"verdict\": string, \"fix\": string} "
                    + "where verdict is one short sentence and fix is the single most useful "
                    + "thing they should add.",
                "TEACH_BACK", l.getId(), chapterId);

        if (judged.isPresent() && judged.get().has("score")) {
            score = Math.max(0, Math.min(100, judged.get().get("score").asDouble()));
            verdict = judged.get().path("verdict").asText("");
            String fix = judged.get().path("fix").asText("");
            if (!fix.isBlank()) verdict = verdict + " " + fix;
            modelUsed = true;
        }

        Progress p = progressFor(l, c);
        if (modelUsed) {
            p.setConceptCheckScore(score);
            p.setConceptCheckPending(false);
        } else {
            /*
             * The model is unreachable, so nothing is graded.
             *
             * This used to fall back to a word count: eighty words of anything scored a
             * hundred, and the only trace was a "rubric" string in the response that no
             * screen displayed. A restart or a busy GPU quietly turned the teach back
             * into a length check, and the mentor could not tell an AI-graded 85 from a
             * counted one.
             *
             * The answer is kept and the chapter still opens on it, because a learner
             * should never be stuck mid chapter waiting on a server. It is simply held
             * ungraded until the model can read it, which is honest about what happened.
             */
            p.setConceptCheckScore(null);
            p.setConceptCheckPending(true);
            verdict = "Saved. The assistant could not be reached, so this is waiting to be "
                    + "read rather than scored. Your mentor can see it in the meantime.";
        }
        p.setConceptCheckAnswer(text);
        p.setUpdatedAt(Instant.now());
        progress.save(p);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", score);
        out.put("verdict", verdict);
        out.put("pending", !modelUsed);
        out.put("gradedBy", modelUsed ? ai.modelName() : null);
        return out;
    }

    /**
     * Doubt block. Grounded in this chapter alone and told to refuse anything else,
     * so it stays a study aid rather than a general chatbot, and every exchange is
     * kept so mentors can see where the cohort is actually stuck.
     */
    public Map<String, Object> askDoubt(String userId, String chapterId, String question) {
        Learner l = require(userId);
        Chapter c = chapters.findById(chapterId).orElseThrow();
        if (!l.modulesUnlocked(inductionCounts(l))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Finish onboarding to open your modules.");
        }
        String module = moduleRepo.findById(c.getModuleId()).map(CourseModule::getName).orElse("");
        var answer = ai.complete(
                "You help a learner with one chapter of a course. Answer only what relates to "
                + "this chapter. If the question is outside it, say so in one line and suggest "
                + "they raise it in the doubt clearing session. Never invent course policy. "
                + "Keep it under 120 words.",
                "Chapter: " + c.getTitle() + "\nModule: " + module
                    + "\nTopics in it: " + topics.findByChapterIdOrderByPositionAsc(chapterId)
                        .stream().map(Topic::getTitle).collect(Collectors.joining(", "))
                    + "\nBrief: " + (c.getAssignment().getBrief() == null
                        ? "" : c.getAssignment().getBrief())
                    + "\nQuestion: " + question,
                "DOUBT", l.getId(), chapterId);

        return answer
                .map(a -> Map.<String, Object>of("answer", a, "model", ai.modelName(), "available", true))
                .orElse(Map.of("answer",
                        "The assistant is not reachable right now. Post this in your doubt clearing "
                        + "session or the group and a mentor will pick it up.",
                        "model", "", "available", false));
    }

    /**
     * Handing in a chapter task.
     *
     * This used to write a brand new Assignment on every submission and drop the
     * uploaded file on the floor, because the controller sent a fileId that this
     * signature had no parameter for. Both are gone: the row is the one the chapter
     * opened, and the submission is a turn on its thread like every other, so a
     * resubmission never overwrites what came before it.
     */
    public Assignment submitTask(String userId, String chapterId, String url,
                                 List<String> fileIds, String notes) {
        Learner l = require(userId);
        Chapter c = chapters.findById(chapterId).orElseThrow();
        if (!c.getAssignment().isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "This chapter has no assignment.");
        }
        if ((url == null || url.isBlank()) && (fileIds == null || fileIds.isEmpty())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Attach a file or paste a link.");
        }
        /*
         * The allowed types, enforced rather than displayed.
         *
         * "accepts pdf, ipynb, py, zip" was shown to the learner and checked by nothing,
         * so a mentor opening a .docx where a notebook was asked for was a wasted round
         * trip for both people. Blank still means anything the global file filter takes.
         */
        String allow = c.getAssignment().getAllow();
        if (allow != null && !allow.isBlank() && fileIds != null) {
            Set<String> ok = Arrays.stream(allow.split("[,\\s]+"))
                    .map(x -> x.trim().toLowerCase().replace(".", ""))
                    .filter(x -> !x.isEmpty()).collect(Collectors.toSet());
            for (Map<String, Object> fv : fileService.viewsOf(fileIds)) {
                String ext = String.valueOf(fv.get("ext"));
                if (!ext.isEmpty() && !ok.contains(ext)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            fv.get("filename") + " is a ." + ext + " file. This assignment takes "
                                    + allow + ".");
                }
            }
        }
        Assignment a = openAssignment(l, c);
        if ("APPROVED".equals(a.getStatus()) && !c.getAssignment().isResubmit()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This one is approved and does not take another submission.");
        }
        String name = users.findById(l.getUserId()).map(User::getFullName).orElse("Learner");
        return tasks.submit(a.getId(), l.getId(), name, url, fileIds, notes);
    }

    /* ---------------------------------------------------------------- sessions */

    public List<Map<String, Object>> openSlots(String userId, String kind) {
        Learner l = require(userId);
        boolean premium = l.getTrackType() == Learner.TrackType.PREMIUM;
        String scope = premium ? "PREMIUM" : "BATCH";
        return slots.findByTrackScopeInAndOpenTrue(List.of(scope, "BOTH")).stream()
                .filter(s -> kind == null || kind.equalsIgnoreCase(s.getKind()))
                .filter(s -> s.getStartsAt() != null && s.getStartsAt().isAfter(Instant.now()))
                /*
                 * Doubt clearing is combined across batches: anyone from any batch can
                 * join, which is why recordings are not batch specific either. The old
                 * rule dropped every slot carrying a batch id, so a schedule created
                 * against a batch silently hid it from everybody else. A slot is now
                 * only private to a batch when it says so.
                 */
                .filter(s -> s.getBatchId() == null || s.isOpenToAllBatches()
                        || s.getBatchId().equals(l.getBatchId()))
                /* the five session-kind toggles, finally doing something */
                .filter(s -> featureService.sessionKindAllowed(l, s.getKind()))
                .filter(Slot::isPublished)
                .sorted(Comparator.comparing(Slot::getStartsAt))
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.getId());
                    m.put("kind", s.getKind());
                    m.put("startsAt", s.getStartsAt());
                    m.put("durationMin", s.getDurationMin());
                    m.put("mentor", users.findById(s.getMentorId()).map(User::getFullName).orElse(null));
                    m.put("seatsLeft", s.getCapacity() - bookings.countBySlotId(s.getId()));
                    // the window, not the link: the link is fetched on click
                    m.putAll(meetings.windowFor(s));
                    m.put("booked", bookings.findBySlotIdAndLearnerId(s.getId(), l.getId()).isPresent());
                    return m;
                })
                .collect(Collectors.toList());
    }

    /**
     * Premium books a one to one slot. Batch never books: doubt clearing is a fixed
     * group session, so the button is not offered on that side.
     */
    public Booking book(String userId, String slotId) {
        Learner l = require(userId);
        Slot s = slots.findById(slotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot not found."));
        /*
         * Who may book a private slot is a per-track setting, not a hard-coded track
         * check. It was written as "batch cannot book", which is the default but not the
         * rule: the rule is the one_to_one_booking toggle, so a batch learner who has been
         * granted it individually gets it, and premium can have it withdrawn.
         */
        if (!featureService.sessionKindAllowed(l, s.getKind())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "That kind of session is not part of your programme.");
        }
        if (!"GROUP_DOUBT".equals(s.getKind())) {
            featureService.require(l, "one_to_one_booking",
                    "Your track joins the fixed group sessions. There is no one to one booking.");
        }
        /*
         * A learner belongs to one mentor, and the one to one is that relationship.
         * Everything else on the board is open to any host, which is the point of the
         * rotation: the group sessions are the org's, the private session is the
         * mentor's.
         */
        if (("DOUBT".equals(s.getKind()) || "ONE_ON_ONE".equals(s.getKind()))
                && l.getMentorId() != null && s.getMentorId() != null
                && !l.getMentorId().equals(s.getMentorId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "One to one sessions are with your own mentor. Group sessions are open to any of them.");
        }
        if (bookings.countBySlotId(slotId) >= s.getCapacity()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That slot is full. Pick another time.");
        }
        if (bookings.findBySlotIdAndLearnerId(slotId, l.getId()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You have already booked this slot.");
        }
        Booking b = new Booking();
        b.setSlotId(slotId);
        b.setLearnerId(l.getId());
        bookings.save(b);
        /*
         * Booking is not attending.
         *
         * This used to clear the onboarding gate the moment the booking row was written,
         * so a learner who booked and never appeared had their modules opened for them.
         * The gate is now cleared by ScheduleService.mark when the mentor takes
         * attendance, which is the record of the session actually happening.
         */
        if (s.getCapacity() == 1) {
            s.setOpen(false);
            slots.save(s);
        }
        return b;
    }

    /* ---------------------------------------------------------------- mocks, projects */

    /**
     * Premium: a mock opens automatically when a module is finished.
     * Batch: the tile stays visible but locked until one project is approved, and the
     * learner then asks for it rather than getting it automatically.
     */
    public Map<String, Object> mockEligibility(String userId) {
        Learner l = require(userId);
        if (l.getTrackType() == Learner.TrackType.PREMIUM) {
            long completeTopics = roadmap(l).stream()
                    .filter(t -> Integer.valueOf(0).equals(t.get("total")) == false
                            && t.get("done").equals(t.get("total")))
                    .count();
            return Map.of("eligible", completeTopics > 0, "origin", "AUTO",
                    "reason", completeTopics > 0 ? "A module is complete."
                            : "Finish a module to open your mock interview.");
        }
        long approved = projects.countByLearnerIdAndStatus(l.getId(), "APPROVED");
        return Map.of("eligible", approved > 0, "origin", "REQUEST",
                "reason", approved > 0 ? "One project approved. You can request a mock."
                        : "Complete and get one project approved to unlock this.");
    }

    public MockRequest requestMock(String userId, String moduleId) {
        Learner l = require(userId);
        Map<String, Object> e = mockEligibility(userId);
        if (!Boolean.TRUE.equals(e.get("eligible"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, String.valueOf(e.get("reason")));
        }
        MockRequest m = new MockRequest();
        m.setLearnerId(l.getId());
        m.setModuleId(moduleId);
        m.setOrigin(String.valueOf(e.get("origin")));
        return mocks.save(m);
    }

    public ProjectWork submitProject(String userId, ProjectWork body) {
        Learner l = require(userId);
        body.setId(null);
        body.setLearnerId(l.getId());
        body.setStatus("SUBMITTED");
        body.setSubmittedAt(Instant.now());
        return projects.save(body);
    }

    public ResumeVersion addResume(String userId, String filename, String url) {
        Learner l = require(userId);
        List<ResumeVersion> existing = resumes.findByLearnerIdOrderByVersionDesc(l.getId());
        ResumeVersion r = new ResumeVersion();
        r.setLearnerId(l.getId());
        r.setVersion(existing.isEmpty() ? 1 : existing.get(0).getVersion() + 1);
        r.setFilename(filename);
        r.setUrl(url);
        return resumes.save(r);
    }

    /* ---------------------------------------------------------------- batch surfaces */

    public Map<String, Object> batchView(Learner l) {
        /* a null id reaches Mongo as a query it refuses outright, which surfaces as a
           500 rather than an empty page */
        if (l.getBatchId() == null || l.getBatchId().isBlank()) return Map.of();
        Batch b = batches.findById(l.getBatchId()).orElse(null);
        if (b == null) return Map.of();
        List<Learner> roster = learners.findByBatchId(b.getId());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", b.getCode());
        m.put("name", b.getName());
        m.put("startDate", b.getStartDate());
        m.put("inductionDate", b.getInductionDate());
        m.put("inductionDone", b.isInductionDone());
        // a ref, never a link: playback still goes through the grant flow
        m.put("inductionRecordingId", b.getInductionRecordingId());
        m.put("whatsappLink", b.getWhatsappLink());
        m.put("size", roster.size());
        m.put("roster", roster.stream().map(x -> users.findById(x.getUserId())
                .map(User::getFullName).orElse("Learner")).sorted().toList());
        m.put("announcements", announcements.findByBatchIdOrderByCreatedAtDesc(b.getId()));
        /*
         * Both of these are toggles on the Track features screen, so they are answered
         * here rather than drawn unconditionally and hidden in the browser. Off means the
         * numbers never leave the server, which is what an admin switching them off is
         * asking for.
         */
        boolean pace = featureService.enabled(l, "cohort_pace");
        boolean board = featureService.enabled(l, "batch_leaderboard");
        m.put("cohortPace", pace ? cohortPace(l, roster) : null);
        m.put("leaderboard", board ? leaderboard(roster) : List.of());
        m.put("rank", board ? rankFor(l, roster) : null);
        return m;
    }

    /** Cohort pace is the earliest at risk signal a learner gets about themselves. */
    private Map<String, Object> cohortPace(Learner me, List<Learner> roster) {
        double mine = ((Number) stats(me).get("percent")).doubleValue();
        double avg = roster.stream()
                .mapToDouble(x -> ((Number) stats(x).get("percent")).doubleValue())
                .average().orElse(0);
        return Map.of("mine", Math.round(mine), "cohortAverage", Math.round(avg),
                "standing", mine >= avg ? "AHEAD" : "BEHIND");
    }

    /**
     * Where they stand, in their batch and across everyone on the same course. Both,
     * because a small batch makes a rank meaningless on its own.
     */
    private Map<String, Object> rankFor(Learner l, List<Learner> roster) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("inBatch", placeOf(l, roster));
        m.put("batchSize", roster.size());

        List<Learner> sameCourse = l.getBundleId() == null ? List.of()
                : learners.findAll().stream()
                    .filter(x -> l.getBundleId().equals(x.getBundleId()))
                    .filter(x -> !x.isOnHold())
                    .toList();
        m.put("overall", placeOf(l, sameCourse));
        m.put("overallSize", sameCourse.size());
        return m;
    }

    /** Ties share a place, so two learners on the same percent are both third. */
    private int placeOf(Learner l, List<Learner> pool) {
        if (pool.isEmpty()) return 0;
        int mine = ((Number) stats(l).get("percent")).intValue();
        long ahead = pool.stream()
                .filter(x -> !x.getId().equals(l.getId()))
                .filter(x -> ((Number) stats(x).get("percent")).intValue() > mine)
                .count();
        return (int) ahead + 1;
    }

    public List<Map<String, Object>> leaderboard(List<Learner> pool) {
        return pool.stream().map(x -> {
            Map<String, Object> s = stats(x);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", users.findById(x.getUserId()).map(User::getFullName).orElse("Learner"));
            row.put("percent", s.get("percent"));
            row.put("avgQuiz", s.get("avgQuiz"));
            row.put("tasksApproved", s.get("tasksApproved"));
            return row;
        }).sorted((a, b) -> Integer.compare(
                ((Number) b.get("percent")).intValue(), ((Number) a.get("percent")).intValue()))
          .limit(10).collect(Collectors.toList());
    }

    public List<JobPost> jobs(String userId) {
        Learner l = require(userId);
        featureService.require(l, "jobs_referrals", "Job openings are not part of your programme.");
        return jobs.findAllByOrderByPostedAtDesc();
    }

    /** A learner's own notes against a chapter. Theirs alone, nobody else reads them. */
    public Map<String, Object> note(String userId, String chapterId) {
        Learner l = require(userId);
        return notes.findByLearnerIdAndChapterId(l.getId(), chapterId)
                .map(n -> Map.<String, Object>of("body", n.getBody() == null ? "" : n.getBody(),
                        "updatedAt", n.getUpdatedAt()))
                .orElse(Map.of("body", "", "updatedAt", ""));
    }

    public Map<String, Object> saveNote(String userId, String chapterId, String body) {
        Learner l = require(userId);
        ChapterNote n = notes.findByLearnerIdAndChapterId(l.getId(), chapterId)
                .orElseGet(ChapterNote::new);
        n.setLearnerId(l.getId());
        n.setChapterId(chapterId);
        n.setBody(body);
        n.setUpdatedAt(Instant.now());
        notes.save(n);
        return Map.of("saved", true, "updatedAt", n.getUpdatedAt());
    }

    /**
     * The session library, grouped the way a learner looks for it. Doubt clearing is
     * combined across batches, so it is not filtered by one; a recap is a series and
     * its parts stay together.
     */
    public Map<String, Object> skillList() {
        String raw = settings.get("onboarding.skills", "");
        List<String> list = raw.isBlank() ? List.of()
                : Arrays.stream(raw.split(","))
                    .map(String::trim).filter(x -> !x.isEmpty()).toList();
        return Map.of("skills", list);
    }

    public List<FormSection> formSections() {
        return formSections.findByActiveTrueOrderByPositionAsc();
    }

    public Map<String, Object> recordings(String userId) {
        return recordingService.libraryFor(require(userId));
    }

    /** Sessions attached to a topic, shown alongside its materials. */
    public List<Map<String, Object>> chapterRecordings(String userId, String chapterId) {
        return recordingService.forChapter(chapterId, require(userId));
    }

    public List<CaseStudy> caseStudies(String userId) {
        Learner l = require(userId);
        featureService.require(l, "case_studies", "Case studies are not part of your programme.");
        return caseStudies.findAll();
    }

    public List<ProgressCall> myCalls(String userId) {
        Learner l = require(userId);
        featureService.require(l, "biweekly_call",
                "Progress calls are not run on your track. Doubt clearing is where questions go.");
        return calls.findByLearnerIdOrderByScheduledForDesc(l.getId());
    }

    public List<MockRequest> myMocks(String userId) {
        return mocks.findByLearnerId(require(userId).getId());
    }

    public List<ProjectWork> myProjects(String userId) {
        return projects.findByLearnerId(require(userId).getId());
    }

    public List<Assignment> myAssignments(String userId) {
        return assignments.findByLearnerId(require(userId).getId());
    }

    public List<ResumeVersion> myResumes(String userId) {
        return resumes.findByLearnerIdOrderByVersionDesc(require(userId).getId());
    }

    public long daysSince(Instant then) {
        return then == null ? Long.MAX_VALUE : ChronoUnit.DAYS.between(then, Instant.now());
    }
}

package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * What is still to be set up, worked out from the data rather than remembered.
 *
 * An empty product is the right way to start and a bewildering place to land. This is
 * the difference between the two: it says what to do next, in the order that stops you
 * doing work twice, and it ticks itself off as the thing actually exists rather than as
 * somebody presses "done".
 */
@Service
public class SetupService {

    private final SettingsService settings;
    private final ModuleRepository modules;
    private final ChapterRepository chapters;
    private final TopicRepository topics;
    private final ResourceRepository resources;
    private final QuizQuestionRepository questions;
    private final BundleRepository bundles;
    private final UserRepository users;
    private final BatchRepository batches;
    private final LearnerRepository learners;
    private final MeetingRoomRepository rooms;

    public SetupService(SettingsService settings, ModuleRepository modules,
                        ChapterRepository chapters, TopicRepository topics,
                        ResourceRepository resources,
                        QuizQuestionRepository questions, BundleRepository bundles,
                        UserRepository users, BatchRepository batches,
                        LearnerRepository learners, MeetingRoomRepository rooms) {
        this.settings = settings; this.modules = modules; this.chapters = chapters;
        this.topics = topics;
        this.resources = resources; this.questions = questions; this.bundles = bundles;
        this.users = users; this.batches = batches; this.learners = learners;
        this.rooms = rooms;
    }

    private record Step(String key, String label, String why, String to, String action,
                        boolean done, String detail) {}

    public Map<String, Object> checklist() {
        long mentors = users.findAll().stream()
                .filter(u -> u.getRole() == User.Role.MENTOR && u.isActive()).count();
        long moduleCount = modules.count();
        long chapterCount = chapters.count();
        long topicCount = topics.count();
        long withMaterial = topics.findAll().stream()
                .filter(t -> resources.countByTopicId(t.getId()) > 0
                        || (t.getVideoRef() != null && !t.getVideoRef().isBlank()))
                .count();
        long courses = bundles.findAll().stream()
                .filter(b -> b.isActive() && !b.getModuleIds().isEmpty()).count();
        boolean walkthrough = !settings.get("onboarding.prereqVideoRef", "").isBlank();
        /* the support address is the honest signal: the organisation name has a
           sensible default, so it being present proves nothing */
        boolean named = !settings.get("org.supportEmail", "").isBlank();

        List<Step> steps = List.of(
            new Step("settings", "Check your settings",
                "The batch start day and the at-risk threshold drive rules everywhere else, "
                + "so they are worth setting before anyone is enrolled.",
                "/super/settings", "Open settings", named, null),

            new Step("module", "Add your first module",
                "A module is one subject on the roadmap. Put whichever one everything else "
                + "builds on first, since they open in order.",
                "/super/modules", "Open modules", moduleCount > 0,
                moduleCount == 0 ? null : moduleCount + (moduleCount == 1 ? " module" : " modules")),

            new Step("chapter", "Add chapters and topics inside it",
                "Topics are what a learner actually works through, in order.",
                "/super/modules", "Add chapters", chapterCount > 0,
                chapterCount == 0 ? null : chapterCount + " chapters, " + topicCount + " topics"),

            new Step("material", "Attach a video to a topic",
                "A topic with nothing attached is an empty page. This is the step that "
                + "turns an outline into a course.",
                "/super/modules", "Attach materials", withMaterial > 0,
                topicCount == 0 ? null : withMaterial + " of " + topicCount + " have material"),

            new Step("course", "Create a course",
                "A course is the set of modules a learner is enrolled on, in order, with a price.",
                "/super/courses", "Create a course", courses > 0,
                courses == 0 ? null : courses + (courses == 1 ? " course" : " courses")),

            new Step("walkthrough", "Set the walkthrough video",
                "Every learner watches this before their modules open. Without it the first "
                + "gate is skipped.",
                "/super/settings", "Set the video", walkthrough, null),

            new Step("mentor", "Add a mentor",
                "Learners are assigned to a mentor at enrolment, so at least one has to exist "
                + "before anybody is imported.",
                "/super/people", "Add a mentor", mentors > 0,
                mentors == 0 ? null : mentors + (mentors == 1 ? " mentor" : " mentors")),

            new Step("room", "Give each mentor a meeting room",
                "The join link is fetched at click time from the room, so a mentor without one "
                + "cannot run a session.",
                "/mentor/slots", "Set up rooms", rooms.count() > 0, null),

            new Step("batch", "Create a batch",
                "Batch learners are placed into one at enrolment, by the day they joined.",
                "/admin/batches", "Create a batch", batches.count() > 0, null),

            new Step("learner", "Bring in your learners",
                "Import the record sheet, or add one by hand to try it first.",
                "/admin/import", "Import from a sheet", learners.count() > 0,
                learners.count() == 0 ? null : learners.count() + " learners")
        );

        long done = steps.stream().filter(Step::done).count();
        List<Map<String, Object>> rows = steps.stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", s.key());
            m.put("label", s.label());
            m.put("why", s.why());
            m.put("to", s.to());
            m.put("action", s.action());
            m.put("done", s.done());
            m.put("detail", s.detail());
            return m;
        }).collect(Collectors.toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("steps", rows);
        out.put("done", done);
        out.put("total", steps.size());
        out.put("complete", done == steps.size());
        /* the first thing not done is the only thing worth pointing at */
        out.put("next", steps.stream().filter(s -> !s.done()).findFirst()
                .map(Step::label).orElse(null));
        return out;
    }

    /**
     * What is not ready to teach, per course.
     *
     * Blocks nothing. A course being half built is a normal Tuesday; a course being half
     * built and nobody knowing which half is the problem.
     */
    public List<Map<String, Object>> readiness() {
        return bundles.findAll().stream().map(b -> {
            List<String> gaps = new ArrayList<>();
            if (b.getModuleIds().isEmpty()) gaps.add("No modules chosen");

            long emptyTopics = 0, testsWithoutQuestions = 0, topicTotal = 0, emptyChapters = 0;
            for (String moduleId : b.getModuleIds()) {
                for (Chapter c : chapters.findByModuleIdOrderByPositionAsc(moduleId)) {
                    List<Topic> ts = topics.findByChapterIdOrderByPositionAsc(c.getId());
                    if (ts.isEmpty()) emptyChapters++;
                    for (Topic t : ts) {
                        topicTotal++;
                        boolean hasMaterial = resources.countByTopicId(t.getId()) > 0
                                || (t.getVideoRef() != null && !t.getVideoRef().isBlank());
                        if (!hasMaterial) emptyTopics++;
                    }
                    if (c.getTest().isEnabled() && questions.findByChapterId(c.getId()).isEmpty()) {
                        testsWithoutQuestions++;
                    }
                }
            }
            if (topicTotal == 0 && !b.getModuleIds().isEmpty()) gaps.add("Modules have no topics");
            if (emptyChapters > 0) gaps.add(emptyChapters + " chapters with no topics");
            if (emptyTopics > 0) gaps.add(emptyTopics + " topics with nothing attached");
            if (testsWithoutQuestions > 0) gaps.add(testsWithoutQuestions + " tests with no questions");
            if (b.getPrice() <= 0) gaps.add("No price set");

            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("name", b.getName());
            m.put("chapters", chapters.findByModuleIdIn(b.getModuleIds()).size());
            m.put("topics", topicTotal);
            m.put("gaps", gaps);
            m.put("ready", gaps.isEmpty());
            m.put("learners", learners.findAll().stream()
                    .filter(l -> b.getId().equals(l.getBundleId())).count());
            return m;
        }).collect(Collectors.toList());
    }
}

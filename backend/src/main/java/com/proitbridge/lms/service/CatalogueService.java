package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import com.proitbridge.lms.service.video.VideoRefs;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Everything about a course, editable.
 *
 * The catalogue used to exist because a seeder wrote it once. Modules, chapters, video
 * ids, durations, task briefs and every test question were literals in Java, so adding
 * a real chapter meant a code change and a redeploy. All of it now lives here.
 *
 * The rule that shapes this file: a chapter and its video are created in one action.
 * Splitting them across two screens is how a course ends up with chapters that play
 * nothing, and the id has to be captured somewhere anyway.
 */
@Service
public class CatalogueService {

    private final ModuleRepository modules;
    private final ChapterRepository chapters;
    private final TopicRepository topics;
    private final BundleRepository bundles;
    private final QuizQuestionRepository questions;
    private final VideoRepository videos;
    private final ProgressRepository progress;
    private final LearnerRepository learners;
    private final RubricRepository rubrics;
    private final CommunityLinkRepository communityLinks;
    private final JobPostRepository jobs;
    private final CaseStudyRepository caseStudies;
    private final ActivityService activity;
    private final ResourceService resourceService;
    private final FormSectionRepository formSections;
    private final FormFieldRepository formFields;
    private final IntakeFormRepository intakeForms;

    public CatalogueService(ModuleRepository modules, ChapterRepository chapters,
                            TopicRepository topics,
                            BundleRepository bundles, QuizQuestionRepository questions,
                            VideoRepository videos, ProgressRepository progress,
                            LearnerRepository learners, RubricRepository rubrics,
                            CommunityLinkRepository communityLinks, JobPostRepository jobs,
                            CaseStudyRepository caseStudies, ActivityService activity,
                            ResourceService resourceService, FormSectionRepository formSections,
                            FormFieldRepository formFields, IntakeFormRepository intakeForms) {
        this.modules = modules; this.chapters = chapters; this.topics = topics;
        this.bundles = bundles;
        this.questions = questions; this.videos = videos; this.progress = progress;
        this.learners = learners; this.rubrics = rubrics; this.communityLinks = communityLinks;
        this.jobs = jobs; this.caseStudies = caseStudies; this.activity = activity;
        this.resourceService = resourceService; this.formSections = formSections;
        this.formFields = formFields; this.intakeForms = intakeForms;
    }

    /* ================================================================== modules */

    public CourseModule saveModule(CourseModule body, String actorEmail) {
        if (body.getName() == null || body.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A module needs a name.");
        }
        if (body.getSlug() == null || body.getSlug().isBlank()) {
            body.setSlug(body.getName().toLowerCase().replaceAll("[^a-z0-9]+", "-"));
        }
        if (body.getId() == null) {
            List<CourseModule> all = modules.findAllByOrderByPositionAsc();
            body.setPosition(all.size());
            if (body.getCode() == null || body.getCode().isBlank()) {
                body.setCode(nextModuleCode(all));
            }
        }
        CourseModule saved = modules.save(body);
        activity.log(null, actorEmail, body.getId() == null ? "CREATE_MODULE" : "UPDATE_MODULE",
                "module", saved.getName());
        return saved;
    }

    /** M01, M02, M03. Highest existing number plus one, so a deletion leaves no reused code. */
    private String nextModuleCode(List<CourseModule> all) {
        int high = 0;
        for (CourseModule m : all) {
            String c = m.getCode();
            if (c != null && c.matches("M\\d+")) high = Math.max(high, Integer.parseInt(c.substring(1)));
        }
        return String.format("M%02d", high + 1);
    }

    /**
     * A module with learner progress against it is never deleted, because deleting it
     * would silently rewrite people's history. It is archived: hidden from new courses,
     * still readable for anyone who studied it.
     */
    public Map<String, Object> deleteModule(String id, String actorEmail) {
        CourseModule t = modules.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such module."));
        long studied = progress.findAll().stream()
                .filter(p -> id.equals(p.getModuleId())).count();
        boolean inUse = bundles.findAll().stream().anyMatch(b -> b.getModuleIds().contains(id));

        if (studied > 0 || inUse) {
            t.setActive(false);
            modules.save(t);
            activity.log(null, actorEmail, "ARCHIVE_MODULE", "module", t.getName());
            return Map.of("archived", true,
                    "reason", studied > 0
                        ? studied + " learners have progress on this module, so it is archived rather than deleted"
                        : "This module is part of a course, so it is archived rather than deleted");
        }
        for (Chapter c : chapters.findByModuleIdOrderByPositionAsc(id)) {
            questions.findByChapterId(c.getId()).forEach(questions::delete);
            topics.findByChapterIdOrderByPositionAsc(c.getId()).forEach(topics::delete);
            chapters.delete(c);
        }
        modules.delete(t);
        activity.log(null, actorEmail, "DELETE_MODULE", "module", t.getName());
        return Map.of("archived", false, "reason", "Deleted, nothing referenced it");
    }

    public List<CourseModule> reorderModules(List<String> orderedIds, String actorEmail) {
        int i = 0;
        for (String id : orderedIds) {
            CourseModule t = modules.findById(id).orElse(null);
            if (t == null) continue;
            t.setPosition(i++);
            modules.save(t);
        }
        activity.log(null, actorEmail, "REORDER_MODULES", "module", orderedIds.size() + " modules");
        return modules.findAllByOrderByPositionAsc();
    }

    /** Plain lists, for the screens that only need to choose from them. */
    public List<CourseModule> moduleList() { return modules.findAllByOrderByPositionAsc(); }

    public List<Chapter> chapterList(String moduleId) {
        return chapters.findByModuleIdOrderByPositionAsc(moduleId);
    }

    public List<Topic> topicList(String chapterId) {
        return topics.findByChapterIdOrderByPositionAsc(chapterId);
    }

    /* ================================================================ chapters */

    public record ChapterInput(String id, String moduleId, String title, String summary) {}

    /**
     * A chapter is a title and a place in the order. Everything inside it, the topics
     * and the three assessment blocks, is edited on its own screen, because a chapter
     * created and configured in one modal is a modal nobody finishes.
     */
    public Chapter saveChapter(ChapterInput in, String actorEmail) {
        if (in.moduleId() == null || modules.findById(in.moduleId()).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the module this chapter belongs to.");
        }
        if (in.title() == null || in.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A chapter needs a title.");
        }

        Chapter c = in.id() == null ? new Chapter()
                : chapters.findById(in.id()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "No such chapter."));

        boolean isNew = c.getId() == null;
        c.setModuleId(in.moduleId());
        c.setTitle(in.title());
        c.setSummary(in.summary());
        if (isNew) {
            c.setPosition(chapters.findByModuleIdOrderByPositionAsc(in.moduleId()).size() + 1);
            c.getAssignment().setTitle(in.title() + " assignment");
            c.getAssignment().setBrief("Apply what this chapter covered and submit your work.");
            c.getTeachback().setPrompt("Explain " + in.title().toLowerCase() + " in your own words.");
        }

        Chapter saved = chapters.save(c);
        activity.log(null, actorEmail, isNew ? "CREATE_CHAPTER" : "UPDATE_CHAPTER",
                "chapter", saved.getTitle());
        return saved;
    }

    /** The test, the assignment and the teach back, saved as one block each. */
    public Chapter saveTest(String chapterId, Chapter.Test in, String actorEmail) {
        Chapter c = chapter(chapterId);
        if (in.getPassMark() < 0 || in.getPassMark() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A pass mark is a percentage.");
        }
        if (in.getAttempts() < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Allow at least one attempt.");
        }
        c.setTest(in);
        Chapter saved = chapters.save(c);
        activity.log(null, actorEmail, "SAVE_TEST", "chapter", c.getTitle());
        return saved;
    }

    public Chapter saveAssignment(String chapterId, Chapter.AssignmentSpec in, String actorEmail) {
        Chapter c = chapter(chapterId);
        if (in.isEnabled() && (in.getTitle() == null || in.getTitle().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An assignment needs a title.");
        }
        if (in.getFileIds() == null) in.setFileIds(new ArrayList<>());
        c.setAssignment(in);
        Chapter saved = chapters.save(c);
        activity.log(null, actorEmail, "SAVE_ASSIGNMENT", "chapter", c.getTitle());
        return saved;
    }

    public Chapter saveTeachback(String chapterId, Chapter.TeachBack in, String actorEmail) {
        Chapter c = chapter(chapterId);
        c.setTeachback(in);
        Chapter saved = chapters.save(c);
        activity.log(null, actorEmail, "SAVE_TEACHBACK", "chapter", c.getTitle());
        return saved;
    }

    /** Attach a file already uploaded through the file service to the chapter's assignment. */
    public Chapter attachAssignmentFile(String chapterId, String fileId, String actorEmail) {
        Chapter c = chapter(chapterId);
        if (!c.getAssignment().getFileIds().contains(fileId)) {
            c.getAssignment().getFileIds().add(fileId);
        }
        Chapter saved = chapters.save(c);
        activity.log(null, actorEmail, "ATTACH_ASSIGNMENT_FILE", "chapter", c.getTitle());
        return saved;
    }

    public Chapter removeAssignmentFile(String chapterId, String fileId, String actorEmail) {
        Chapter c = chapter(chapterId);
        c.getAssignment().getFileIds().remove(fileId);
        Chapter saved = chapters.save(c);
        activity.log(null, actorEmail, "REMOVE_ASSIGNMENT_FILE", "chapter", c.getTitle());
        return saved;
    }

    private Chapter chapter(String id) {
        return chapters.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such chapter."));
    }

    /**
     * A chapter with progress against it refuses deletion outright. Its test scores and
     * task history hang off this id, so removing it would rewrite what those learners did.
     */
    public Map<String, Object> deleteChapter(String id, String actorEmail) {
        Chapter c = chapter(id);
        long studied = progress.findAll().stream()
                .filter(p -> id.equals(p.getChapterId())).count();
        if (studied > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    studied + " learners have progress on this chapter. Rename or reorder it, "
                    + "but deleting it would rewrite their record.");
        }
        questions.findByChapterId(id).forEach(questions::delete);
        for (Topic t : topics.findByChapterIdOrderByPositionAsc(id)) {
            if (t.getVideoRef() != null) {
                videos.findById(t.getVideoRef()).ifPresent(v -> { v.setActive(false); videos.save(v); });
            }
            topics.delete(t);
        }
        chapters.delete(c);
        activity.log(null, actorEmail, "DELETE_CHAPTER", "chapter", c.getTitle());
        return Map.of("deleted", true);
    }

    /** Order is what the sequential unlock walks, so it has to be editable. */
    public List<Chapter> reorderChapters(String moduleId, List<String> orderedIds, String actorEmail) {
        int i = 1;
        for (String id : orderedIds) {
            Chapter c = chapters.findById(id).orElse(null);
            if (c == null || !moduleId.equals(c.getModuleId())) continue;
            c.setPosition(i++);
            chapters.save(c);
        }
        activity.log(null, actorEmail, "REORDER_CHAPTERS", "module", moduleId);
        return chapters.findByModuleIdOrderByPositionAsc(moduleId);
    }

    /* ================================================================== topics */

    public record TopicInput(String id, String chapterId, String title, String summary,
                             Integer durationMin, String videoExternalId,
                             String videoProvider, String codeRunner) {}

    /**
     * Create or update a topic, attaching its video in the same call. A blank video id
     * leaves whatever was there alone, so editing a title never detaches the video.
     */
    public Topic saveTopic(TopicInput in, String actorEmail) {
        Chapter parent = in.chapterId() == null ? null : chapters.findById(in.chapterId()).orElse(null);
        if (parent == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the chapter this topic belongs to.");
        }
        if (in.title() == null || in.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A topic needs a title.");
        }

        Topic t = in.id() == null ? new Topic()
                : topics.findById(in.id()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "No such topic."));

        boolean isNew = t.getId() == null;
        t.setChapterId(parent.getId());
        t.setModuleId(parent.getModuleId());
        t.setTitle(in.title());
        t.setSummary(in.summary());
        t.setDurationMin(in.durationMin() == null ? 0 : in.durationMin());
        t.setCodeRunner(in.codeRunner() == null || in.codeRunner().isBlank()
                ? "NONE" : in.codeRunner());
        if (isNew) {
            t.setPosition(topics.findByChapterIdOrderByPositionAsc(parent.getId()).size() + 1);
        }

        if (in.videoExternalId() != null && !in.videoExternalId().isBlank()) {
            /* whatever was pasted: a watch link, a share link, an embed snippet, or the
               id itself. Only the id is stored. */
            String id = VideoRefs.parse(in.videoExternalId(), in.videoProvider());
            attachVideo(t, id, in.videoProvider(), actorEmail);
        }

        Topic saved = topics.save(t);
        activity.log(null, actorEmail, isNew ? "CREATE_TOPIC" : "UPDATE_TOPIC",
                "topic", saved.getTitle());
        return saved;
    }

    /**
     * One video document per topic, reused on edit. Rotating a leaked id is the same
     * operation as setting one for the first time, which is why it lives in one place.
     */
    private void attachVideo(Topic t, String externalId, String provider, String actorEmail) {
        Video v = t.getVideoRef() == null ? new Video() : videos.findById(t.getVideoRef()).orElse(new Video());
        boolean rotating = v.getId() != null && !externalId.equals(v.getExternalId());
        v.setTitle(t.getTitle());
        v.setKind("TOPIC");
        v.setModuleId(t.getModuleId());
        v.setExternalId(externalId);
        v.setProvider(provider == null || provider.isBlank() ? "YOUTUBE" : provider);
        v.setDurationSec(t.getDurationMin() * 60);
        v.setActive(true);
        v = videos.save(v);
        t.setVideoRef(v.getId());
        if (rotating) {
            activity.log(null, actorEmail, "ROTATE_VIDEO", "video", t.getTitle());
        }
    }

    /**
     * A topic anybody has watched is archived, not deleted. Its watch history and its
     * doubt log are the learner's record of the sitting, not ours to remove.
     */
    public Map<String, Object> deleteTopic(String id, String actorEmail) {
        Topic t = topics.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such topic."));
        long watched = progress.findAll().stream()
                .filter(p -> p.getWatchedTopicIds().contains(id)).count();
        if (watched > 0) {
            t.setActive(false);
            topics.save(t);
            activity.log(null, actorEmail, "ARCHIVE_TOPIC", "topic", t.getTitle());
            return Map.of("archived", true,
                    "reason", watched + " learners have watched this topic, so it is archived rather than deleted");
        }
        resourceService.deleteAllFor(t.getId(), actorEmail);
        if (t.getVideoRef() != null) {
            videos.findById(t.getVideoRef()).ifPresent(v -> { v.setActive(false); videos.save(v); });
        }
        topics.delete(t);
        activity.log(null, actorEmail, "DELETE_TOPIC", "topic", t.getTitle());
        return Map.of("archived", false, "reason", "Deleted, nobody had watched it");
    }

    public List<Topic> reorderTopics(String chapterId, List<String> orderedIds, String actorEmail) {
        int i = 1;
        for (String id : orderedIds) {
            Topic t = topics.findById(id).orElse(null);
            if (t == null || !chapterId.equals(t.getChapterId())) continue;
            t.setPosition(i++);
            topics.save(t);
        }
        activity.log(null, actorEmail, "REORDER_TOPICS", "chapter", chapterId);
        return topics.findByChapterIdOrderByPositionAsc(chapterId);
    }

    /**
     * Many at once, pasted.
     *
     * Typing forty titles one at a time is the thing that stops a real course ever being
     * entered, so an outline can be pasted straight in. One per line; an optional
     * duration after a pipe. Everything else is edited afterwards, which is the right
     * order: get the shape down first, fill it in second.
     *
     *   Setting up Python and the notebook | 20
     *   Variables, types and operators
     *
     * The same parse serves both levels. Pasted into a module it makes chapters, and
     * into a chapter it makes topics, because an outline is an outline and the person
     * pasting it should not have to care which screen they are on.
     */
    public Map<String, Object> bulkChapters(String moduleId, String text, String actorEmail) {
        if (modules.findById(moduleId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No such module.");
        }
        List<Chapter> existing = chapters.findByModuleIdOrderByPositionAsc(moduleId);
        Set<String> already = existing.stream()
                .map(c -> c.getTitle().trim().toLowerCase()).collect(Collectors.toSet());
        int position = existing.size();

        List<String> added = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Outline line : parseOutline(text)) {
            if (already.contains(line.title().toLowerCase())) { skipped.add(line.title()); continue; }
            Chapter c = new Chapter();
            c.setModuleId(moduleId);
            c.setTitle(line.title());
            c.setPosition(++position);
            c.getAssignment().setTitle(line.title() + " assignment");
            c.getAssignment().setBrief("Apply what this chapter covered and submit your work.");
            c.getTeachback().setPrompt("Explain " + line.title().toLowerCase() + " in your own words.");
            chapters.save(c);
            already.add(line.title().toLowerCase());
            added.add(line.title());
        }
        activity.log(null, actorEmail, "BULK_ADD_CHAPTERS", "module",
                added.size() + " added, " + skipped.size() + " already there");
        return Map.of("added", added.size(), "skipped", skipped.size(), "skippedTitles", skipped);
    }

    public Map<String, Object> bulkTopics(String chapterId, String text, String actorEmail) {
        Chapter parent = chapter(chapterId);
        List<Topic> existing = topics.findByChapterIdOrderByPositionAsc(chapterId);
        Set<String> already = existing.stream()
                .map(t -> t.getTitle().trim().toLowerCase()).collect(Collectors.toSet());
        int position = existing.size();

        List<String> added = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (Outline line : parseOutline(text)) {
            if (already.contains(line.title().toLowerCase())) { skipped.add(line.title()); continue; }
            Topic t = new Topic();
            t.setChapterId(chapterId);
            t.setModuleId(parent.getModuleId());
            t.setTitle(line.title());
            t.setDurationMin(line.minutes());
            t.setPosition(++position);
            topics.save(t);
            already.add(line.title().toLowerCase());
            added.add(line.title());
        }
        activity.log(null, actorEmail, "BULK_ADD_TOPICS", "chapter",
                added.size() + " added, " + skipped.size() + " already there");
        return Map.of("added", added.size(), "skipped", skipped.size(), "skippedTitles", skipped);
    }

    private record Outline(String title, int minutes) {}

    private List<Outline> parseOutline(String text) {
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Paste the outline first.");
        }
        List<Outline> out = new ArrayList<>();
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            /* a pasted outline is usually numbered, and the numbers are not the title */
            line = line.replaceFirst("^\\s*(?:\\d+[.)]|[-*\u2022])\\s*", "").trim();
            if (line.isEmpty()) continue;

            String title = line;
            int minutes = 0;
            int pipe = line.lastIndexOf('|');
            if (pipe > 0) {
                String tail = line.substring(pipe + 1).trim();
                if (tail.matches("\\d{1,3}")) {
                    minutes = Integer.parseInt(tail);
                    title = line.substring(0, pipe).trim();
                }
            }
            if (!title.isEmpty()) out.add(new Outline(title, minutes));
        }
        return out;
    }

    /* =============================================================== questions */

    public QuizQuestion saveQuestion(QuizQuestion body, String actorEmail) {
        if (body.getChapterId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Which chapter is this question for?");
        }
        if (body.getOptions() == null || body.getOptions().size() < 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A question needs at least two options.");
        }
        if (body.getCorrectIndex() < 0 || body.getCorrectIndex() >= body.getOptions().size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mark which option is correct.");
        }
        /*
         * A question with no explanation saved perfectly happily, and the learner then
         * got their answer marked wrong with nothing said about why. The explanation is
         * the only teaching the test does, so it is not optional.
         */
        if (body.getExplanation() == null || body.getExplanation().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Say why the right answer is right. The learner sees it after they answer.");
        }
        body.setDraft(false);            // written or reviewed by a person, so it is not a draft
        QuizQuestion saved = questions.save(body);
        activity.log(null, actorEmail, "SAVE_QUESTION", "chapter", body.getChapterId());
        return saved;
    }

    public void deleteQuestion(String id, String actorEmail) {
        questions.deleteById(id);
        activity.log(null, actorEmail, "DELETE_QUESTION", "question", id);
    }

    /* ================================================================= courses */

    public Bundle saveBundle(Bundle body, String actorEmail) {
        if (body.getName() == null || body.getName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A course needs a name.");
        }
        boolean isNew = body.getId() == null;

        /*
         * An edit changes what was sent and leaves the rest alone.
         *
         * This used to save the posted object straight over the stored one, which is fine
         * for a create and destructive for anything else: correcting a typo in the name
         * from a screen that does not carry price, access, the cover image or the publish
         * flag would have blanked all four. Nothing called it with an id until now, so
         * nobody had found out. Merging is the behaviour the method name has always
         * implied.
         */
        Bundle target = body;
        if (!isNew) {
            Bundle current = bundle(body.getId());
            current.setName(body.getName());
            if (body.getDescription() != null) current.setDescription(body.getDescription());
            if (body.getTag() != null) current.setTag(body.getTag());
            if (body.getColor() != null) current.setColor(body.getColor());
            /* price is a primitive, so "not sent" and "zero" look the same on the wire.
               A course is never deliberately set to zero from this screen, so a zero is
               read as absent and the stored price is kept. */
            if (body.getPrice() > 0) current.setPrice(body.getPrice());
            if (body.getImageFileId() != null) current.setImageFileId(body.getImageFileId());
            if (body.getModuleIds() != null) current.setModuleIds(body.getModuleIds());
            if (body.getAccess() != null) current.setAccess(body.getAccess());
            target = current;
        }

        if (target.getModuleIds() == null) target.setModuleIds(new ArrayList<>());
        if (target.getAccess() == null) target.setAccess(new Bundle.Access());
        Bundle saved = bundles.save(target);
        activity.log(null, actorEmail, isNew ? "CREATE_BUNDLE" : "UPDATE_BUNDLE",
                "bundle", saved.getName());
        return saved;
    }

    /** Add or remove modules without resending the rest of the course. */
    public Bundle setBundleModules(String id, List<String> moduleIds, String actorEmail) {
        Bundle b = bundle(id);
        List<String> clean = new ArrayList<>();
        for (String m : moduleIds) {
            if (modules.findById(m).isPresent() && !clean.contains(m)) clean.add(m);
        }
        b.setModuleIds(clean);
        Bundle saved = bundles.save(b);
        activity.log(null, actorEmail, "SET_BUNDLE_MODULES", "bundle",
                b.getName() + ", " + clean.size() + " modules");
        return saved;
    }

    public Bundle setBundleAccess(String id, Bundle.Access access, String actorEmail) {
        Bundle b = bundle(id);
        b.setAccess(access == null ? new Bundle.Access() : access);
        Bundle saved = bundles.save(b);
        activity.log(null, actorEmail, "SET_BUNDLE_ACCESS", "bundle", b.getName());
        return saved;
    }

    /**
     * Publishing is refused while the course would open on an empty page. Everything
     * else about readiness is advice; this one is a wall, because a learner meeting a
     * chapter with no topics is the failure the whole readiness panel exists to prevent.
     */
    public Map<String, Object> publishBundle(String id, boolean publish, String actorEmail) {
        Bundle b = bundle(id);
        if (publish) {
            List<String> blocking = new ArrayList<>();
            if (b.getModuleIds().isEmpty()) blocking.add("no modules in the course");
            for (String mid : b.getModuleIds()) {
                CourseModule m = modules.findById(mid).orElse(null);
                if (m == null) continue;
                List<Chapter> cs = chapters.findByModuleIdOrderByPositionAsc(mid);
                if (cs.isEmpty()) { blocking.add(m.getName() + " has no chapters"); continue; }
                for (Chapter c : cs) {
                    if (topics.countByChapterId(c.getId()) == 0) {
                        blocking.add(m.getName() + ", " + c.getTitle() + " has no topics");
                    }
                }
            }
            if (!blocking.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Not ready to publish: " + String.join("; ", blocking));
            }
        }
        b.setPublished(publish);
        bundles.save(b);
        activity.log(null, actorEmail, publish ? "PUBLISH_BUNDLE" : "UNPUBLISH_BUNDLE",
                "bundle", b.getName());
        return Map.of("published", publish);
    }

    private Bundle bundle(String id) {
        return bundles.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such course."));
    }

    public Map<String, Object> deleteBundle(String id, String actorEmail) {
        Bundle b = bundle(id);
        long enrolled = learners.findAll().stream().filter(l -> id.equals(l.getBundleId())).count();
        if (enrolled > 0) {
            b.setActive(false);
            bundles.save(b);
            activity.log(null, actorEmail, "ARCHIVE_BUNDLE", "bundle", b.getName());
            return Map.of("archived", true,
                    "reason", enrolled + " learners are on this course, so it is archived rather than deleted");
        }
        bundles.delete(b);
        activity.log(null, actorEmail, "DELETE_BUNDLE", "bundle", b.getName());
        return Map.of("archived", false, "reason", "Deleted, nobody was enrolled");
    }

    /* ============================================================ the whole tree */

    /** One payload the console draws itself from. */
    /**
     * Walk every topic in a course and report what a learner would actually hit.
     *
     * Item four on the list, and the reason it needs its own action: the chain from a
     * pasted YouTube link to a playing video runs through the topic, the chapter, the
     * bundle and the video grant, and every link in it can be individually fine while
     * the learner still sees nothing. This checks the whole run in one go, so publishing
     * is a decision rather than a hope.
     */
    public Map<String, Object> videoCheck(String bundleId) {
        Bundle b = bundles.findById(bundleId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such course."));

        List<Map<String, Object>> problems = new ArrayList<>();
        int topicCount = 0;
        int playable = 0;

        for (String moduleId : b.getModuleIds()) {
            CourseModule m = modules.findById(moduleId).orElse(null);
            if (m == null) {
                problems.add(problem("Course", b.getName(),
                        "A module in this course no longer exists."));
                continue;
            }
            List<Chapter> cs = chapters.findByModuleIdOrderByPositionAsc(moduleId);
            if (cs.isEmpty()) {
                problems.add(problem("Module", m.getName(), "No chapters yet."));
            }
            for (Chapter c : cs) {
                List<Topic> ts = topics.findByChapterIdOrderByPositionAsc(c.getId());
                if (ts.isEmpty()) {
                    problems.add(problem("Chapter", m.getName() + " / " + c.getTitle(),
                            "No topics, so this opens as an empty page and blocks publishing."));
                    continue;
                }
                for (Topic t : ts) {
                    if (!t.isActive()) continue;
                    topicCount++;
                    String where = m.getName() + " / " + c.getTitle() + " / " + t.getTitle();

                    if (t.getVideoRef() == null || t.getVideoRef().isBlank()) {
                        /* material on the topic can stand in for a video, so this is only
                           a problem when there is nothing at all */
                        if (resourceService.countFor(t.getId()) == 0) {
                            problems.add(problem("Topic", where, "Nothing attached at all."));
                        } else {
                            playable++;
                        }
                        continue;
                    }
                    Video v = videos.findById(t.getVideoRef()).orElse(null);
                    if (v == null) {
                        problems.add(problem("Topic", where,
                                "Points at a video record that is missing. Paste the link again."));
                    } else if (v.getExternalId() == null || v.getExternalId().isBlank()) {
                        problems.add(problem("Topic", where,
                                "The video record has no id on it. Paste the link again."));
                    } else if (!t.getModuleId().equals(moduleId)) {
                        /* the grant checks the bundle against the topic's own moduleId, so
                           a stale copy of it locks the learner out of a topic they own */
                        problems.add(problem("Topic", where,
                                "Its module reference does not match the chapter it is in."));
                    } else {
                        playable++;
                    }
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("course", b.getName());
        out.put("topics", topicCount);
        out.put("playable", playable);
        out.put("problems", problems);
        return out;
    }

    private Map<String, Object> problem(String level, String where, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("level", level);
        m.put("where", where);
        m.put("detail", detail);
        return m;
    }

    public Map<String, Object> tree() {
        List<Progress> allProgress = progress.findAll();
        List<Learner> allLearners = learners.findAll();

        List<Map<String, Object>> moduleRows = modules.findAllByOrderByPositionAsc().stream().map(m -> {
            List<Map<String, Object>> chapterRows = chapters.findByModuleIdOrderByPositionAsc(m.getId())
                    .stream().map(c -> chapterRow(c, allProgress)).collect(Collectors.toList());

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", m.getId());
            row.put("code", m.getCode());
            row.put("name", m.getName());
            row.put("slug", m.getSlug());
            row.put("description", m.getDescription());
            row.put("position", m.getPosition());
            row.put("active", m.isActive());
            row.put("chapters", chapterRows);
            row.put("topics", chapterRows.stream().mapToInt(c -> ((List<?>) c.get("topics")).size()).sum());
            row.put("minutes", chapterRows.stream().mapToInt(c -> (Integer) c.get("minutes")).sum());
            row.put("files", chapterRows.stream().mapToInt(c -> (Integer) c.get("files")).sum());
            row.put("assignments", chapterRows.stream()
                    .filter(c -> Boolean.TRUE.equals(c.get("hasAssignment"))).count());
            /* a chapter with nothing under it is what a learner hits as an empty page,
               which matters more than whether it happens to have a video */
            row.put("emptyChapters", chapterRows.stream()
                    .filter(c -> ((List<?>) c.get("topics")).isEmpty()).count());
            return row;
        }).collect(Collectors.toList());

        List<Map<String, Object>> bundleRows = bundles.findAll().stream().map(b -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", b.getId());
            row.put("name", b.getName());
            row.put("description", b.getDescription());
            row.put("price", b.getPrice());
            row.put("tag", b.getTag());
            row.put("color", b.getColor());
            row.put("imageFileId", b.getImageFileId());
            row.put("published", b.isPublished());
            row.put("active", b.isActive());
            row.put("moduleIds", b.getModuleIds());
            row.put("access", b.getAccess());
            row.put("learners", allLearners.stream()
                    .filter(l -> b.getId().equals(l.getBundleId())).count());
            row.put("readiness", readiness(b));
            return row;
        }).collect(Collectors.toList());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modules", moduleRows);
        out.put("bundles", bundleRows);
        out.put("emptyChapters", moduleRows.stream().mapToLong(m -> (Long) m.get("emptyChapters")).sum());
        return out;
    }

    private Map<String, Object> chapterRow(Chapter c, List<Progress> allProgress) {
        List<Map<String, Object>> topicRows = topics.findByChapterIdOrderByPositionAsc(c.getId())
                .stream().map(t -> {
                    Video v = t.getVideoRef() == null ? null
                            : videos.findById(t.getVideoRef()).orElse(null);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", t.getId());
                    m.put("title", t.getTitle());
                    m.put("summary", t.getSummary());
                    m.put("position", t.getPosition());
                    m.put("durationMin", t.getDurationMin());
                    m.put("codeRunner", t.getCodeRunner());
                    m.put("active", t.isActive());
                    m.put("hasVideo", v != null && v.getExternalId() != null);
                    m.put("videoProvider", v == null ? null : v.getProvider());
                    /* running the LMS never needs the id itself, so it is masked here */
                    m.put("videoMasked", v == null ? null : mask(v.getExternalId()));
                    m.put("resources", (int) resourceService.countFor(t.getId()));
                    return m;
                }).collect(Collectors.toList());

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", c.getId());
        row.put("title", c.getTitle());
        row.put("summary", c.getSummary());
        row.put("position", c.getPosition());
        row.put("active", c.isActive());
        row.put("topics", topicRows);
        row.put("minutes", topicRows.stream().mapToInt(t -> (Integer) t.get("durationMin")).sum());
        row.put("files", topicRows.stream().mapToInt(t -> (Integer) t.get("resources")).sum()
                + c.getAssignment().getFileIds().size());
        row.put("test", c.getTest());
        row.put("assignment", c.getAssignment());
        row.put("teachback", c.getTeachback());
        row.put("questions", questions.findByChapterId(c.getId()).size());
        row.put("hasAssignment", c.getAssignment().isEnabled());
        row.put("studied", allProgress.stream().filter(p -> c.getId().equals(p.getChapterId())).count());
        return row;
    }

    /**
     * What is missing before a course can be taught.
     *
     * It blocks nothing except publishing an empty chapter. A course being half built is
     * a normal Tuesday; not knowing which half is the problem.
     */
    public List<String> readiness(Bundle b) {
        List<String> gaps = new ArrayList<>();
        if (b.getModuleIds().isEmpty()) gaps.add("No modules in this course");
        if (b.getPrice() <= 0) gaps.add("No price set");
        for (String mid : b.getModuleIds()) {
            CourseModule m = modules.findById(mid).orElse(null);
            if (m == null) continue;
            List<Chapter> cs = chapters.findByModuleIdOrderByPositionAsc(mid);
            if (cs.isEmpty()) { gaps.add(m.getName() + " has no chapters"); continue; }
            for (Chapter c : cs) {
                List<Topic> ts = topics.findByChapterIdOrderByPositionAsc(c.getId());
                if (ts.isEmpty()) {
                    gaps.add(m.getName() + " \u203a " + c.getTitle() + " has no topics");
                } else {
                    long noVideo = ts.stream().filter(t -> t.getVideoRef() == null).count();
                    if (noVideo > 0) {
                        gaps.add(m.getName() + " \u203a " + c.getTitle() + ": "
                                + noVideo + " topic" + (noVideo > 1 ? "s" : "") + " with no video");
                    }
                }
                if (c.getTest().isEnabled() && questions.findByChapterId(c.getId()).isEmpty()) {
                    gaps.add(m.getName() + " \u203a " + c.getTitle() + ": test is on with no questions");
                }
                if (c.getAssignment().isEnabled()
                        && (c.getAssignment().getBrief() == null || c.getAssignment().getBrief().isBlank())) {
                    gaps.add(m.getName() + " \u203a " + c.getTitle() + ": assignment has no brief");
                }
            }
        }
        return gaps;
    }

    private String mask(String id) {
        if (id == null || id.length() < 5) return "not set";
        return id.substring(0, 2) + "\u2026" + id.substring(id.length() - 2);
    }


    /* ================================================== the information form */

    public List<FormSection> formSections() {
        return formSections.findAll().stream()
                .sorted(Comparator.comparingInt(FormSection::getPosition))
                .collect(Collectors.toList());
    }

    public FormSection saveFormSection(FormSection body, String actorEmail) {
        if (body.getLabel() == null || body.getLabel().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the section a name.");
        }
        if (body.getId() == null) {
            /* the key is what the learner's saved answers are filed under, so it is
               generated once and never changes; renaming the label is free */
            String base = body.getLabel().toLowerCase().replaceAll("[^a-z0-9]+", "_")
                    .replaceAll("^_|_$", "");
            String key = base.isBlank() ? "section" : base;
            int n = 2;
            while (formSections.findByKey(key).isPresent()) key = base + "_" + n++;
            body.setKey(key);
            body.setPosition(formSections.findAll().size());
        } else {
            FormSection existing = formSections.findById(body.getId()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "No such section."));
            body.setKey(existing.getKey());
        }
        FormSection saved = formSections.save(body);
        activity.log(null, actorEmail, "SAVE_FORM_SECTION", "form", saved.getLabel());
        return saved;
    }

    /**
     * Switched off rather than deleted when anybody has answered it.
     *
     * Deleting the section would leave their answers filed under a key nothing knows
     * about, which is data quietly orphaned rather than removed.
     */
    public Map<String, Object> deleteFormSection(String id, String actorEmail) {
        FormSection f = formSections.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such section."));
        boolean answered = intakeForms.findAll().stream()
                .anyMatch(x -> x.getCompletedSections().contains(f.getKey()));
        if (answered) {
            f.setActive(false);
            formSections.save(f);
            activity.log(null, actorEmail, "HIDE_FORM_SECTION", "form", f.getLabel());
            return Map.of("archived", true,
                    "reason", "Learners have already answered this, so it is hidden rather "
                            + "than deleted and their answers are kept.");
        }
        formSections.delete(f);
        activity.log(null, actorEmail, "DELETE_FORM_SECTION", "form", f.getLabel());
        return Map.of("archived", false, "reason", "Deleted, nobody had answered it.");
    }

    /* ------------------------------------------------------- form questions */

    /**
     * The questions inside the sections.
     *
     * Sections were configurable and their questions were not, which is the wrong half:
     * what one client asks a learner is exactly what differs from the next. Answers were
     * already stored as a free-form map keyed by section, so this needed no migration.
     */
    public List<FormField> formFields() {
        return formFields.findByOrderByPositionAsc();
    }

    public FormField saveFormField(FormField body, String actorEmail) {
        if (body.getLabel() == null || body.getLabel().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the question a label.");
        }
        if (body.getSectionKey() == null || body.getSectionKey().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say which section it belongs to.");
        }
        if (("CHOICE".equals(body.getType()) || "MULTI_CHOICE".equals(body.getType()))
                && body.getOptions().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A choice question needs something to choose from.");
        }

        if (body.getId() == null) {
            /*
             * The key files the answer, so it is generated once and never changes.
             * Renaming the label afterwards is free and keeps every answer attached.
             * Keys only have to be unique inside their own section, because that is how
             * the answers are stored.
             */
            String base = body.getLabel().replaceAll("[^A-Za-z0-9]+", " ").trim();
            String[] words = base.isBlank() ? new String[]{"field"} : base.split(" ");
            StringBuilder k = new StringBuilder(words[0].toLowerCase());
            for (int w = 1; w < Math.min(words.length, 4); w++) {
                k.append(Character.toUpperCase(words[w].charAt(0)))
                 .append(words[w].substring(1).toLowerCase());
            }
            String key = k.toString();
            String candidate = key;
            int n = 2;
            while (keyTaken(body.getSectionKey(), candidate)) candidate = key + n++;
            body.setKey(candidate);
            body.setPosition(formFields.findAll().size());
        } else {
            FormField existing = formFields.findById(body.getId()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "No such question."));
            body.setKey(existing.getKey());
            body.setPosition(existing.getPosition());
        }
        FormField saved = formFields.save(body);
        activity.log(null, actorEmail, "SAVE_FORM_FIELD", "form", saved.getLabel());
        return saved;
    }

    private boolean keyTaken(String sectionKey, String key) {
        return formFields.findBySectionKeyOrderByPositionAsc(sectionKey).stream()
                .anyMatch(f -> key.equals(f.getKey()));
    }

    /**
     * Removing a question, or hiding it.
     *
     * Once anybody has answered it, the question is deactivated instead of deleted.
     * Deleting it would orphan every answer given to it, and those answers are the
     * reason the question was asked.
     */
    public Map<String, Object> deleteFormField(String id, String actorEmail) {
        FormField f = formFields.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such question."));
        boolean answered = intakeForms.findAll().stream().anyMatch(x -> {
            Object sec = x.getSections().get(f.getSectionKey());
            return sec instanceof Map<?, ?> m
                    && m.get(f.getKey()) != null
                    && !String.valueOf(m.get(f.getKey())).isBlank();
        });
        if (answered) {
            f.setActive(false);
            formFields.save(f);
            activity.log(null, actorEmail, "HIDE_FORM_FIELD", "form", f.getLabel());
            return Map.of("archived", true,
                    "reason", "Learners have already answered this, so it is hidden rather "
                            + "than deleted and their answers are kept.");
        }
        formFields.delete(f);
        activity.log(null, actorEmail, "DELETE_FORM_FIELD", "form", f.getLabel());
        return Map.of("archived", false, "reason", "Deleted, nobody had answered it.");
    }

    public List<FormField> reorderFormFields(List<String> orderedIds, String actorEmail) {
        int i = 0;
        for (String id : orderedIds) {
            FormField f = formFields.findById(id).orElse(null);
            if (f == null) continue;
            f.setPosition(i++);
            formFields.save(f);
        }
        activity.log(null, actorEmail, "REORDER_FORM_FIELDS", "form", orderedIds.size() + " questions");
        return formFields();
    }

    public List<FormSection> reorderFormSections(List<String> orderedIds, String actorEmail) {
        int i = 0;
        for (String id : orderedIds) {
            FormSection f = formSections.findById(id).orElse(null);
            if (f == null) continue;
            f.setPosition(i++);
            formSections.save(f);
        }
        activity.log(null, actorEmail, "REORDER_FORM", "form", orderedIds.size() + " sections");
        return formSections();
    }

    /* ========================================================== everything else */

    public List<Rubric> rubrics() { return rubrics.findAll(); }

    public Rubric saveRubric(Rubric body, String actorEmail) {
        Rubric saved = rubrics.save(body);
        activity.log(null, actorEmail, "SAVE_RUBRIC", "rubric", saved.getName());
        return saved;
    }

    public void deleteRubric(String id, String actorEmail) {
        rubrics.deleteById(id);
        activity.log(null, actorEmail, "DELETE_RUBRIC", "rubric", id);
    }

    public List<CommunityLink> links() { return communityLinks.findByActiveTrueOrderByPositionAsc(); }

    public CommunityLink saveLink(CommunityLink body, String actorEmail) {
        CommunityLink saved = communityLinks.save(body);
        activity.log(null, actorEmail, "SAVE_LINK", "link", saved.getLabel());
        return saved;
    }

    public void deleteLink(String id, String actorEmail) {
        communityLinks.deleteById(id);
        activity.log(null, actorEmail, "DELETE_LINK", "link", id);
    }

    public JobPost saveJob(JobPost body, String actorEmail) {
        JobPost saved = jobs.save(body);
        activity.log(null, actorEmail, "SAVE_JOB", "job", saved.getTitle());
        return saved;
    }

    public void deleteJob(String id, String actorEmail) {
        jobs.deleteById(id);
        activity.log(null, actorEmail, "DELETE_JOB", "job", id);
    }

    public CaseStudy saveCaseStudy(CaseStudy body, String actorEmail) {
        CaseStudy saved = caseStudies.save(body);
        activity.log(null, actorEmail, "SAVE_CASE_STUDY", "case", saved.getTitle());
        return saved;
    }

    public void deleteCaseStudy(String id, String actorEmail) {
        caseStudies.deleteById(id);
        activity.log(null, actorEmail, "DELETE_CASE_STUDY", "case", id);
    }
}

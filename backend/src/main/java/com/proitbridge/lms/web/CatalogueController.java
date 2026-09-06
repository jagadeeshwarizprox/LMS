package com.proitbridge.lms.web;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.security.CurrentUser;
import com.proitbridge.lms.service.CatalogueService;
import com.proitbridge.lms.service.ResourceService;
import com.proitbridge.lms.service.SetupService;
import com.proitbridge.lms.service.SettingsService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** The course builder's API. Everything a course is made of, editable. */
@RestController
@RequestMapping("/api/super/catalogue")
public class CatalogueController {

    private final CatalogueService catalogue;
    private final SettingsService settings;
    private final ResourceService resourceService;
    private final SetupService setupService;
    private final CurrentUser current;

    public CatalogueController(CatalogueService catalogue, SettingsService settings,
                               ResourceService resourceService, SetupService setupService,
                               CurrentUser current) {
        this.catalogue = catalogue;
        this.settings = settings;
        this.resourceService = resourceService;
        this.setupService = setupService;
        this.current = current;
    }

    private String actor() { return current.get().email(); }

    @GetMapping
    public Map<String, Object> tree() { return catalogue.tree(); }

    /* ---------------------------------------------------------------- modules */

    @PostMapping("/modules")
    public CourseModule saveModule(@RequestBody CourseModule body) { return catalogue.saveModule(body, actor()); }

    @DeleteMapping("/modules/{id}")
    public Map<String, Object> deleteModule(@PathVariable String id) {
        return catalogue.deleteModule(id, actor());
    }

    @PostMapping("/modules/reorder")
    public List<CourseModule> reorderModules(@RequestBody Map<String, List<String>> body) {
        return catalogue.reorderModules(body.getOrDefault("ids", List.of()), actor());
    }

    /* -------------------------------------------------------------- chapters */

    @PostMapping("/chapters")
    public Chapter saveChapter(@RequestBody CatalogueService.ChapterInput body) {
        return catalogue.saveChapter(body, actor());
    }

    @DeleteMapping("/chapters/{id}")
    public Map<String, Object> deleteChapter(@PathVariable String id) {
        return catalogue.deleteChapter(id, actor());
    }

    @PostMapping("/modules/{moduleId}/chapters/reorder")
    public List<Chapter> reorderChapters(@PathVariable String moduleId,
                                         @RequestBody Map<String, List<String>> body) {
        return catalogue.reorderChapters(moduleId, body.getOrDefault("ids", List.of()), actor());
    }

    /* ------------------------------------- the three blocks on a chapter */

    @PutMapping("/chapters/{id}/test")
    public Chapter saveTest(@PathVariable String id, @RequestBody Chapter.Test body) {
        return catalogue.saveTest(id, body, actor());
    }

    @PutMapping("/chapters/{id}/assignment")
    public Chapter saveAssignment(@PathVariable String id, @RequestBody Chapter.AssignmentSpec body) {
        return catalogue.saveAssignment(id, body, actor());
    }

    @PutMapping("/chapters/{id}/teachback")
    public Chapter saveTeachback(@PathVariable String id, @RequestBody Chapter.TeachBack body) {
        return catalogue.saveTeachback(id, body, actor());
    }

    @PostMapping("/chapters/{id}/assignment/files")
    public Chapter attachAssignmentFile(@PathVariable String id,
                                        @RequestBody Map<String, String> body) {
        return catalogue.attachAssignmentFile(id, body.get("fileId"), actor());
    }

    @DeleteMapping("/chapters/{id}/assignment/files/{fileId}")
    public Chapter removeAssignmentFile(@PathVariable String id, @PathVariable String fileId) {
        return catalogue.removeAssignmentFile(id, fileId, actor());
    }

    /* ---------------------------------------------------------------- topics */

    @PostMapping("/topics")
    public Topic saveTopic(@RequestBody CatalogueService.TopicInput body) {
        return catalogue.saveTopic(body, actor());
    }

    @DeleteMapping("/topics/{id}")
    public Map<String, Object> deleteTopic(@PathVariable String id) {
        return catalogue.deleteTopic(id, actor());
    }

    @PostMapping("/chapters/{chapterId}/topics/reorder")
    public List<Topic> reorderTopics(@PathVariable String chapterId,
                                     @RequestBody Map<String, List<String>> body) {
        return catalogue.reorderTopics(chapterId, body.getOrDefault("ids", List.of()), actor());
    }

    /** An outline pasted straight in, one topic per line. */
    @PostMapping("/chapters/{id}/topics/bulk")
    public Map<String, Object> bulkTopics(@PathVariable String id,
                                          @RequestBody Map<String, String> body) {
        return catalogue.bulkTopics(id, body.get("text"), actor());
    }

    /* --------------------------------------------------------------- courses */

    @PostMapping("/bundles")
    public Bundle saveBundle(@RequestBody Bundle body) { return catalogue.saveBundle(body, actor()); }

    @PutMapping("/bundles/{id}/modules")
    public Bundle setBundleModules(@PathVariable String id,
                                   @RequestBody Map<String, List<String>> body) {
        return catalogue.setBundleModules(id, body.getOrDefault("ids", List.of()), actor());
    }

    @PutMapping("/bundles/{id}/access")
    public Bundle setBundleAccess(@PathVariable String id, @RequestBody Bundle.Access body) {
        return catalogue.setBundleAccess(id, body, actor());
    }

    /** Walk every topic in a course and report what a learner would actually hit. */
    @GetMapping("/bundles/{id}/video-check")
    public Map<String, Object> videoCheck(@PathVariable String id) {
        return catalogue.videoCheck(id);
    }

    @PostMapping("/bundles/{id}/publish")
    public Map<String, Object> publishBundle(@PathVariable String id,
                                             @RequestBody Map<String, Boolean> body) {
        return catalogue.publishBundle(id, Boolean.TRUE.equals(body.get("publish")), actor());
    }

    @DeleteMapping("/bundles/{id}")
    public Map<String, Object> deleteBundle(@PathVariable String id) {
        return catalogue.deleteBundle(id, actor());
    }

    /* ------------------------------------------------- the information form */

    @GetMapping("/form-sections")
    public List<FormSection> formSections() { return catalogue.formSections(); }

    @PostMapping("/form-sections")
    public FormSection saveFormSection(@RequestBody FormSection body) {
        return catalogue.saveFormSection(body, actor());
    }

    @DeleteMapping("/form-sections/{id}")
    public Map<String, Object> deleteFormSection(@PathVariable String id) {
        return catalogue.deleteFormSection(id, actor());
    }

    @GetMapping("/form-fields")
    public List<FormField> formFields() { return catalogue.formFields(); }

    @PostMapping("/form-fields")
    public FormField saveFormField(@RequestBody FormField body) {
        return catalogue.saveFormField(body, actor());
    }

    @DeleteMapping("/form-fields/{id}")
    public Map<String, Object> deleteFormField(@PathVariable String id) {
        return catalogue.deleteFormField(id, actor());
    }

    @PostMapping("/form-fields/reorder")
    public List<FormField> reorderFormFields(@RequestBody Map<String, List<String>> body) {
        return catalogue.reorderFormFields(body.getOrDefault("ids", List.of()), actor());
    }

    @PostMapping("/form-sections/reorder")
    public List<FormSection> reorderFormSections(@RequestBody Map<String, List<String>> body) {
        return catalogue.reorderFormSections(body.getOrDefault("ids", List.of()), actor());
    }

    /* --------------------------------------------------------------- setup */

    @GetMapping("/setup")
    public Map<String, Object> setup() { return setupService.checklist(); }

    @GetMapping("/readiness")
    public List<Map<String, Object>> readiness() { return setupService.readiness(); }

    /** An outline pasted straight in, one chapter per line. */
    @PostMapping("/modules/{id}/chapters/bulk")
    public Map<String, Object> bulkChapters(@PathVariable String id,
                                            @RequestBody Map<String, String> body) {
        return catalogue.bulkChapters(id, body.get("text"), actor());
    }

    /* ------------------------------------------------------------- materials */

    @GetMapping("/topics/{id}/resources")
    public List<Map<String, Object>> resources(@PathVariable String id) {
        return resourceService.adminView(id);
    }

    @PostMapping("/resources")
    public Resource saveResource(@RequestBody ResourceService.ResourceInput body) {
        return resourceService.save(body, actor());
    }

    @DeleteMapping("/resources/{id}")
    public Map<String, Object> deleteResource(@PathVariable String id) {
        resourceService.delete(id, actor());
        return Map.of("deleted", true);
    }

    @PostMapping("/topics/{id}/resources/reorder")
    public List<Resource> reorderResources(@PathVariable String id,
                                           @RequestBody Map<String, List<String>> body) {
        return resourceService.reorder(id, body.getOrDefault("ids", List.of()), actor());
    }

    /** Several files at once, each named after the file. */
    @PostMapping("/topics/{id}/resources/bulk")
    public Map<String, Object> bulkResources(@PathVariable String id,
                                             @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Map<String, String>> files =
                (List<Map<String, String>>) body.getOrDefault("files", List.of());
        return resourceService.addFiles(id, files,
                String.valueOf(body.getOrDefault("kind", "NOTES")), actor());
    }

    /* ------------------------------------------------------------- questions */

    @PostMapping("/questions")
    public QuizQuestion saveQuestion(@RequestBody QuizQuestion body) {
        return catalogue.saveQuestion(body, actor());
    }

    @DeleteMapping("/questions/{id}")
    public Map<String, Object> deleteQuestion(@PathVariable String id) {
        catalogue.deleteQuestion(id, actor());
        return Map.of("deleted", true);
    }

    /* --------------------------------------------------------------- courses */


    /* ------------------------------------------------------- everything else */

    @GetMapping("/rubrics")
    public List<Rubric> rubrics() { return catalogue.rubrics(); }

    @PostMapping("/rubrics")
    public Rubric saveRubric(@RequestBody Rubric body) { return catalogue.saveRubric(body, actor()); }

    @DeleteMapping("/rubrics/{id}")
    public Map<String, Object> deleteRubric(@PathVariable String id) {
        catalogue.deleteRubric(id, actor());
        return Map.of("deleted", true);
    }

    @GetMapping("/links")
    public List<CommunityLink> links() { return catalogue.links(); }

    @PostMapping("/links")
    public CommunityLink saveLink(@RequestBody CommunityLink body) {
        return catalogue.saveLink(body, actor());
    }

    @DeleteMapping("/links/{id}")
    public Map<String, Object> deleteLink(@PathVariable String id) {
        catalogue.deleteLink(id, actor());
        return Map.of("deleted", true);
    }

    @PostMapping("/jobs")
    public JobPost saveJob(@RequestBody JobPost body) { return catalogue.saveJob(body, actor()); }

    @DeleteMapping("/jobs/{id}")
    public Map<String, Object> deleteJob(@PathVariable String id) {
        catalogue.deleteJob(id, actor());
        return Map.of("deleted", true);
    }

    @PostMapping("/case-studies")
    public CaseStudy saveCaseStudy(@RequestBody CaseStudy body) {
        return catalogue.saveCaseStudy(body, actor());
    }

    @DeleteMapping("/case-studies/{id}")
    public Map<String, Object> deleteCaseStudy(@PathVariable String id) {
        catalogue.deleteCaseStudy(id, actor());
        return Map.of("deleted", true);
    }

    /* -------------------------------------------------------------- settings */

    @GetMapping("/settings")
    public Map<String, Object> settings() { return settings.all(); }

    @PostMapping("/settings")
    public Map<String, Object> saveSettings(@RequestBody Map<String, String> body) {
        return settings.save(body, actor());
    }

    @PostMapping("/settings/{key}/reset")
    public Map<String, Object> resetSetting(@PathVariable String key) {
        return settings.reset(key, actor());
    }

    /** Used by the settings that point at a video: the walkthrough, an induction. */
    @PostMapping("/videos")
    public Map<String, Object> saveVideo(@RequestBody Map<String, String> body) {
        Video v = settings.saveVideo(body.get("ref"), body.get("title"),
                body.get("externalId"), body.get("kind"), body.get("provider"), actor());
        return Map.of("ref", v.getId(), "title", v.getTitle());
    }
}

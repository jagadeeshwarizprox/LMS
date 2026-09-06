package com.proitbridge.lms.web;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.security.CurrentUser;
import com.proitbridge.lms.service.LearnerService;
import com.proitbridge.lms.service.AssignmentService;
import com.proitbridge.lms.service.WatchService;
import com.proitbridge.lms.service.ThisWeekService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/learner")
public class LearnerController {

    private final LearnerService svc;
    private final ThisWeekService thisWeek;
    private final AssignmentService tasks;
    private final WatchService watch;
    private final CurrentUser current;

    public LearnerController(LearnerService svc, ThisWeekService thisWeek,
                             AssignmentService tasks, WatchService watch,
                             CurrentUser current) {
        this.svc = svc;
        this.thisWeek = thisWeek;
        this.tasks = tasks;
        this.watch = watch;
        this.current = current;
    }

    private String uid() { return current.get().id(); }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() { return svc.dashboard(uid()); }

    /** One short answer to "what am I meant to do today". */
    @GetMapping("/this-week")
    public Map<String, Object> thisWeek() { return thisWeek.forLearner(uid()); }

    @GetMapping("/chapters/{id}/note")
    public Map<String, Object> note(@PathVariable String id) { return svc.note(uid(), id); }

    @PutMapping("/chapters/{id}/note")
    public Map<String, Object> saveNote(@PathVariable String id, @RequestBody Map<String, String> body) {
        return svc.saveNote(uid(), id, body.get("body"));
    }

    @PostMapping("/guide-video")
    public Map<String, Object> guideVideo() { svc.markGuideWatched(uid()); return Map.of("ok", true); }

    @PostMapping("/prereq-video")
    public Map<String, Object> prereq() { svc.markPrereqWatched(uid()); return Map.of("ok", true); }

    @PostMapping("/induction-watched")
    public Map<String, Object> induction() { svc.markInductionWatched(uid()); return Map.of("ok", true); }

    @GetMapping("/intake")
    public IntakeForm intake() { return svc.intake(uid()); }

    @PutMapping("/intake/{section}")
    public IntakeForm saveSection(@PathVariable String section, @RequestBody Map<String, Object> body) {
        return svc.saveSection(uid(), section, body);
    }

    @GetMapping("/chapters/{id}")
    public Map<String, Object> chapter(@PathVariable String id) { return svc.chapter(uid(), id); }

    @GetMapping("/topics/{id}")
    public Map<String, Object> topic(@PathVariable String id) { return svc.topic(uid(), id); }

    @PostMapping("/topics/{id}/watched")
    public Progress watched(@PathVariable String id) { return svc.markWatched(uid(), id); }

    @PostMapping("/chapters/{id}/quiz")
    public Map<String, Object> quiz(@PathVariable String id, @RequestBody Map<String, Integer> answers) {
        return svc.submitQuiz(uid(), id, answers);
    }

    @PostMapping("/chapters/{id}/concept-check")
    public Map<String, Object> conceptCheck(@PathVariable String id, @RequestBody Map<String, String> body) {
        return svc.conceptCheck(uid(), id, body.get("explanation"));
    }

    @PostMapping("/chapters/{id}/doubt")
    public Map<String, Object> doubt(@PathVariable String id, @RequestBody Map<String, String> body) {
        return svc.askDoubt(uid(), id, body.get("question"));
    }

    @PostMapping("/chapters/{id}/task")
    public Assignment task(@PathVariable String id, @RequestBody Map<String, Object> body) {
        return svc.submitTask(uid(), id, (String) body.get("submissionUrl"),
                fileIdsOf(body), (String) body.get("notes"));
    }

    /** Accepts fileIds, and a lone fileId, so an older client is not broken by the list. */
    @SuppressWarnings("unchecked")
    private List<String> fileIdsOf(Map<String, Object> body) {
        Object many = body.get("fileIds");
        if (many instanceof List<?> l) return (List<String>) l;
        Object one = body.get("fileId");
        return one == null ? List.of() : List.of(String.valueOf(one));
    }

    @GetMapping("/slots")
    public List<Map<String, Object>> slots(@RequestParam(required = false) String kind) {
        return svc.openSlots(uid(), kind);
    }

    @PostMapping("/slots/{id}/book")
    public Booking book(@PathVariable String id) { return svc.book(uid(), id); }

    @GetMapping("/mock-eligibility")
    public Map<String, Object> mockEligibility() { return svc.mockEligibility(uid()); }

    @PostMapping("/mocks")
    public MockRequest requestMock(@RequestBody Map<String, String> body) {
        return svc.requestMock(uid(), body.get("moduleId"));
    }

    @GetMapping("/mocks")
    public List<MockRequest> mocks() { return svc.myMocks(uid()); }

    @GetMapping("/projects")
    public List<ProjectWork> projects() { return svc.myProjects(uid()); }

    @PostMapping("/projects")
    public ProjectWork submitProject(@RequestBody ProjectWork body) { return svc.submitProject(uid(), body); }

    @GetMapping("/tasks/{id}")
    public Map<String, Object> taskThread(@PathVariable String id) {
        return tasks.threadForLearner(id, svc.require(uid()).getId());
    }

    @PostMapping("/tasks/{id}/submit")
    public Object submitTask(@PathVariable String id, @RequestBody Map<String, Object> body) {
        var me = current.get();
        return tasks.submit(id, svc.require(me.id()).getId(), me.name(),
                (String) body.get("submissionUrl"), fileIdsOf(body), (String) body.get("notes"));
    }

    @PostMapping("/tasks/{id}/comment")
    public Map<String, Object> taskComment(@PathVariable String id, @RequestBody Map<String, String> body) {
        var me = current.get();
        tasks.commentAsLearner(id, body.get("body"), svc.require(me.id()).getId(),
                me.id(), me.name());
        return Map.of("ok", true);
    }

    /* ------------------------------------------------------------ continue watching */

    /** Called while playing, and once on close. */
    @PostMapping("/watch/{videoRef}")
    public Map<String, Object> saveWatch(@PathVariable String videoRef,
                                         @RequestBody Map<String, Integer> body) {
        return watch.save(svc.require(uid()).getId(), videoRef,
                body.getOrDefault("seconds", 0), body.getOrDefault("durationSec", 0));
    }

    /** Asked before playback starts, so the player knows whether to offer a resume. */
    @GetMapping("/watch/{videoRef}")
    public Map<String, Object> resume(@PathVariable String videoRef) {
        return watch.resumeFor(svc.require(uid()).getId(), videoRef);
    }

    @GetMapping("/continue-watching")
    public List<Map<String, Object>> continueWatching() {
        return watch.continueWatching(svc.require(uid()).getId(), 6);
    }

    /** The skills this organisation asks learners to rate themselves on. */
    @GetMapping("/settings/skills")
    public Map<String, Object> skills() { return svc.skillList(); }

    /** The sections this organisation asks for, in order. */
    @GetMapping("/form-sections")
    public List<FormSection> formSections() { return svc.formSections(); }

    /** What each section asks. Configured, not compiled in. */
    @GetMapping("/form-fields")
    public List<FormField> formFields() { return svc.formFields(); }

    /* the in-app inbox is gone: what a mentor sends reaches the learner by email, and
       the conversation itself lives on WhatsApp rather than in a second place to check */

    @GetMapping("/assignments")
    public List<Assignment> assignments() { return svc.myAssignments(uid()); }

    @GetMapping("/calls")
    public List<ProgressCall> calls() { return svc.myCalls(uid()); }

    @GetMapping("/resumes")
    public List<ResumeVersion> resumes() { return svc.myResumes(uid()); }

    @PostMapping("/resumes")
    public ResumeVersion addResume(@RequestBody Map<String, String> body) {
        return svc.addResume(uid(), body.get("filename"), body.get("url"));
    }

    @GetMapping("/jobs")
    public List<JobPost> jobs() { return svc.jobs(uid()); }

    @GetMapping("/case-studies")
    public List<CaseStudy> caseStudies() { return svc.caseStudies(uid()); }

    @GetMapping("/recordings")
    public Map<String, Object> recordings() { return svc.recordings(uid()); }

    @GetMapping("/chapters/{id}/recordings")
    public List<Map<String, Object>> chapterRecordings(@PathVariable String id) {
        return svc.chapterRecordings(uid(), id);
    }
}

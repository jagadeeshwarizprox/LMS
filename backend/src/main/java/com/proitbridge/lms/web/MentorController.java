package com.proitbridge.lms.web;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.security.AuthUser;
import com.proitbridge.lms.security.CurrentUser;
import com.proitbridge.lms.service.MeetingService;
import com.proitbridge.lms.service.AssignmentService;
import com.proitbridge.lms.service.MentorDeskService;
import com.proitbridge.lms.service.MessageService;
import com.proitbridge.lms.service.RecordingService;
import com.proitbridge.lms.service.ScheduleService;
import com.proitbridge.lms.service.MentorService;
import com.proitbridge.lms.service.ProjectRunService;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mentor")
public class MentorController {

    private final MentorService svc;
    private final MentorDeskService desk;
    private final MeetingService meetings;
    private final AssignmentService tasks;
    private final ScheduleService schedule;
    private final MessageService chat;
    private final RecordingService recordingService;
    private final ProjectRunService projectRuns;
    private final CurrentUser current;

    public MentorController(MentorService svc, MentorDeskService desk, MeetingService meetings,
                            ProjectRunService projectRuns,
                            AssignmentService tasks, ScheduleService schedule,
                            MessageService chat, RecordingService recordingService,
                            CurrentUser current) {
        this.svc = svc;
        this.desk = desk;
        this.meetings = meetings;
        this.tasks = tasks;
        this.schedule = schedule;
        this.chat = chat;
        this.recordingService = recordingService;
        this.projectRuns = projectRuns;
        this.current = current;
    }

    /* ------------------------------------------------------------- the desk */

    @GetMapping("/roster")
    public Map<String, Object> roster() { return desk.roster(current.get().id()); }

    @GetMapping("/day")
    public Map<String, Object> day() { return desk.day(current.get().id()); }

    /** Where the cohort is stuck, taken from what they asked the assistant. */
    @GetMapping("/doubts")
    public List<Map<String, Object>> doubts() { return desk.doubtDigest(current.get().id()); }

    @GetMapping("/learners/{id}/study-time")
    public Map<String, Object> studyTime(@PathVariable String id) { return desk.studyTimeFor(id); }

    @PostMapping("/resumes/{id}/reviewed")
    public Map<String, Object> resumeReviewed(@PathVariable String id) {
        desk.markResumeReviewed(id);
        return Map.of("reviewed", true);
    }

    /* ------------------------------------------------------------- assignments */

    @PostMapping("/tasks")
    public Map<String, Object> assignTask(@RequestBody Map<String, Object> body) {
        var me = current.get();
        Instant due = body.get("dueAt") == null || String.valueOf(body.get("dueAt")).isBlank()
                ? null : Instant.parse(String.valueOf(body.get("dueAt")));
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.getOrDefault("learnerIds", List.of());
        return tasks.assign(new AssignmentService.NewTask(
                (String) body.get("title"), (String) body.get("brief"), due,
                (String) body.get("rubricId"), ids, (String) body.get("batchId"),
                (String) body.get("trackScope")), me.id(), me.name());
    }

    @GetMapping("/tasks/{id}")
    public Map<String, Object> thread(@PathVariable String id) {
        AuthUser me = current.get();
        return tasks.threadForStaff(id, me.id(), me.role());
    }

    @PostMapping("/tasks/{id}/review")
    public Object reviewTask(@PathVariable String id, @RequestBody Map<String, Object> body) {
        var me = current.get();
        Double score = body.get("score") == null ? null : Double.valueOf(body.get("score").toString());
        @SuppressWarnings("unchecked")
        Map<String, Double> rubric = (Map<String, Double>) body.get("rubricScores");
        return tasks.review(id, String.valueOf(body.get("status")), score, rubric,
                (String) body.get("feedback"), me.id(), me.name(), me.role());
    }

    @PostMapping("/tasks/bulk-review")
    public Map<String, Object> bulkReview(@RequestBody Map<String, Object> body) {
        var me = current.get();
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.getOrDefault("ids", List.of());
        return tasks.bulkReview(ids, String.valueOf(body.get("status")),
                (String) body.get("feedback"), me.id(), me.name(), me.role());
    }

    @PostMapping("/tasks/{id}/comment")
    public Map<String, Object> comment(@PathVariable String id, @RequestBody Map<String, String> body) {
        var me = current.get();
        tasks.commentAsStaff(id, body.get("body"), me.id(), me.name(), me.role());
        return Map.of("ok", true);
    }

    @GetMapping("/tasks/overdue")
    public List<Map<String, Object>> overdue() { return tasks.overdue(current.get().id()); }

    @GetMapping("/rubrics")
    public List<Rubric> rubrics() { return tasks.rubricsFor("TASK"); }

    /* schedules moved to /api/week, which every host can reach and which applies the
       ownership rule per row. These three had no permission check at all: any mentor
       could pause any schedule in the org by id. */

    @PostMapping("/slots/{id}/cancel")
    public Slot cancelSlot(@PathVariable String id, @RequestBody Map<String, String> body) {
        var me = current.get();
        return schedule.cancel(id, body.get("reason"), me.id(), me.name());
    }

    @PostMapping("/slots/{id}/reschedule")
    public Slot rescheduleSlot(@PathVariable String id, @RequestBody Map<String, String> body) {
        var me = current.get();
        return schedule.reschedule(id, Instant.parse(body.get("startsAt")), me.id(), me.name());
    }

    @GetMapping("/slots/{id}/attendance")
    public Map<String, Object> attendance(@PathVariable String id) {
        return schedule.attendanceSheet(id);
    }

    @PostMapping("/slots/{id}/attendance")
    public Map<String, Object> markAttendance(@PathVariable String id, @RequestBody Map<String, String> body) {
        schedule.mark(id, body.get("learnerId"), body.getOrDefault("state", "PRESENT"));
        return Map.of("ok", true);
    }

    @PostMapping("/slots/{id}/notes")
    public Slot sessionNotes(@PathVariable String id, @RequestBody Map<String, String> body) {
        return schedule.saveNotes(id, body.get("notes"));
    }

    /* ------------------------------------------------------------- talking to learners */

    @GetMapping("/learners/{id}/nudge-draft")
    public Map<String, Object> nudgeDraft(@PathVariable String id,
                                          @RequestParam(required = false) String reasons) {
        List<String> parts = reasons == null || reasons.isBlank()
                ? List.of() : List.of(reasons.split("\\|"));
        return Map.of("body", chat.draftNudge(id, parts));
    }

    @PostMapping("/learners/{id}/message")
    public Map<String, Object> message(@PathVariable String id, @RequestBody Map<String, String> body) {
        var me = current.get();
        chat.send(id, me.id(), me.name(), body.getOrDefault("subject", "A note from your mentor"),
                body.get("body"), body.getOrDefault("kind", "MESSAGE"));
        return Map.of("sent", true);
    }

    @PostMapping("/broadcast")
    public Map<String, Object> broadcast(@RequestBody Map<String, Object> body) {
        var me = current.get();
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) body.getOrDefault("learnerIds", List.of());
        return Map.of("sent", chat.broadcast(ids, me.id(), me.name(),
                String.valueOf(body.get("subject")), String.valueOf(body.get("body"))));
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() { return svc.dashboard(current.get().id()); }

    @GetMapping("/at-risk")
    public List<Map<String, Object>> atRisk() { return svc.atRisk(current.get().id()); }

    @GetMapping("/learners/{id}")
    public Map<String, Object> profile(@PathVariable String id) {
        AuthUser me = current.get();
        return svc.learnerProfile(me.id(), me.role(), id);
    }

    @PostMapping("/assignments/{id}/review")
    public Assignment reviewAssignment(@PathVariable String id, @RequestBody Map<String, Object> body) {
        AuthUser me = current.get();
        Double score = body.get("score") == null ? null : Double.valueOf(body.get("score").toString());
        return svc.reviewAssignment(me.id(), me.role(), id,
                String.valueOf(body.get("status")), score, (String) body.get("feedback"));
    }

    @PostMapping("/projects/{id}/review")
    public ProjectWork reviewProject(@PathVariable String id, @RequestBody Map<String, String> body) {
        AuthUser me = current.get();
        return svc.reviewProject(me.id(), me.role(), id, body.get("status"), body.get("feedback"));
    }

    @PostMapping("/mocks/{id}/schedule")
    public MockRequest scheduleMock(@PathVariable String id, @RequestBody Map<String, String> body) {
        AuthUser me = current.get();
        return svc.scheduleMock(me.id(), me.role(), id, Instant.parse(body.get("scheduledFor")));
    }

    @PostMapping("/mocks/{id}/record")
    public MockRequest recordMock(@PathVariable String id, @RequestBody Map<String, Object> body) {
        AuthUser me = current.get();
        Double score = body.get("score") == null ? null : Double.valueOf(body.get("score").toString());
        return svc.recordMock(me.id(), me.role(), id, score, (String) body.get("feedback"));
    }

    /**
     * Reopening a chapter test a learner has used up.
     *
     * The attempt limit only means something if it can be reached, and a learner who has
     * reached it needs a route back that is not a support ticket. Their attempts are on
     * the record either way.
     */
    @PostMapping("/learners/{id}/chapters/{chapterId}/reopen-test")
    public Map<String, Object> reopenTest(@PathVariable String id, @PathVariable String chapterId) {
        AuthUser me = current.get();
        return svc.reopenTestFor(me.id(), me.role(), id, chapterId);
    }

    @PostMapping("/mocks/{id}/decline")
    public MockRequest declineMock(@PathVariable String id, @RequestBody Map<String, Object> body) {
        AuthUser me = current.get();
        return svc.declineMock(me.id(), me.role(), id, (String) body.get("reason"));
    }

    @PostMapping("/learners/{id}/call")
    public ProgressCall logCall(@PathVariable String id, @RequestBody Map<String, String> body) {
        AuthUser me = current.get();
        return svc.logCall(me.id(), me.role(), id, body.get("notes"), body.get("nextGoal"));
    }

    @PostMapping("/slots")
    public Slot releaseSlot(@RequestBody Slot body) { return svc.releaseSlot(current.get().id(), body); }

    @GetMapping("/slots")
    public List<Map<String, Object>> slots() { return svc.slotBookings(current.get().id()); }

    @GetMapping("/room")
    public Map<String, Object> room() {
        var r = meetings.roomFor(current.get().id());
        return r == null ? Map.of("set", false)
                : Map.of("set", true, "label", r.getLabel(), "joinUrl", r.getJoinUrl(),
                         "passcode", r.getPasscode() == null ? "" : r.getPasscode());
    }

    @PostMapping("/room")
    public Map<String, Object> saveRoom(@RequestBody Map<String, String> body) {
        meetings.saveRoom(current.get().id(), body.get("joinUrl"), body.get("passcode"), body.get("label"));
        return Map.of("saved", true);
    }

    /* ------------------------------------------------------- session recordings */

    /** Sessions that finished and have no recording, ageing in days. */
    @GetMapping("/recordings/pending")
    public List<Map<String, Object>> pendingRecordings() {
        return recordingService.pending(current.get().id());
    }

    @PostMapping("/recordings")
    public Recording publishRecording(@RequestBody RecordingService.PublishInput body) {
        var me = current.get();
        return recordingService.publish(body, me.id(), me.email());
    }

    @DeleteMapping("/recordings/{id}")
    public Map<String, Object> unpublishRecording(@PathVariable String id) {
        recordingService.unpublish(id, current.get().email());
        return Map.of("unpublished", true);
    }

    @GetMapping("/batches")
    public List<Map<String, Object>> batches() { return svc.myBatches(current.get().id()); }

    /** The brief for a progress call, assembled from the record instead of hunted for. */
    @GetMapping("/learners/{id}/brief")
    public Map<String, Object> callBrief(@PathVariable String id) {
        AuthUser me = current.get();
        return svc.callBrief(me.id(), me.role(), id);
    }

    /* --------------------------------------------------------------- projects */

    /** Stages waiting on a review, oldest first, which is the order to work in. */
    @GetMapping("/project-queue")
    public List<Map<String, Object>> projectQueue() {
        AuthUser me = current.get();
        return projectRuns.reviewQueue(svc.myLearners(me.id()).stream().map(Learner::getId).toList());
    }

    @GetMapping("/project-runs/{runId}")
    public Map<String, Object> projectRun(@PathVariable String runId) {
        return projectRuns.runForMentor(runId);
    }

    /** The gate. Approving opens the next stage; sending back reopens this one. */
    @PostMapping("/project-runs/{runId}/stages/{stageKey}/review")
    @SuppressWarnings("unchecked")
    public Map<String, Object> reviewStage(@PathVariable String runId, @PathVariable String stageKey,
                                           @RequestBody Map<String, Object> body) {
        AuthUser me = current.get();
        Object scores = body.get("rubricScores");
        return projectRuns.review(me.id(), runId, stageKey,
                (String) body.get("outcome"),
                body.get("score") instanceof Number n ? n.doubleValue() : null,
                (String) body.get("feedback"),
                scores instanceof Map ? (Map<String, Double>) scores : null);
    }

    /** Every learner in a batch against every stage, so nobody has to open twenty records. */
    @GetMapping("/batches/{batchId}/projects/{projectId}/grid")
    public Map<String, Object> projectGrid(@PathVariable String batchId, @PathVariable String projectId) {
        return projectRuns.batchGrid(batchId, projectId);
    }

    @GetMapping("/batches/{id}/roster")
    public List<Map<String, Object>> roster(@PathVariable String id) {
        AuthUser me = current.get();
        return svc.batchRoster(me.id(), me.role(), id);
    }

    /** Everyone reporting to this mentor, with the shape of their load. Empty for a
     *  mentor with nobody under them, which is most of them. */
    @GetMapping("/team")
    public List<Map<String, Object>> team() { return svc.team(current.get().id()); }
}

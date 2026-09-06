package com.proitbridge.lms.web;

import com.proitbridge.lms.domain.Project;
import com.proitbridge.lms.repo.ProjectRepository;
import com.proitbridge.lms.security.AuthUser;
import com.proitbridge.lms.security.CurrentUser;
import com.proitbridge.lms.service.ProjectRunService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Authoring a project, and putting people on it.
 *
 * Authoring is super admin because a brief is course content. Enrolment is admin because
 * it is an operations decision about who does what and when, the same as a batch move.
 */
@RestController
public class ProjectController {

    private final ProjectRepository projects;
    private final ProjectRunService svc;
    private final CurrentUser current;

    public ProjectController(ProjectRepository projects, ProjectRunService svc, CurrentUser current) {
        this.projects = projects; this.svc = svc; this.current = current;
    }

    /* ------------------------------------------------------- authoring, super admin */

    @GetMapping("/api/super/catalogue/projects")
    public List<Project> all() {
        return projects.findAll();
    }

    @GetMapping("/api/super/catalogue/projects/{id}")
    public Project one(@PathVariable String id) {
        return projects.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such project."));
    }

    /** A new project is seeded with the six stages, so none is ever authored empty. */
    @PostMapping("/api/super/catalogue/projects")
    public Project save(@RequestBody Project body) {
        if (body.getTitle() == null || body.getTitle().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the project a title.");
        }
        if (body.getStages() == null || body.getStages().isEmpty()) {
            body.setStages(Project.defaultStages());
        }
        return projects.save(body);
    }

    @DeleteMapping("/api/super/catalogue/projects/{id}")
    public Map<String, Object> retire(@PathVariable String id) {
        Project p = one(id);
        /* retired rather than deleted: runs already point at it and history should read */
        p.setActive(false);
        projects.save(p);
        return Map.of("retired", true);
    }

    /* ----------------------------------------------------------- enrolment, admin */

    @GetMapping("/api/admin/projects")
    public List<Project> live() {
        return projects.findByActiveTrue();
    }

    @PostMapping("/api/admin/projects/{id}/enrol-learner")
    public Map<String, Object> enrolOne(@PathVariable String id, @RequestBody Map<String, String> body) {
        return Map.of("runId", svc.enrol(body.get("learnerId"), id).getId());
    }

    /** A cohort onto one project, which is how a batch project actually starts. */
    @PostMapping("/api/admin/projects/{id}/enrol-batch")
    public Map<String, Object> enrolBatch(@PathVariable String id, @RequestBody Map<String, String> body) {
        return svc.enrolBatch(body.get("batchId"), id);
    }

    @GetMapping("/api/admin/projects/{id}/batches/{batchId}/grid")
    public Map<String, Object> grid(@PathVariable String id, @PathVariable String batchId) {
        return svc.batchGrid(batchId, id);
    }

    /* --------------------------------------------------------------- the learner */

    @GetMapping("/api/learner/project-runs")
    public List<Map<String, Object>> myRuns() {
        return svc.myRuns(learnerId());
    }

    @GetMapping("/api/learner/project-runs/{runId}")
    public Map<String, Object> myRun(@PathVariable String runId) {
        return svc.run(learnerId(), runId);
    }

    @PostMapping("/api/learner/project-runs/{runId}/stages/{stageKey}")
    public Map<String, Object> submit(@PathVariable String runId, @PathVariable String stageKey,
                                      @RequestBody Map<String, Object> body) {
        return svc.submit(learnerId(), runId, stageKey, body);
    }

    private String learnerId() {
        AuthUser me = current.get();
        return svc.learnerIdFor(me.id());
    }
}

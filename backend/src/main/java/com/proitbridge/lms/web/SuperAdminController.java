package com.proitbridge.lms.web;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.security.CurrentUser;
import com.proitbridge.lms.service.ActivityService;
import com.proitbridge.lms.service.CatalogueService;
import com.proitbridge.lms.service.FeatureService;
import com.proitbridge.lms.service.SuperAdminService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/super")
public class SuperAdminController {

    private final SuperAdminService svc;
    private final FeatureService featureService;
    private final ActivityService activity;
    private final CatalogueService catalogue;
    private final CurrentUser current;

    public SuperAdminController(SuperAdminService svc, FeatureService featureService,
                                ActivityService activity, CatalogueService catalogue,
                                CurrentUser current) {
        this.svc = svc;
        this.featureService = featureService;
        this.activity = activity;
        this.catalogue = catalogue;
        this.current = current;
    }

    private String actor() { return current.get().email(); }


    /** Read only. Everything that changes the catalogue lives under /catalogue. */
    @GetMapping("/modules")
    public List<CourseModule> modules() { return catalogue.moduleList(); }

    @GetMapping("/modules/{id}/chapters")
    public List<Chapter> chapters(@PathVariable String id) {
        return catalogue.chapterList(id);
    }


    /** Switch a staff account off or back on. Never a delete. */
    @PostMapping("/people/{id}/active")
    public Map<String, Object> setStaffActive(@PathVariable String id,
                                              @RequestBody Map<String, Boolean> body) {
        return svc.setStaffActive(id, Boolean.TRUE.equals(body.get("active")), actor());
    }

    /** Move every learner from one mentor to another. */
    @PostMapping("/people/{id}/reassign-learners")
    public Map<String, Object> reassignLearners(@PathVariable String id,
                                                @RequestBody Map<String, String> body) {
        return svc.reassignLearners(id, body.get("toId"), body.get("reason"), actor());
    }

    /** Put a staff account back to the password made from its name. */
    @PostMapping("/people/{id}/reset-password")
    public Map<String, Object> resetStaffPassword(@PathVariable String id) {
        return svc.resetToDefault(id, actor());
    }

    @GetMapping("/people")
    public List<Map<String, Object>> people() { return svc.hierarchy(); }

    @PostMapping("/people")
    public Map<String, Object> saveStaff(@RequestBody Map<String, Object> body) {
        User u = new User();
        u.setId((String) body.get("id"));
        u.setEmail((String) body.get("email"));
        u.setFullName((String) body.get("fullName"));
        u.setPhone((String) body.get("phone"));
        /*
         * A second super admin is not created from inside the product.
         *
         * The role hands out every permission there is, including the one that would
         * remove the account that granted it, so it is deliberately not something the
         * running system can mint. The first one comes from the bootstrap seeder, which
         * is a deployment act with someone accountable for it.
         *
         * The check is here rather than only in the dropdown, because a dropdown is not
         * a permission: the request works just as well typed by hand.
         */
        User.Role role = User.Role.valueOf(String.valueOf(body.get("role")).toUpperCase());
        if (role == User.Role.SUPER_ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "A super admin cannot be created from here. It is set up with the deployment.");
        }
        if (u.getId() != null) {
            /* nor promoted into, and nor may an existing one be edited into something else */
            User existing = svc.findStaff(u.getId());
            if (existing != null && existing.getRole() == User.Role.SUPER_ADMIN) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "A super admin account is not edited from here.");
            }
        }
        u.setRole(role);
        u.setReportsToId((String) body.get("reportsToId"));
        u.setActive(!Boolean.FALSE.equals(body.get("active")));
        User saved = svc.saveStaff(u, (String) body.get("password"), actor());
        String pw = svc.consumeLastPassword();

        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("id", saved.getId());
        out.put("email", saved.getEmail());
        out.put("fullName", saved.getFullName());
        out.put("role", saved.getRole());
        /* shown once and never again: it is not stored anywhere in a readable form,
           so if mail is off this is the only chance to pass it on */
        out.put("password", pw);
        return out;
    }

    @GetMapping("/chapters/{id}/questions")
    public List<QuizQuestion> questions(@PathVariable String id) { return svc.questionsFor(id); }

    /** Drafted by the model, published by a person. Drafts never reach a learner. */
    @PostMapping("/chapters/{id}/draft-quiz")
    public List<QuizQuestion> draftQuiz(@PathVariable String id,
                                        @RequestBody(required = false) Map<String, Object> body) {
        int n = body == null || body.get("count") == null ? 3
                : Integer.parseInt(body.get("count").toString());
        return svc.draftQuiz(id, n, actor());
    }

    @PostMapping("/questions/{id}/publish")
    public QuizQuestion publish(@PathVariable String id, @RequestBody(required = false) QuizQuestion edits) {
        return svc.publishQuestion(id, edits, actor());
    }

    @DeleteMapping("/questions/{id}")
    public Map<String, Object> discard(@PathVariable String id) {
        svc.discardQuestion(id, actor());
        return Map.of("discarded", true);
    }

    @GetMapping("/features")
    public List<Feature> features() { return featureService.all(); }

    @PostMapping("/features/{key}")
    public Feature saveFeature(@PathVariable String key, @RequestBody Map<String, Boolean> body) {
        return svc.saveFeature(key, Boolean.TRUE.equals(body.get("forPremium")),
                Boolean.TRUE.equals(body.get("forBatch")), actor());
    }

    @DeleteMapping("/learners/{id}")
    public Map<String, Object> deleteLearner(@PathVariable String id) {
        svc.deleteLearner(id, actor());
        return Map.of("deleted", true);
    }

    @GetMapping("/activity")
    public List<ActivityLog> activity() { return activity.recent(); }
}

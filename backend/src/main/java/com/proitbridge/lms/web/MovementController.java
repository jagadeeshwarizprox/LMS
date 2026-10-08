package com.proitbridge.lms.web;

import com.proitbridge.lms.domain.Learner;
import com.proitbridge.lms.security.CurrentUser;
import com.proitbridge.lms.service.CatalogueService;
import com.proitbridge.lms.service.MovementService;
import com.proitbridge.lms.service.OnboardingBoardService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Everything that moves a learner, in one place, all of it reason gated. */
@RestController
@RequestMapping("/api/admin/learners/{id}")
public class MovementController {

    private final MovementService moves;
    private final OnboardingBoardService onboarding;
    private final CurrentUser current;
    private final CatalogueService catalogue;
    private final com.proitbridge.lms.repo.LearnerRepository learners;

    public MovementController(MovementService moves, OnboardingBoardService onboarding,
                              CurrentUser current, CatalogueService catalogue,
                              com.proitbridge.lms.repo.LearnerRepository learners) {
        this.moves = moves; this.onboarding = onboarding; this.current = current;
        this.catalogue = catalogue; this.learners = learners;
    }

    private String actor() { return current.get().email(); }

    @GetMapping("/move-options")
    public Map<String, Object> options(@PathVariable String id) { return moves.options(id); }

    @PostMapping("/track")
    public Learner changeTrack(@PathVariable String id, @RequestBody Map<String, String> body) {
        return moves.changeTrack(id,
                Learner.TrackType.valueOf(body.get("trackType").toUpperCase()),
                body.get("batchId"), body.get("reason"), actor());
    }

    @PostMapping("/move-batch")
    public Learner changeBatch(@PathVariable String id, @RequestBody Map<String, String> body) {
        return moves.changeBatch(id, body.get("batchId"), body.get("reason"), actor());
    }

    @PostMapping("/course")
    public Map<String, Object> changeBundle(@PathVariable String id, @RequestBody Map<String, String> body) {
        return moves.changeBundle(id, body.get("bundleId"), body.get("reason"), actor());
    }

    @PostMapping("/hold")
    public Learner hold(@PathVariable String id, @RequestBody Map<String, String> body) {
        return moves.hold(id, body.get("reason"), actor());
    }

    @PostMapping("/resume")
    public Learner resume(@PathVariable String id) { return moves.resume(id, actor()); }

    @PostMapping("/group-link")
    public Learner groupLink(@PathVariable String id, @RequestBody Map<String, String> body) {
        return onboarding.setGroupLink(id, body.get("link"));
    }

    /**
     * What the fast-forward panel needs in one answer: every module, the ones already
     * marked review only, and the ones the intake form suggests (rated Advanced).
     */
    @GetMapping("/fast-forward")
    public Map<String, Object> fastForwardOptions(@PathVariable String id) {
        Learner l = learners.findById(id).orElseThrow(() ->
                new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Learner not found."));
        List<Map<String, String>> modules = catalogue.moduleList().stream()
                .map(m -> Map.of("id", m.getId(), "name", m.getName() == null ? "" : m.getName()))
                .toList();
        Object suggested = onboarding.suggestFastForward(id, modules).getOrDefault("suggested", List.of());
        return Map.of("modules", modules,
                "applied", l.getFastForwardTopicIds() == null ? Set.of() : l.getFastForwardTopicIds(),
                "suggested", suggested);
    }

    @PostMapping("/fast-forward")
    public Learner fastForward(@PathVariable String id, @RequestBody Map<String, List<String>> body) {
        return onboarding.applyFastForward(id, Set.copyOf(body.getOrDefault("moduleIds", List.of())));
    }
}

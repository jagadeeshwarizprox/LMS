package com.proitbridge.lms.web;

import com.proitbridge.lms.domain.Learner;
import com.proitbridge.lms.security.CurrentUser;
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

    public MovementController(MovementService moves, OnboardingBoardService onboarding,
                              CurrentUser current) {
        this.moves = moves; this.onboarding = onboarding; this.current = current;
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

    @PostMapping("/fast-forward")
    public Learner fastForward(@PathVariable String id, @RequestBody Map<String, List<String>> body) {
        return onboarding.applyFastForward(id, Set.copyOf(body.getOrDefault("moduleIds", List.of())));
    }
}

package com.proitbridge.lms.web;

import com.proitbridge.lms.security.CurrentUser;
import com.proitbridge.lms.service.StudyTimeService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Check in, heartbeat, check out, and the learner's own time. */
@RestController
@RequestMapping("/api/learner/study")
public class StudyController {

    private final StudyTimeService study;
    private final CurrentUser current;

    public StudyController(StudyTimeService study, CurrentUser current) {
        this.study = study;
        this.current = current;
    }

    private String lid() { return study.requireLearner(current.get().id()).getId(); }

    @GetMapping
    public Map<String, Object> summary() { return study.summary(lid()); }

    @PostMapping("/check-in")
    public Map<String, Object> checkIn(@RequestBody(required = false) Map<String, String> body) {
        study.checkIn(lid(), body == null ? null : body.get("note"));
        return study.summary(lid());
    }

    /** Called while the learner is actually doing something, not on a timer alone. */
    @PostMapping("/heartbeat")
    public Map<String, Object> heartbeat(@RequestBody(required = false) Map<String, String> body) {
        return study.heartbeat(lid(), body == null ? null : body.get("chapterId"));
    }

    @PostMapping("/check-out")
    public Map<String, Object> checkOut() {
        study.checkOut(lid(), "LEARNER");
        return study.summary(lid());
    }
}

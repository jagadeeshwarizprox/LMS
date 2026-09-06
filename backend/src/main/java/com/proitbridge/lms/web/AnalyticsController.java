package com.proitbridge.lms.web;

import com.proitbridge.lms.security.AuthUser;
import com.proitbridge.lms.security.CurrentUser;
import com.proitbridge.lms.service.AnalyticsService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * One place where every learner's numbers sit side by side.
 *
 * Open to mentors as well as the office, but scoped: a mentor gets their own learners and
 * the org totals to measure against, and not the batch or mentor comparison tables. A
 * league table of other mentors' numbers is the fastest way to turn a reporting screen
 * into something people manage rather than use.
 */
@RestController
@RequestMapping("/api/analytics")
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final CurrentUser current;

    public AnalyticsController(AnalyticsService analytics, CurrentUser current) {
        this.analytics = analytics;
        this.current = current;
    }

    @GetMapping
    public Map<String, Object> overview() {
        AuthUser me = current.get();
        return analytics.overview(me.id(), me.role());
    }
}

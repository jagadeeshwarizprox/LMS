package com.proitbridge.lms.web;

import com.proitbridge.lms.security.AuthUser;
import com.proitbridge.lms.security.CurrentUser;
import com.proitbridge.lms.service.MeetingService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The join link is fetched here, at click time, inside the window. It is never
 * part of a schedule payload, so a session list cannot be scraped for live links.
 */
@RestController
@RequestMapping("/api/slots")
public class SlotController {

    private final MeetingService meetings;
    private final CurrentUser current;

    public SlotController(MeetingService meetings, CurrentUser current) {
        this.meetings = meetings;
        this.current = current;
    }

    /** Every upcoming session and what would happen if somebody pressed join. */
    @GetMapping("/join-check")
    public List<Map<String, Object>> joinCheck() {
        return meetings.joinCheck();
    }

    @PostMapping("/{id}/join")
    public Map<String, Object> join(@PathVariable String id) {
        AuthUser me = current.get();
        return meetings.join(id, me.id(), me.role());
    }
}

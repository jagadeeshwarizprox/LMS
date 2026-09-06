package com.proitbridge.lms.web;

import com.proitbridge.lms.domain.SessionSchedule;
import com.proitbridge.lms.domain.Slot;
import com.proitbridge.lms.security.AuthUser;
import com.proitbridge.lms.security.CurrentUser;
import com.proitbridge.lms.service.ScheduleService;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * The shared week, for everyone who runs a session.
 *
 * The board was reachable only by an admin, which put the org's cadence somewhere the
 * people delivering it could not open. A mentor had two half screens instead, neither of
 * which showed the week those screens produced.
 *
 * This is the same week for everybody. What changes by role is what you may do to a row,
 * not what you may see: a mentor edits what they host, an admin edits anything, and only
 * an admin releases the week to learners.
 */
@RestController
@RequestMapping("/api/week")
public class WeekController {

    private final ScheduleService schedule;
    private final CurrentUser current;

    public WeekController(ScheduleService schedule, CurrentUser current) {
        this.schedule = schedule;
        this.current = current;
    }

    /** One week. Nothing for the current week, or a Monday as yyyy-MM-dd. */
    @GetMapping
    public Map<String, Object> week(@RequestParam(required = false) String weekStart) {
        AuthUser me = current.get();
        return schedule.weekFor(me.id(), me.role(),
                weekStart == null || weekStart.isBlank() ? null : LocalDate.parse(weekStart));
    }

    /** The repeating sessions behind the week, whoever owns them. */
    @GetMapping("/schedules")
    public List<Map<String, Object>> schedules() {
        AuthUser me = current.get();
        return schedule.schedulesFor(me.id(), me.role());
    }

    /**
     * A repeating session. A mentor may only put themselves on it; an admin may set a
     * host rotation, which is what makes one weekly slot cycle across the roster.
     */
    @PostMapping("/schedules")
    public SessionSchedule saveSchedule(@RequestBody SessionSchedule body) {
        AuthUser me = current.get();
        boolean admin = "ADMIN".equals(me.role()) || "SUPER_ADMIN".equals(me.role());
        if (!admin) {
            schedule.assertMayEdit(me.id(), me.role(), body.getMentorId());
            body.setMentorId(me.id());
            body.setHostRotation(List.of());
        }
        return schedule.saveFromBoard(body);
    }

    @PostMapping("/schedules/{id}/active")
    public Map<String, Object> setActive(@PathVariable String id, @RequestBody Map<String, Object> body) {
        AuthUser me = current.get();
        schedule.assertMayEditSchedule(me.id(), me.role(), id);
        schedule.setActive(id, !Boolean.FALSE.equals(body.get("active")));
        return Map.of("ok", true);
    }

    /** One session, outside any repeating schedule. */
    @PostMapping("/sessions")
    public Slot createOne(@RequestBody Map<String, Object> body) {
        AuthUser me = current.get();
        boolean admin = "ADMIN".equals(me.role()) || "SUPER_ADMIN".equals(me.role());
        if (!admin) {
            schedule.assertMayEdit(me.id(), me.role(), (String) body.get("hostId"));
            body.put("hostId", me.id());
        }
        return schedule.createOne(body);
    }

    /** Moving a session to another host is an admin act: nobody takes their own off someone. */
    @PostMapping("/sessions/{id}/host")
    public Slot reassign(@PathVariable String id, @RequestBody Map<String, String> body) {
        AuthUser me = current.get();
        schedule.assertAdmin(me.role(), "Only the office moves a session to another host.");
        return schedule.reassign(id, body.get("hostId"));
    }

    /** Publishing is editorial, so it stays with the office however sessions are created. */
    @PostMapping("/publish")
    public Map<String, Object> publish(@RequestBody Map<String, Object> body) {
        AuthUser me = current.get();
        schedule.assertAdmin(me.role(), "The week is released by the office.");
        String w = (String) body.get("weekStart");
        boolean on = !Boolean.FALSE.equals(body.get("published"));
        return schedule.publishWeek(w == null || w.isBlank() ? null : LocalDate.parse(w), on);
    }
}

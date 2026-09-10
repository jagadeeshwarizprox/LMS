package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * A schedule that keeps itself.
 *
 * Every slot used to be created by hand, one at a time, which is how a weekly cadence
 * quietly stops happening. A schedule says which day and time a session runs; slots are
 * generated from it two weeks ahead, and the generator is idempotent, so running it
 * twice changes nothing.
 */
@Service
public class ScheduleService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int HORIZON_DAYS = 14;

    private final SessionScheduleRepository schedules;
    private final SlotRepository slots;
    private final BookingRepository bookings;
    private final AttendanceRepository attendance;
    private final LearnerRepository learners;
    private final UserRepository users;
    private final MeetingRoomRepository rooms;
    private final MessageService messages;
    private final ActivityService activity;
    private final SettingsService settings;

    public ScheduleService(SessionScheduleRepository schedules, SlotRepository slots,
                           BookingRepository bookings, AttendanceRepository attendance,
                           LearnerRepository learners, UserRepository users,
                           MeetingRoomRepository rooms, MessageService messages,
                           ActivityService activity, SettingsService settings) {
        this.schedules = schedules; this.slots = slots; this.bookings = bookings;
        this.attendance = attendance; this.learners = learners; this.users = users;
        this.rooms = rooms; this.messages = messages; this.activity = activity;
        this.settings = settings;
    }

    public void assertAdmin(String role, String message) {
        if (!"ADMIN".equals(role) && !"SUPER_ADMIN".equals(role)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
        }
    }

    /** The same ownership rule, for a repeating schedule rather than one occurrence. */
    public void assertMayEditSchedule(String userId, String role, String scheduleId) {
        SessionSchedule sc = schedules.findById(scheduleId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such schedule."));
        assertMayEdit(userId, role, sc.getMentorId());
    }

    /* ==================================================== the shared week

       Everyone who runs a session sees the same week.

       The board used to be admin only, and a mentor had two half screens instead:
       one to set up a repeating session and one to add a slot, with no view of what
       either produced. So the person actually taking Thursday's doubt clearing could
       not see that somebody else had already taken it, and the org's cadence lived in
       a place the people running it could not open.

       Read is open to every mentor. Write is decided per row: a mentor edits what they
       host, an admin edits anything. Releasing the week to learners stays with admin,
       because publishing is an editorial act, not a scheduling one. */

    /** The week, plus what this viewer is allowed to do to each row. */
    public Map<String, Object> weekFor(String userId, String role, LocalDate weekStart) {
        boolean admin = "ADMIN".equals(role) || "SUPER_ADMIN".equals(role);
        boolean mayCreate = admin || settings.getBool("session.mentorsMayCreate", true);

        Map<String, Object> m = board(weekStart);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) m.get("sessions");
        for (Map<String, Object> r : rows) {
            boolean mine = userId != null && userId.equals(r.get("hostId"));
            r.put("mine", mine);
            r.put("canEdit", admin || (mine && mayCreate));
        }
        m.put("canCreate", mayCreate);
        m.put("canPublish", admin);
        m.put("canReassign", admin);
        m.put("isAdmin", admin);
        m.put("mentors", users.findByRole(User.Role.MENTOR).stream()
                .filter(User::isActive)
                .map(u -> Map.of("id", u.getId(), "name", u.getFullName()))
                .toList());
        return m;
    }

    /** Every repeating schedule, with the same per-row permission. */
    public List<Map<String, Object>> schedulesFor(String userId, String role) {
        boolean admin = "ADMIN".equals(role) || "SUPER_ADMIN".equals(role);
        boolean mayCreate = admin || settings.getBool("session.mentorsMayCreate", true);
        return allSchedules().stream().peek(r -> {
            boolean mine = userId != null
                    && (userId.equals(r.get("ownerId"))
                        || (r.get("hostRotation") instanceof List<?> hr && hr.contains(userId)));
            r.put("mine", mine);
            r.put("canEdit", admin || (mine && mayCreate));
        }).toList();
    }

    /**
     * The guard every mentor write goes through.
     *
     * A mentor may create and may change what they host. They may not take a session off
     * somebody else, which is what reassignment is for and why that stays with admin.
     */
    public void assertMayEdit(String userId, String role, String hostId) {
        if ("ADMIN".equals(role) || "SUPER_ADMIN".equals(role)) return;
        if (!settings.getBool("session.mentorsMayCreate", true)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Sessions are set up by the office. You will see the week here once it is planned.");
        }
        if (hostId != null && !hostId.equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "That session is somebody else's. Ask the office to move it.");
        }
    }

    public SessionSchedule save(SessionSchedule body, String mentorId) {
        if (body.getMentorId() == null) body.setMentorId(mentorId);
        if (body.getRoomId() == null) {
            rooms.findByOwnerId(body.getMentorId()).ifPresent(r -> body.setRoomId(r.getId()));
        }
        SessionSchedule saved = schedules.save(body);
        generate();                       // the first slots appear immediately
        return saved;
    }

    public List<Map<String, Object>> mine(String mentorId) {
        return schedules.findByMentorId(mentorId).stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", s.getId());
            m.put("kind", s.getKind());
            m.put("weekday", s.getWeekday());
            m.put("weekdayName", DayOfWeek.of(s.getWeekday())
                    .getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH));
            m.put("startTime", s.getStartTime());
            m.put("durationMin", s.getDurationMin());
            m.put("trackScope", s.getTrackScope());
            m.put("capacity", s.getCapacity());
            m.put("active", s.isActive());
            m.put("label", s.getLabel());
            m.put("upcoming", slots.findAll().stream()
                    .filter(x -> s.getId().equals(x.getScheduleId()))
                    .filter(x -> !x.isCancelled() && x.getStartsAt().isAfter(Instant.now()))
                    .count());
            return m;
        }).collect(Collectors.toList());
    }

    public void setActive(String scheduleId, boolean active) {
        SessionSchedule s = schedules.findById(scheduleId).orElseThrow();
        s.setActive(active);
        schedules.save(s);
    }

    /** Runs on boot and nightly. Idempotent: a slot already generated is left alone. */
    @Scheduled(cron = "0 15 1 * * *")
    public void generate() {
        LocalDate today = LocalDate.now(IST);
        Set<String> existing = slots.findAll().stream()
                .filter(s -> s.getScheduleId() != null)
                .map(s -> s.getScheduleId() + "@" + s.getStartsAt())
                .collect(Collectors.toSet());

        for (SessionSchedule sc : schedules.findByActiveTrue()) {
            for (int i = 0; i <= HORIZON_DAYS; i++) {
                LocalDate d = today.plusDays(i);
                if (d.getDayOfWeek().getValue() != sc.getWeekday()) continue;
                LocalTime t = LocalTime.parse(sc.getStartTime());
                Instant at = ZonedDateTime.of(d, t, IST).toInstant();
                if (at.isBefore(Instant.now())) continue;
                if (existing.contains(sc.getId() + "@" + at)) continue;

                Slot s = new Slot();
                /*
                 * The host rotates. Which mentor takes week n is decided by how many
                 * occurrences have already gone by, so the same schedule cycles
                 * through the roster without anybody editing it. An empty roster
                 * falls back to the mentor who owns the schedule.
                 */
                s.setMentorId(hostFor(sc, d));
                s.setRoomId(sc.getRoomId());
                s.setKind(sc.getKind());
                s.setStartsAt(at);
                s.setDurationMin(sc.getDurationMin());
                s.setCapacity(sc.getCapacity());
                s.setTrackScope(sc.getTrackScope());
                s.setBatchId(sc.getBatchId());
                s.setScheduleId(sc.getId());
                s.setOpenToAllBatches(sc.isOpenToAllBatches());
                s.setTopic(sc.getTopic());
                s.setSpeaker(sc.getSpeaker());
                s.setPublished(sc.isPublished());
                slots.save(s);
            }
        }
    }

    /**
     * Who takes this occurrence.
     *
     * Weeks are counted from a fixed epoch rather than from the schedule's own start,
     * so regenerating slots after a restart lands on the same host it did before.
     */
    private String hostFor(SessionSchedule sc, LocalDate day) {
        List<String> roster = sc.getHostRotation();
        if (roster == null || roster.isEmpty()) return sc.getMentorId();
        long week = day.toEpochDay() / 7;
        int i = (int) Math.floorMod(week, roster.size());
        return roster.get(i);
    }

    /**
     * The live session board: one week, every session, whoever is taking it.
     *
     * This is the view that did not exist. A mentor could only see their own
     * schedules, so nobody could look at the week as a whole and see that Thursday
     * had no recap and Saturday had two people on the same project session.
     */
    public Map<String, Object> board(LocalDate weekStart) {
        LocalDate from = weekStart == null
                ? LocalDate.now(IST).with(DayOfWeek.MONDAY) : weekStart;
        LocalDate to = from.plusDays(7);
        Instant fromAt = ZonedDateTime.of(from, LocalTime.MIDNIGHT, IST).toInstant();
        Instant toAt = ZonedDateTime.of(to, LocalTime.MIDNIGHT, IST).toInstant();

        List<Map<String, Object>> rows = slots.findAll().stream()
                .filter(s -> s.getStartsAt() != null)
                .filter(s -> !s.getStartsAt().isBefore(fromAt) && s.getStartsAt().isBefore(toAt))
                .sorted(Comparator.comparing(Slot::getStartsAt))
                .map(this::boardRow)
                .collect(Collectors.toList());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("weekStart", from.toString());
        m.put("weekEnd", to.minusDays(1).toString());
        m.put("sessions", rows);
        m.put("published", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("published"))).count());
        m.put("draft", rows.stream().filter(r -> Boolean.FALSE.equals(r.get("published"))).count());
        return m;
    }

    private Map<String, Object> boardRow(Slot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("kind", s.getKind());
        m.put("startsAt", s.getStartsAt());
        m.put("durationMin", s.getDurationMin());
        m.put("weekday", s.getStartsAt().atZone(IST).getDayOfWeek()
                .getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH));
        m.put("time", s.getStartsAt().atZone(IST).toLocalTime().toString());
        m.put("hostId", s.getMentorId());
        m.put("host", s.getMentorId() == null ? "Unassigned"
                : users.findById(s.getMentorId()).map(User::getFullName).orElse("Unassigned"));
        m.put("trackScope", s.getTrackScope());
        m.put("batchId", s.getBatchId());
        m.put("openToAllBatches", s.isOpenToAllBatches());
        m.put("topic", s.getTopic());
        m.put("speaker", s.getSpeaker());
        m.put("capacity", s.getCapacity());
        m.put("booked", bookings.countBySlotId(s.getId()));
        m.put("published", s.isPublished());
        m.put("cancelled", s.isCancelled());
        m.put("scheduleId", s.getScheduleId());
        return m;
    }

    /** Every repeating schedule, whoever owns it, for the board's schedule tab. */
    public List<Map<String, Object>> allSchedules() {
        return schedules.findAll().stream().map(sc -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", sc.getId());
            m.put("kind", sc.getKind());
            m.put("weekday", sc.getWeekday());
            m.put("weekdayName", DayOfWeek.of(sc.getWeekday())
                    .getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH));
            m.put("startTime", sc.getStartTime());
            m.put("durationMin", sc.getDurationMin());
            m.put("trackScope", sc.getTrackScope());
            m.put("batchId", sc.getBatchId());
            m.put("openToAllBatches", sc.isOpenToAllBatches());
            m.put("capacity", sc.getCapacity());
            m.put("active", sc.isActive());
            m.put("label", sc.getLabel());
            m.put("topic", sc.getTopic());
            m.put("speaker", sc.getSpeaker());
            m.put("ownerId", sc.getMentorId());
            m.put("owner", sc.getMentorId() == null ? null
                    : users.findById(sc.getMentorId()).map(User::getFullName).orElse(null));
            m.put("hostRotation", sc.getHostRotation());
            m.put("hosts", sc.getHostRotation() == null ? List.of()
                    : sc.getHostRotation().stream()
                        .map(id -> users.findById(id).map(User::getFullName).orElse("Unknown"))
                        .collect(Collectors.toList()));
            return m;
        }).collect(Collectors.toList());
    }

    /**
     * Saved from the board rather than from one mentor's own page, so the owner is
     * whoever is first on the rotation instead of whoever happened to press save.
     */
    public SessionSchedule saveFromBoard(SessionSchedule body) {
        List<String> roster = body.getHostRotation();
        if (body.getMentorId() == null && roster != null && !roster.isEmpty()) {
            body.setMentorId(roster.get(0));
        }
        return save(body, body.getMentorId());
    }

    /** One session created straight onto the board, outside any repeating schedule. */
    public Slot createOne(Map<String, Object> body) {
        Slot s = new Slot();
        s.setKind(str(body, "kind", "GROUP_DOUBT"));
        s.setMentorId(str(body, "hostId", null));
        s.setTrackScope(str(body, "trackScope", "BOTH"));
        s.setBatchId(str(body, "batchId", null));
        s.setTopic(str(body, "topic", null));
        s.setSpeaker(str(body, "speaker", null));
        s.setOpenToAllBatches(!Boolean.FALSE.equals(body.get("openToAllBatches")));
        s.setPublished(Boolean.TRUE.equals(body.get("published")));
        s.setDurationMin(num(body, "durationMin", 60));
        s.setCapacity(num(body, "capacity", 80));
        String at = str(body, "startsAt", null);
        if (at == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the session a date and time.");
        }
        s.setStartsAt(Instant.parse(at));

        /*
         * The link.
         *
         * A session used to take the host's standing room and nothing else, so an
         * industry session on somebody else's Zoom, or a one-off on a link a guest sent
         * over, had nowhere to go. A join URL given here becomes a room pinned to this
         * session; leave it blank and the host's own room is used as before.
         */
        String joinUrl = str(body, "joinUrl", null);
        if (joinUrl != null && !joinUrl.isBlank()) {
            MeetingRoom one = new MeetingRoom();
            one.setOwnerId(s.getMentorId());
            one.setLabel(str(body, "topic", "Session link"));
            one.setProvider(str(body, "provider", "ZOOM"));
            one.setJoinUrl(joinUrl.trim());
            one.setPasscode(str(body, "passcode", null));
            s.setRoomId(rooms.save(one).getId());
        } else if (s.getMentorId() != null) {
            rooms.findByOwnerId(s.getMentorId()).ifPresent(r -> s.setRoomId(r.getId()));
        }
        return slots.save(s);
    }

    /** Change who is taking one occurrence, without touching the schedule behind it. */
    public Slot reassign(String slotId, String mentorId) {
        Slot s = slots.findById(slotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such session."));
        s.setMentorId(mentorId);
        rooms.findByOwnerId(mentorId).ifPresent(r -> s.setRoomId(r.getId()));
        return slots.save(s);
    }

    /** Release the week. Draft sessions become visible to learners in one action. */
    public Map<String, Object> publishWeek(LocalDate weekStart, boolean published) {
        LocalDate from = weekStart == null
                ? LocalDate.now(IST).with(DayOfWeek.MONDAY) : weekStart;
        Instant fromAt = ZonedDateTime.of(from, LocalTime.MIDNIGHT, IST).toInstant();
        Instant toAt = ZonedDateTime.of(from.plusDays(7), LocalTime.MIDNIGHT, IST).toInstant();
        List<Slot> released = new ArrayList<>();
        int n = 0;
        for (Slot s : slots.findAll()) {
            if (s.getStartsAt() == null) continue;
            if (s.getStartsAt().isBefore(fromAt) || !s.getStartsAt().isBefore(toAt)) continue;
            if (s.isPublished() == published) continue;
            s.setPublished(published);
            slots.save(s);
            released.add(s);
            n++;
        }

        /*
         * Releasing a week used to flip a flag and tell nobody, so a learner found out
         * only if they happened to open the LMS and notice. The week's cadence is the
         * thing they most need to know about, and it is announced once, as a week, rather
         * than as a message per session.
         */
        int told = 0;
        if (published && !released.isEmpty()) {
            released.sort(Comparator.comparing(Slot::getStartsAt));
            String body = "The sessions for the week of "
                    + from.format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
                    + " are up:\n\n"
                    + released.stream().map(this::lineFor).collect(Collectors.joining("\n"))
                    + "\n\nOpen Sessions in the LMS to book or join.";
            for (Learner l : learners.findAll()) {
                if (l.isOnHold()) continue;
                if (released.stream().noneMatch(x -> inScopeFor(x, l))) continue;
                messages.send(l.getId(), null, "ProITBridge",
                        "This week's sessions", body, "ANNOUNCEMENT");
                told++;
            }
        }
        return Map.of("changed", n, "published", published, "told", told);
    }

    /** One line per session, in the learner's own timezone terms: day, time, what it is. */
    private String lineFor(Slot s) {
        var z = s.getStartsAt().atZone(IST);
        return z.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, h:mm a"))
                + "  " + labelFor(s.getKind())
                + (s.getTopic() == null || s.getTopic().isBlank() ? "" : " - " + s.getTopic());
    }

    private String labelFor(String kind) {
        return switch (kind == null ? "" : kind) {
            case "GROUP_DOUBT", "DOUBT" -> "Doubt clearing";
            case "RECAP" -> "Recap";
            case "INTERACTIVE" -> "Interactive session";
            case "PROJECT" -> "Project session";
            case "INDUSTRY", "LIVE" -> "Industry session";
            case "INDUCTION" -> "Induction";
            case "ONBOARDING" -> "Onboarding call";
            default -> "Session";
        };
    }

    /** The same audience rule the learner's own session list uses, applied one at a time. */
    private boolean inScopeFor(Slot s, Learner l) {
        if (s.isCancelled()) return false;
        String scope = s.getTrackScope() == null ? "BOTH" : s.getTrackScope();
        boolean premium = l.getTrackType() == Learner.TrackType.PREMIUM;
        if ("PREMIUM".equals(scope) && !premium) return false;
        if ("BATCH".equals(scope) && premium) return false;
        if (s.getBatchId() != null && !s.isOpenToAllBatches()) {
            return s.getBatchId().equals(l.getBatchId());
        }
        return true;
    }

    private static String str(Map<String, Object> m, String k, String fallback) {
        Object v = m.get(k);
        return v == null || String.valueOf(v).isBlank() ? fallback : String.valueOf(v);
    }

    private static int num(Map<String, Object> m, String k, int fallback) {
        Object v = m.get(k);
        if (v instanceof Number nn) return nn.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return fallback; }
    }

    /* ---------------------------------------------------------------- one slot */

    public Slot cancel(String slotId, String reason, String mentorId, String mentorName) {
        Slot s = slots.findById(slotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such session."));
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Say why it is cancelled. Whoever booked it will be told.");
        }
        s.setCancelled(true);
        s.setOpen(false);
        s.setCancelReason(reason);
        slots.save(s);

        for (Booking b : bookings.findBySlotId(slotId)) {
            b.setStatus("CANCELLED");
            bookings.save(b);
            messages.send(b.getLearnerId(), mentorId, mentorName,
                    "A session was cancelled",
                    "The session on " + s.getStartsAt() + " will not run.\n\nReason: " + reason,
                    "MESSAGE");
        }
        activity.log(mentorId, null, "CANCEL_SESSION", "slot", slotId + ": " + reason);
        return s;
    }

    public Slot reschedule(String slotId, Instant newStart, String mentorId, String mentorName) {
        Slot s = slots.findById(slotId).orElseThrow();
        Instant was = s.getStartsAt();
        s.setStartsAt(newStart);
        s.setCancelled(false);
        slots.save(s);
        for (Booking b : bookings.findBySlotId(slotId)) {
            messages.send(b.getLearnerId(), mentorId, mentorName,
                    "A session moved",
                    "The session that was on " + was + " now runs at " + newStart + ".", "MESSAGE");
        }
        return s;
    }

    /* ---------------------------------------------------------------- attendance */

    /** Who turned up, which is the other half of running a session. */
    public Map<String, Object> attendanceSheet(String slotId) {
        Slot s = slots.findById(slotId).orElseThrow();
        Map<String, String> marked = attendance.findBySlotId(slotId).stream()
                .collect(Collectors.toMap(Attendance::getLearnerId, Attendance::getState, (a, b) -> a));

        List<String> expected = bookings.findBySlotId(slotId).stream()
                .map(Booking::getLearnerId).collect(Collectors.toList());
        if (expected.isEmpty()) {
            // an open group session has no bookings, so the whole eligible cohort is the sheet
            expected = learners.findAll().stream()
                    .filter(l -> s.getBatchId() == null || s.getBatchId().equals(l.getBatchId()))
                    .filter(l -> "BOTH".equals(s.getTrackScope())
                            || s.getTrackScope().equals(l.getTrackType().name()))
                    .filter(l -> s.getMentorId().equals(l.getMentorId()) || s.getCapacity() > 5)
                    .map(Learner::getId).collect(Collectors.toList());
        }

        List<Map<String, Object>> rows = expected.stream().map(id -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("learnerId", id);
            m.put("name", learners.findById(id).flatMap(l -> users.findById(l.getUserId()))
                    .map(User::getFullName).orElse("Learner"));
            m.put("state", marked.getOrDefault(id, ""));
            return m;
        }).collect(Collectors.toList());

        return Map.of("slotId", slotId, "startsAt", s.getStartsAt(), "kind", s.getKind(),
                "notes", s.getNotes() == null ? "" : s.getNotes(),
                "rows", rows,
                "present", marked.values().stream().filter("PRESENT"::equals).count());
    }

    /**
     * Marking who turned up, which is also what clears the onboarding gate.
     *
     * Booking used to clear it. A learner booked a slot, never appeared, and their modules
     * opened anyway, which made the one gate that exists to guarantee the human contact
     * the weakest thing in the product. Attendance was already being recorded and simply
     * was not connected to it.
     *
     * Marking somebody absent takes the gate back, because a session they did not attend
     * has not happened for them. Nothing else about their record is touched.
     */
    public void mark(String slotId, String learnerId, String state) {
        Attendance a = attendance.findBySlotId(slotId).stream()
                .filter(x -> x.getLearnerId().equals(learnerId)).findFirst()
                .orElseGet(Attendance::new);
        a.setSlotId(slotId);
        a.setLearnerId(learnerId);
        a.setState(state);
        a.setMarkedAt(Instant.now());
        attendance.save(a);
        applyGateFromAttendance(slotId, learnerId, state);
    }

    /** The onboarding and induction kinds are the only two that gate anything. */
    private void applyGateFromAttendance(String slotId, String learnerId, String state) {
        Slot s = slots.findById(slotId).orElse(null);
        if (s == null) return;
        String kind = s.getKind() == null ? "" : s.getKind();
        if (!"ONBOARDING".equals(kind) && !"INDUCTION".equals(kind)) return;

        Learner l = learners.findById(learnerId).orElse(null);
        if (l == null) return;
        boolean attended = "PRESENT".equals(state) || "LATE".equals(state);

        if ("INDUCTION".equals(kind)) {
            if (l.getTrackType() != Learner.TrackType.BATCH) return;
            l.setInductionWatched(attended);
        } else if (l.getTrackType() != Learner.TrackType.PREMIUM) {
            return;
        }
        l.setGateCallDone(attended);
        learners.save(l);
        activity.log(null, null, attended ? "GATE_CALL_ATTENDED" : "GATE_CALL_WITHDRAWN",
                "learner", learnerId + " (" + kind + ")");
    }

    /** Notes go on the session, so every attendee's record carries what was covered. */
    public Slot saveNotes(String slotId, String notes) {
        Slot s = slots.findById(slotId).orElseThrow();
        s.setNotes(notes);
        return slots.save(s);
    }
}

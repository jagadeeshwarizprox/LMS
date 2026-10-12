package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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
    private final MentorCoverService cover;
    private final StandingRoomResolver standing;
    private final MongoTemplate mongo;

    /* how many learners a sweep holds in memory at once */
    private static final int PAGE = 500;

    public ScheduleService(SessionScheduleRepository schedules, SlotRepository slots,
                           BookingRepository bookings, AttendanceRepository attendance,
                           LearnerRepository learners, UserRepository users,
                           MeetingRoomRepository rooms, MessageService messages,
                           ActivityService activity, SettingsService settings,
                           MentorCoverService cover, StandingRoomResolver standing,
                           MongoTemplate mongo) {
        this.mongo = mongo;
        this.schedules = schedules; this.slots = slots; this.bookings = bookings;
        this.attendance = attendance; this.learners = learners; this.users = users;
        this.rooms = rooms; this.messages = messages; this.activity = activity;
        this.settings = settings;
        this.cover = cover;
        this.standing = standing;
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
            /* the same rule assertMayEditSchedule applies: being on the rotation lets a
               mentor see a schedule as theirs, but pausing or changing it is the owner's.
               This said yes to rotation hosts and the server then refused them. */
            boolean owner = userId != null && userId.equals(r.get("ownerId"));
            r.put("canEdit", admin || (owner && mayCreate));
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

    /**
     * Running a session that already exists: marking attendance, writing notes, calling
     * it off or moving it.
     *
     * This is not the same act as creating one, so session.mentorsMayCreate does not
     * apply. With that switch off the office plans the week, but the mentor still has to
     * take the register for their own session, and assertMayEdit refused them. A mentor
     * covering for the host (an active cover, today inside its dates) runs the session
     * in their place, so they pass too. Anybody else's session stays refused.
     */
    public Slot assertMayRunSlot(String userId, String role, String slotId) {
        Slot s = slots.findById(slotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such session."));
        if ("ADMIN".equals(role) || "SUPER_ADMIN".equals(role)) return s;
        if (userId != null && (userId.equals(s.getMentorId())
                || (s.getMentorId() != null && cover.canActOn(userId, s.getMentorId())))) {
            return s;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "That session is somebody else's. Ask the office to move it.");
    }

    /** The same rule as creating, read off the session itself rather than the request. */
    public void assertMayEditSlot(String userId, String role, String slotId) {
        Slot s = slots.findById(slotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such session."));
        assertMayEdit(userId, role, s.getMentorId());
    }

    public SessionSchedule save(SessionSchedule body, String mentorId) {
        if (body.getMentorId() == null) body.setMentorId(mentorId);
        if (body.getRoomId() == null) {
            standingRoom(body.getMentorId()).ifPresent(r -> body.setRoomId(r.getId()));
        }
        SessionSchedule saved = schedules.save(body);
        generate();                       // the first slots appear immediately
        return saved;
    }

    /** Fields a caller may never set on a schedule through the edit form. */
    private static final Set<String> SCHEDULE_PROTECTED = Set.of("id", "skippedKeys");

    /**
     * Saving a repeating schedule from the board, new or existing.
     *
     * The body used to be bound straight onto a SessionSchedule and saved, so a request
     * carrying an id replaced the stored row whole: anybody could overwrite any schedule
     * by id, and a form that only sent the time reset every other field to its default.
     * An existing schedule is now loaded, checked against the same ownership rule as
     * pausing it, and only the fields actually sent are written onto it. A new one from
     * a mentor is always theirs.
     */
    public SessionSchedule saveFromBoard(Map<String, Object> body, String userId, String role) {
        boolean admin = "ADMIN".equals(role) || "SUPER_ADMIN".equals(role);
        Map<String, Object> patch = new LinkedHashMap<>();
        body.forEach((k, v) -> {
            if (v != null && !SCHEDULE_PROTECTED.contains(k)) patch.put(k, v);
        });
        if (!admin) {
            /* a mentor cannot hand a schedule to somebody else or set a rotation */
            patch.remove("mentorId");
            patch.remove("hostRotation");
        }

        Object idv = body.get("id");
        String id = idv == null || String.valueOf(idv).isBlank() ? null : String.valueOf(idv);
        if (id != null) {
            assertMayEditSchedule(userId, role, id);
            SessionSchedule stored = schedules.findById(id).orElseThrow();
            String oldHost = stored.getMentorId();
            String oldRoom = stored.getRoomId();
            applySchedule(stored, patch);
            /* handing a schedule to another host left it pointing at the old host's standing
               room, so learners joined the wrong mentor's call. A room chosen on purpose (sent
               in the patch, or a pinned one) is left alone; only the old standing room follows */
            String newHost = stored.getMentorId();
            if (newHost != null && !newHost.equals(oldHost) && !patch.containsKey("roomId")
                    && oldRoom != null && oldRoom.equals(standingRoomId(oldHost))) {
                stored.setRoomId(standingRoom(newHost).map(MeetingRoom::getId).orElse(null));
            }
            SessionSchedule saved = schedules.save(stored);
            generate();
            return saved;
        }

        assertMayEdit(userId, role, admin ? null : (String) body.get("mentorId"));
        SessionSchedule fresh = new SessionSchedule();
        applySchedule(fresh, patch);
        if (!admin) {
            fresh.setMentorId(userId);
            fresh.setHostRotation(new ArrayList<>());
        }
        return saveFromBoard(fresh);
    }

    /** Writes only the keys present in the patch; everything else stays as stored. */
    private void applySchedule(SessionSchedule sc, Map<String, Object> p) {
        if (p.containsKey("kind")) sc.setKind(str(p, "kind", sc.getKind()));
        if (p.containsKey("weekday")) sc.setWeekday(num(p, "weekday", sc.getWeekday()));
        if (p.containsKey("startTime")) sc.setStartTime(str(p, "startTime", sc.getStartTime()));
        if (p.containsKey("durationMin")) sc.setDurationMin(num(p, "durationMin", sc.getDurationMin()));
        if (p.containsKey("mentorId")) sc.setMentorId(str(p, "mentorId", sc.getMentorId()));
        if (p.containsKey("roomId")) sc.setRoomId(str(p, "roomId", sc.getRoomId()));
        if (p.containsKey("trackScope")) sc.setTrackScope(str(p, "trackScope", sc.getTrackScope()));
        if (p.containsKey("batchId")) sc.setBatchId(str(p, "batchId", null));
        if (p.containsKey("capacity")) sc.setCapacity(num(p, "capacity", sc.getCapacity()));
        if (p.containsKey("active")) sc.setActive(bool(p.get("active"), sc.isActive()));
        if (p.containsKey("label")) sc.setLabel(str(p, "label", null));
        if (p.containsKey("topic")) sc.setTopic(str(p, "topic", null));
        if (p.containsKey("speaker")) sc.setSpeaker(str(p, "speaker", null));
        if (p.containsKey("openToAllBatches")) {
            sc.setOpenToAllBatches(bool(p.get("openToAllBatches"), sc.isOpenToAllBatches()));
        }
        if (p.containsKey("published")) sc.setPublished(bool(p.get("published"), sc.isPublished()));
        if (p.get("hostRotation") instanceof List<?> hr) {
            sc.setHostRotation(hr.stream().filter(Objects::nonNull).map(String::valueOf)
                    .collect(Collectors.toCollection(ArrayList::new)));
        }
        if (sc.getWeekday() < 1 || sc.getWeekday() > 7) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick a day of the week.");
        }
    }

    private static boolean bool(Object v, boolean fallback) {
        if (v instanceof Boolean b) return b;
        if (v == null) return fallback;
        return Boolean.parseBoolean(String.valueOf(v));
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
            /* a count over the scheduleId index, not every slot ever made */
            m.put("upcoming", mongo.count(Query.query(Criteria.where("scheduleId").is(s.getId())
                    .and("cancelled").ne(true).and("startsAt").gt(Instant.now())), Slot.class));
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
        /*
         * What already exists is every key a slot was generated under, whether it has
         * since moved or been cancelled, plus every occurrence deliberately taken off.
         * Keyed on the current start time, a moved slot left its original time looking
         * empty and a dropped one left nothing at all, and both came back overnight.
         */
        /*
         * Only what could collide is read: the keys this run would create (an occurrence
         * at or after now, inside the horizon, for an active schedule) and the slots of
         * those schedules now standing inside that window. Both go over the
         * (scheduleId, generatedKey) and startsAt indexes, where this used to load every
         * slot ever generated, a collection that only grows.
         */
        List<SessionSchedule> active = schedules.findByActiveTrue();
        Instant nowAt = Instant.now();
        Set<String> candidates = new HashSet<>();
        for (SessionSchedule sc : active) {
            for (int i = 0; i <= HORIZON_DAYS; i++) {
                LocalDate d = today.plusDays(i);
                if (d.getDayOfWeek().getValue() != sc.getWeekday()) continue;
                Instant at = ZonedDateTime.of(d, LocalTime.parse(sc.getStartTime()), IST).toInstant();
                if (!at.isBefore(nowAt)) candidates.add(sc.getId() + "@" + at);
            }
        }
        if (candidates.isEmpty()) return;
        List<String> activeIds = active.stream().map(SessionSchedule::getId).toList();
        Set<String> existing = new HashSet<>();
        for (Slot s : mongo.find(Query.query(Criteria.where("scheduleId").in(activeIds)
                .and("generatedKey").in(candidates)), Slot.class)) {
            existing.add(s.getGeneratedKey());
        }
        Instant horizonEnd = today.plusDays(HORIZON_DAYS + 1).atStartOfDay(IST).toInstant();
        for (Slot s : mongo.find(Query.query(Criteria.where("scheduleId").in(activeIds)
                .and("startsAt").gte(nowAt.minusSeconds(60)).lt(horizonEnd)), Slot.class)) {
            if (s.getGeneratedKey() != null) existing.add(s.getGeneratedKey());
            existing.add(s.getScheduleId() + "@" + s.getStartsAt());
        }
        for (SessionSchedule sc : active) {
            if (sc.getSkippedKeys() != null) existing.addAll(sc.getSkippedKeys());
        }

        for (SessionSchedule sc : active) {
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
                s.setGeneratedKey(sc.getId() + "@" + at);
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

        /* one week over the startsAt index; hosts and booking counts in one read each */
        List<Slot> week = slotsBetween(fromAt, toAt);
        Map<String, String> hosts = new HashMap<>();
        users.findAllById(week.stream().map(Slot::getMentorId).filter(Objects::nonNull)
                .collect(Collectors.toSet())).forEach(u -> hosts.put(u.getId(), u.getFullName()));
        Map<String, Long> booked = bookingCounts(week.stream().map(Slot::getId).toList());
        List<Map<String, Object>> rows = week.stream()
                .map(s -> boardRow(s, hosts, booked))
                .collect(Collectors.toList());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("weekStart", from.toString());
        m.put("weekEnd", to.minusDays(1).toString());
        m.put("sessions", rows);
        m.put("published", rows.stream().filter(r -> Boolean.TRUE.equals(r.get("published"))).count());
        m.put("draft", rows.stream().filter(r -> Boolean.FALSE.equals(r.get("published"))).count());
        return m;
    }

    /** Slots starting in [from, to), sorted by start, over the startsAt index. */
    private List<Slot> slotsBetween(Instant from, Instant to) {
        return mongo.find(Query.query(Criteria.where("startsAt").gte(from).lt(to))
                .with(Sort.by(Sort.Direction.ASC, "startsAt")), Slot.class);
    }

    /** Bookings per slot for a set of slots, as one grouped count. */
    private Map<String, Long> bookingCounts(Collection<String> slotIds) {
        Map<String, Long> out = new HashMap<>();
        if (slotIds.isEmpty()) return out;
        for (org.bson.Document d : mongo.aggregate(Aggregation.newAggregation(
                        Aggregation.match(Criteria.where("slotId").in(slotIds)),
                        Aggregation.group("slotId").count().as("n")),
                Booking.class, org.bson.Document.class).getMappedResults()) {
            Object n = d.get("n");
            out.put(String.valueOf(d.get("_id")), n instanceof Number x ? x.longValue() : 0L);
        }
        return out;
    }

    private Map<String, Object> boardRow(Slot s, Map<String, String> hosts, Map<String, Long> booked) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("kind", s.getKind());
        m.put("startsAt", s.getStartsAt());
        m.put("durationMin", s.getDurationMin());
        m.put("weekday", s.getStartsAt().atZone(IST).getDayOfWeek()
                .getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH));
        m.put("time", s.getStartsAt().atZone(IST).toLocalTime().toString());
        m.put("hostId", s.getMentorId());
        m.put("host", s.getMentorId() == null || hosts.get(s.getMentorId()) == null ? "Unassigned"
                : hosts.get(s.getMentorId()));
        m.put("trackScope", s.getTrackScope());
        m.put("batchId", s.getBatchId());
        m.put("openToAllBatches", s.isOpenToAllBatches());
        m.put("topic", s.getTopic());
        m.put("speaker", s.getSpeaker());
        m.put("capacity", s.getCapacity());
        m.put("booked", booked.getOrDefault(s.getId(), 0L));
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
        String joinUrl = Validate.link(str(body, "joinUrl", null), "Join link");
        if (joinUrl == null) {
            if (s.getMentorId() != null) standingRoom(s.getMentorId()).ifPresent(r -> s.setRoomId(r.getId()));
            return slots.save(s);
        }
        /* the slot needs an id before a room can be pinned to it */
        Slot saved = slots.save(s);
        saved.setRoomId(pinRoom(saved, joinUrl,
                str(body, "topic", "Session link"),
                str(body, "provider", "ZOOM"),
                str(body, "passcode", null)).getId());
        return slots.save(saved);
    }

    /** The id of a host's standing room, or null when there is no host or no room. */
    private String standingRoomId(String hostId) {
        return standingRoom(hostId).map(MeetingRoom::getId).orElse(null);
    }

    /** The mentor's standing room, never one pinned to a single session (see StandingRoomResolver). */
    private Optional<MeetingRoom> standingRoom(String ownerId) {
        return standing.standingRoom(ownerId);
    }

    /**
     * The room behind one session, created or corrected in place.
     *
     * A link typed into the session form used to be dropped unless it made it down the
     * one path that happened to build a room out of it, and there was no path at all for
     * changing it afterwards. Reusing the room a session already owns matters: replacing
     * it would leave the old row behind and, worse, would not follow for anybody who had
     * already been handed the link.
     */
    private MeetingRoom pinRoom(Slot slot, String joinUrl, String label,
                                String provider, String passcode) {
        MeetingRoom current = slot.getRoomId() == null ? null
                : rooms.findById(slot.getRoomId()).orElse(null);
        /*
         * Only a room pinned to this very session is edited in place. Anything else,
         * the host's standing room above all, is shared with every other session that
         * points at it: editing it here used to move all of the mentor's sessions to the
         * new link and wipe the passcode on their standing room. A room of its own is
         * made for this session instead, and only this session is pointed at it.
         */
        boolean own = current != null && current.isPinned()
                && (current.getPinnedSlotId() == null || current.getPinnedSlotId().equals(slot.getId()));
        MeetingRoom room = own ? current : new MeetingRoom();
        if (!own) {
            room.setProvider(provider != null ? provider
                    : current != null && current.getProvider() != null ? current.getProvider() : "ZOOM");
            /* the same meeting re-entered keeps the passcode it already had */
            if (passcode == null && current != null && joinUrl.equals(current.getJoinUrl())) {
                room.setPasscode(current.getPasscode());
            }
        } else if (provider != null) {
            room.setProvider(provider);
        }
        room.setPinned(true);
        room.setPinnedSlotId(slot.getId());
        room.setOwnerId(slot.getMentorId());
        if (label != null) room.setLabel(label);
        else if (room.getLabel() == null) room.setLabel("Session link");
        room.setJoinUrl(joinUrl);
        /* a passcode left out is left as it was, not cleared */
        if (passcode != null) room.setPasscode(passcode);
        return rooms.save(room);
    }

    /**
     * Editing a session that already exists.
     *
     * There was no way to. The board could add and it could reassign, and everything
     * else about a session, the time it starts, who it is for, the link it runs on, was
     * fixed at the moment it was created. A time typed wrongly could only be worked
     * around by adding a second session next to the first.
     *
     * Anything the caller leaves out is left alone, so a drawer that only changes the
     * time does not have to resend the audience.
     */
    public Slot updateOne(String slotId, Map<String, Object> body) {
        Slot s = slots.findById(slotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such session."));
        if (body.containsKey("kind")) s.setKind(str(body, "kind", s.getKind()));
        if (body.containsKey("trackScope")) s.setTrackScope(str(body, "trackScope", s.getTrackScope()));
        if (body.containsKey("batchId")) s.setBatchId(str(body, "batchId", null));
        if (body.containsKey("topic")) s.setTopic(str(body, "topic", null));
        if (body.containsKey("speaker")) s.setSpeaker(str(body, "speaker", null));
        if (body.containsKey("openToAllBatches")) {
            s.setOpenToAllBatches(!Boolean.FALSE.equals(body.get("openToAllBatches")));
        }
        if (body.containsKey("published")) s.setPublished(Boolean.TRUE.equals(body.get("published")));
        if (body.containsKey("durationMin")) s.setDurationMin(num(body, "durationMin", s.getDurationMin()));
        if (body.containsKey("capacity")) {
            int seats = num(body, "capacity", s.getCapacity());
            long taken = bookings.countBySlotId(s.getId());
            if (seats < taken) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        taken + " people have already booked this one, so it cannot hold " + seats + ".");
            }
            s.setCapacity(seats);
        }
        if (body.containsKey("startsAt")) {
            String at = str(body, "startsAt", null);
            if (at == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the session a date and time.");
            }
            keepGeneratedKey(s);
            s.setStartsAt(Instant.parse(at));
        }
        if (body.containsKey("hostId")) {
            String host = str(body, "hostId", null);
            if (host != null) s.setMentorId(host);
        }
        if (body.containsKey("joinUrl")) {
            String joinUrl = Validate.link(str(body, "joinUrl", null), "Join link");
            if (joinUrl == null) {
                /* cleared on purpose: fall back to whoever is hosting */
                s.setRoomId(null);
                if (s.getMentorId() != null) {
                    standingRoom(s.getMentorId()).ifPresent(r -> s.setRoomId(r.getId()));
                }
            } else {
                s.setRoomId(pinRoom(s, joinUrl, s.getTopic(), str(body, "provider", null),
                        str(body, "passcode", null)).getId());
            }
        }
        return slots.save(s);
    }

    /**
     * Calling a session off, and taking one off the board entirely.
     *
     * A session nobody has booked is a planning mistake and is deleted. One with people
     * on it is cancelled instead and stays visible with its reason, because a learner who
     * has it in their week needs to be told it is off rather than have it disappear.
     */
    public Map<String, Object> dropOne(String slotId, String reason) {
        Slot s = slots.findById(slotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such session."));
        long taken = bookings.countBySlotId(slotId);
        if (taken == 0) {
            /* remember the occurrence was taken off, or tonight's run puts it back */
            if (s.getScheduleId() != null) {
                String key = s.getGeneratedKey() != null ? s.getGeneratedKey()
                        : s.getScheduleId() + "@" + s.getStartsAt();
                schedules.findById(s.getScheduleId()).ifPresent(sc -> {
                    sc.getSkippedKeys().add(key);
                    schedules.save(sc);
                });
            }
            slots.delete(s);
            return Map.of("deleted", true, "told", 0);
        }
        s.setCancelled(true);
        s.setCancelReason(reason == null || reason.isBlank() ? "Called off" : reason.trim());
        s.setOpen(false);
        slots.save(s);
        return Map.of("deleted", false, "told", taken);
    }

    /** Change who is taking one occurrence, without touching the schedule behind it. */
    public Slot reassign(String slotId, String mentorId) {
        Slot s = slots.findById(slotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such session."));
        /* a session on its own pinned link keeps it; one on the old host's standing
           room moves to the new host's */
        MeetingRoom cur = s.getRoomId() == null ? null : rooms.findById(s.getRoomId()).orElse(null);
        s.setMentorId(mentorId);
        if (cur != null && cur.isPinned()) {
            cur.setOwnerId(mentorId);
            rooms.save(cur);
        } else {
            standingRoom(mentorId).ifPresent(r -> s.setRoomId(r.getId()));
        }
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
        for (Slot s : slotsBetween(fromAt, toAt)) {
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
            /* learners not on hold, a page of PAGE at a time by _id, rather than the whole
               collection in memory at once */
            String after = null;
            while (true) {
                List<Learner> page = learnerPage(Criteria.where("onHold").ne(true), after);
                if (page.isEmpty()) break;
                for (Learner l : page) {
                    if (released.stream().noneMatch(x -> inScopeFor(x, l))) continue;
                    messages.send(l.getId(), null, "ProITBridge",
                            "This week's sessions", body, "ANNOUNCEMENT");
                    told++;
                }
                if (page.size() < PAGE) break;
                after = page.get(page.size() - 1).getId();
            }
        }
        return Map.of("changed", n, "published", published, "told", told);
    }

    /**
     * One page of learners matching `filter` (null for all), after the id `after` (null for
     * the first page). Paging by _id keeps each page an index range scan however far in.
     * The id goes in as the string Spring maps it to, so ObjectId and plain ids both work.
     */
    private List<Learner> learnerPage(Criteria filter, String after) {
        Query q = filter == null ? new Query() : new Query(filter);
        if (after != null) q.addCriteria(Criteria.where("_id").gt(after));
        return mongo.find(q.with(Sort.by(Sort.Direction.ASC, "_id")).limit(PAGE), Learner.class);
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
            case "DEBATE" -> "Debate session";
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
        Slot s = slots.findById(slotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such session."));
        Instant was = s.getStartsAt();
        keepGeneratedKey(s);
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

    /** A generated slot made before keys existed takes its key from where it stands now,
     *  before it moves, so the generator still recognises the original occurrence. */
    private void keepGeneratedKey(Slot s) {
        if (s.getScheduleId() != null && s.getGeneratedKey() == null && s.getStartsAt() != null) {
            s.setGeneratedKey(s.getScheduleId() + "@" + s.getStartsAt());
        }
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
            /* the same three rules, as a query: the batch, the track, and the mentor's own
               learners unless the session is a big group one */
            Criteria c = null;
            boolean any = false;
            if (s.getBatchId() != null) { c = Criteria.where("batchId").is(s.getBatchId()); any = true; }
            if (s.getTrackScope() != null && !"BOTH".equals(s.getTrackScope())) {
                c = any ? c.and("trackType").is(s.getTrackScope())
                        : Criteria.where("trackType").is(s.getTrackScope());
                any = true;
            }
            if (s.getCapacity() <= 5) {
                c = any ? c.and("mentorId").is(s.getMentorId())
                        : Criteria.where("mentorId").is(s.getMentorId());
            }
            expected = new ArrayList<>();
            String after = null;
            while (true) {
                List<Learner> page = learnerPage(c, after);
                if (page.isEmpty()) break;
                for (Learner l : page) expected.add(l.getId());
                if (page.size() < PAGE) break;
                after = page.get(page.size() - 1).getId();
            }
        }

        /* names for the whole sheet in two reads, not two per row */
        Map<String, String> userOf = new HashMap<>();
        learners.findAllById(new HashSet<>(expected)).forEach(l -> userOf.put(l.getId(), l.getUserId()));
        Map<String, String> nameOf = new HashMap<>();
        users.findAllById(userOf.values().stream().filter(Objects::nonNull).collect(Collectors.toSet()))
                .forEach(u -> nameOf.put(u.getId(), u.getFullName()));
        List<Map<String, Object>> rows = expected.stream().map(id -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("learnerId", id);
            String uid = userOf.get(id);
            String nm = uid == null ? null : nameOf.get(uid);
            m.put("name", nm == null ? "Learner" : nm);
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

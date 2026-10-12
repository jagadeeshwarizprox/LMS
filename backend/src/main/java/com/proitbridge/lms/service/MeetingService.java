package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.stream.Collectors;
import java.util.*;

/**
 * A mentor has one standing room that is always open. Slots say when it is used.
 * The URL lives on the room and nowhere else, and is fetched at the moment the
 * learner presses join, inside the window, so a schedule page never carries a
 * list of live meeting links waiting to be copied out.
 */
@Service
public class MeetingService {

    private final MeetingRoomRepository rooms;
    private final SlotRepository slots;
    private final BookingRepository bookings;
    private final LearnerRepository learners;
    private final UserRepository users;
    private final ActivityService activity;
    private final StandingRoomResolver standing;
    private final int openBeforeMin;
    private final int openAfterMin;
    private final MongoTemplate mongo;

    public MeetingService(MeetingRoomRepository rooms, SlotRepository slots,
                          BookingRepository bookings, LearnerRepository learners,
                          UserRepository users, ActivityService activity,
                          StandingRoomResolver standing,
                          @Value("${lms.meeting.open-before-minutes}") int openBeforeMin,
                          @Value("${lms.meeting.open-after-minutes}") int openAfterMin,
                          MongoTemplate mongo) {
        this.mongo = mongo;
        this.rooms = rooms; this.slots = slots; this.bookings = bookings;
        this.learners = learners; this.users = users; this.activity = activity;
        this.standing = standing;
        this.openBeforeMin = openBeforeMin; this.openAfterMin = openAfterMin;
    }

    public MeetingRoom roomFor(String mentorId) {
        return standingRoom(mentorId).orElse(null);
    }

    /**
     * The mentor's standing room: the one that is not pinned to a single session.
     *
     * A mentor can own several rooms now, one standing and any number made for one
     * session's own link, so "the room whose owner is me" is no longer one row. Rooms
     * the previous version made per session carry no pinned flag, so "oldest non-pinned"
     * picked them; StandingRoomResolver decides by how the room is used instead.
     */
    public Optional<MeetingRoom> standingRoom(String mentorId) {
        return standing.standingRoom(mentorId);
    }

    public MeetingRoom saveRoom(String mentorId, String joinUrl, String passcode, String label) {
        MeetingRoom r = standingRoom(mentorId).orElseGet(MeetingRoom::new);
        r.setOwnerId(mentorId);
        r.setJoinUrl(joinUrl);
        r.setPasscode(passcode);
        /* a form that does not send the label keeps the one already there */
        if (label != null && !label.isBlank()) r.setLabel(label);
        else if (r.getLabel() == null) {
            r.setLabel(users.findById(mentorId).map(User::getFullName).orElse("Room"));
        }
        return rooms.save(r);
    }

    /**
     * The room a slot actually uses.
     *
     * A slot gets a roomId copied onto it when its schedule is saved, which means a link
     * set afterwards never reaches any slot already created: the copy is stale forever and
     * join answers that no link is set. Resolving through the mentor's current room at
     * read time fixes that without a migration, and the slot's own roomId still wins when
     * it has one, so a session deliberately pointed at another room keeps working.
     */
    public MeetingRoom roomForSlot(Slot s) {
        if (s.getRoomId() != null) {
            MeetingRoom pinned = rooms.findById(s.getRoomId()).orElse(null);
            if (pinned != null && pinned.getJoinUrl() != null && !pinned.getJoinUrl().isBlank()) {
                return pinned;
            }
        }
        if (s.getMentorId() == null) return null;
        return standingRoom(s.getMentorId())
                .filter(r -> r.getJoinUrl() != null && !r.getJoinUrl().isBlank())
                .orElse(null);
    }

    /** Whether the join button should be live, without giving away the link. */
    public Map<String, Object> windowFor(Slot s) {
        Instant now = Instant.now();
        Instant opens = s.getStartsAt().minus(openBeforeMin, ChronoUnit.MINUTES);
        Instant closes = s.getStartsAt().plus(s.getDurationMin() + openAfterMin, ChronoUnit.MINUTES);
        boolean open = now.isAfter(opens) && now.isBefore(closes);
        return Map.of("open", open, "opensAt", opens, "closesAt", closes,
                "openBeforeMin", openBeforeMin,
                "hasRoom", roomForSlot(s) != null);
    }

    /**
     * Every upcoming session and what would actually happen if somebody pressed join.
     *
     * The same idea as the video check on a course. Whether a session is joinable depends
     * on a room, a link on that room, the time window and the track scope, and each of
     * those can be individually fine while a learner still cannot get in. Reading it once
     * beats finding out at the start of the call.
     */
    public List<Map<String, Object>> joinCheck() {
        Instant now = Instant.now();
        /* the next forty straight off the startsAt index, and their hosts in one read */
        List<Slot> next = mongo.find(Query.query(Criteria.where("startsAt").gt(now.minus(1, ChronoUnit.DAYS)))
                .with(Sort.by(Sort.Direction.ASC, "startsAt")).limit(40), Slot.class);
        Map<String, String> hosts = new HashMap<>();
        users.findAllById(next.stream().map(Slot::getMentorId).filter(Objects::nonNull)
                .collect(Collectors.toSet())).forEach(u -> hosts.put(u.getId(), u.getFullName()));
        return next.stream()
                .map(s -> {
                    MeetingRoom room = roomForSlot(s);
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.getId());
                    m.put("kind", s.getKind());
                    m.put("startsAt", s.getStartsAt());
                    m.put("trackScope", s.getTrackScope());
                    m.put("mentor", s.getMentorId() == null ? null : hosts.get(s.getMentorId()));
                    m.put("room", room == null ? null : room.getLabel());
                    if (room == null) {
                        m.put("problem", s.getMentorId() == null
                                ? "Nobody is running it, so there is no room to fall back to."
                                : "No meeting link. Set one under Meeting rooms for whoever runs it.");
                    } else if (s.getRoomId() != null && !s.getRoomId().equals(room.getId())) {
                        m.put("problem", "Its own room has no link, so it falls back to the "
                                + "mentor's current room.");
                    } else {
                        m.put("problem", null);
                    }
                    m.put("window", windowFor(s));
                    return m;
                }).collect(Collectors.toList());
    }

    /** The link itself. Checked twice: the window, then the learner's right to be there. */
    public Map<String, Object> join(String slotId, String userId, String role) {
        Slot s = slots.findById(slotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No such session."));
        Map<String, Object> w = windowFor(s);
        boolean staff = !"LEARNER".equals(role);
        if (!staff && !Boolean.TRUE.equals(w.get("open"))) {
            /* say which it is. "Opens 15 minutes before" on a session two days away reads
               as though the link is broken, and that is the message people report */
            Instant opensAt = (Instant) w.get("opensAt");
            String when = Instant.now().isBefore(opensAt)
                    ? "This session opens " + openBeforeMin + " minutes before it starts, so from "
                      + opensAt + "."
                    : "This session has already finished.";
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, when);
        }
        if (!staff) {
            Learner l = learners.findByUserId(userId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a learner account."));
            boolean booked = bookings.findBySlotIdAndLearnerId(slotId, l.getId()).isPresent();
            boolean inScope = "BOTH".equals(s.getTrackScope())
                    || s.getTrackScope().equals(l.getTrackType().name());
            boolean batchOk = s.getBatchId() == null || s.getBatchId().equals(l.getBatchId());
            if (!(booked || (inScope && batchOk))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "This session is not part of your track.");
            }
        }
        MeetingRoom room = roomForSlot(s);
        if (room == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No meeting link is set for this session yet. Whoever runs it needs to "
                    + "add their room link under Meeting rooms.");
        }
        activity.log(userId, null, "JOIN_SESSION", "slot", slotId);
        return Map.of("joinUrl", room.getJoinUrl(),
                "passcode", room.getPasscode() == null ? "" : room.getPasscode());
    }
}

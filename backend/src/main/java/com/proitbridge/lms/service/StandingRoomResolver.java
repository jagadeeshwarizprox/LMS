package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.MeetingRoom;
import com.proitbridge.lms.repo.MeetingRoomRepository;
import com.proitbridge.lms.repo.SessionScheduleRepository;
import com.proitbridge.lms.repo.SlotRepository;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Which of a mentor's rooms is their standing room.
 *
 * "Not pinned, oldest wins" was the whole rule, and it picked the wrong room for anybody
 * upgraded from the previous version. That version made a room per session for a link
 * typed into one session, owned by the mentor and with no pinned flag (the flag did not
 * exist), and those rows are often older than the standing room. The oldest then won,
 * and every session the mentor runs started resolving to one old session's link.
 *
 * What tells them apart is how they are used. The standing room is the one schedules
 * copy onto their slots, so it is referenced by a schedule or by many slots; a per
 * session room is referenced by exactly one slot and nothing else. So, in order:
 *   1. a non-pinned room a schedule points at, or that more than one slot points at;
 *   2. a non-pinned room no slot points at yet (a standing room set up before any use);
 *   3. failing both, the oldest non-pinned room, as before.
 * Ties inside a tier go to the oldest, so the answer is stable between calls.
 *
 * MeetingService and ScheduleService both asked this question with their own copy of
 * the old rule; they share this one so they cannot disagree.
 */
@Component
public class StandingRoomResolver {

    private static final Comparator<MeetingRoom> OLDEST =
            Comparator.comparing(MeetingRoom::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private final MeetingRoomRepository rooms;
    private final SlotRepository slots;
    private final SessionScheduleRepository schedules;

    public StandingRoomResolver(MeetingRoomRepository rooms, SlotRepository slots,
                                SessionScheduleRepository schedules) {
        this.rooms = rooms; this.slots = slots; this.schedules = schedules;
    }

    public Optional<MeetingRoom> standingRoom(String ownerId) {
        if (ownerId == null) return Optional.empty();
        List<MeetingRoom> open = rooms.findByOwnerId(ownerId).stream()
                .filter(r -> !r.isPinned())
                .sorted(OLDEST)
                .toList();
        if (open.size() <= 1) return open.stream().findFirst();

        /* one count per room, worked out once rather than inside each pass */
        record Use(MeetingRoom room, long slotRefs, boolean scheduled) { }
        List<Use> uses = open.stream()
                .map(r -> new Use(r,
                        r.getId() == null ? 0 : slots.countByRoomId(r.getId()),
                        r.getId() != null && schedules.existsByRoomId(r.getId())))
                .toList();

        return uses.stream().filter(u -> u.scheduled() || u.slotRefs() > 1)
                .map(Use::room).findFirst()
                .or(() -> uses.stream().filter(u -> u.slotRefs() == 0).map(Use::room).findFirst())
                .or(() -> open.stream().findFirst());
    }
}

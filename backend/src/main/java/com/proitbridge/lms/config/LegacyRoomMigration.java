package com.proitbridge.lms.config;

import com.proitbridge.lms.domain.MeetingRoom;
import com.proitbridge.lms.domain.Slot;
import com.proitbridge.lms.repo.MeetingRoomRepository;
import com.proitbridge.lms.repo.SessionScheduleRepository;
import com.proitbridge.lms.repo.SlotRepository;
import com.proitbridge.lms.service.StandingRoomResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Pins the per-session rooms the previous version left unpinned.
 *
 * That version made a room for a link typed into one session, owned by the mentor, before
 * the pinned flag existed, so those rows read as standing rooms. StandingRoomResolver
 * already prefers the right one at read time; this puts the flag where it belongs so
 * nothing else has to keep guessing.
 *
 * A room is pinned when exactly one slot points at it, no schedule does, and it is not
 * the room the resolver picks as the owner's standing room, so the owner always keeps
 * another room to stand on. Idempotent: pinned rooms are skipped, so a second start
 * finds nothing to do.
 */
@Configuration
public class LegacyRoomMigration {

    private static final Logger log = LoggerFactory.getLogger(LegacyRoomMigration.class);

    @Bean
    ApplicationRunner pinLegacySessionRooms(MeetingRoomRepository rooms, SlotRepository slots,
                                            SessionScheduleRepository schedules,
                                            StandingRoomResolver standing) {
        return args -> {
            Map<String, List<MeetingRoom>> byOwner = rooms.findAll().stream()
                    .filter(r -> !r.isPinned() && r.getOwnerId() != null && r.getId() != null)
                    .collect(Collectors.groupingBy(MeetingRoom::getOwnerId));
            int pinned = 0;
            for (var e : byOwner.entrySet()) {
                if (e.getValue().size() < 2) continue;   // their only room is their standing room
                String keep = standing.standingRoom(e.getKey()).map(MeetingRoom::getId).orElse(null);
                for (MeetingRoom r : e.getValue()) {
                    if (Objects.equals(r.getId(), keep)) continue;
                    if (schedules.existsByRoomId(r.getId())) continue;
                    List<Slot> using = slots.findByRoomId(r.getId());
                    if (using.size() != 1) continue;
                    r.setPinned(true);
                    r.setPinnedSlotId(using.get(0).getId());
                    rooms.save(r);
                    pinned++;
                }
            }
            if (pinned > 0) log.info("Pinned {} per-session meeting room(s) left by the previous version", pinned);
        };
    }
}

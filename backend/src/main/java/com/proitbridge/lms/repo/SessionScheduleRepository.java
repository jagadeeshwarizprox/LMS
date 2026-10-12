package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface SessionScheduleRepository extends MongoRepository<SessionSchedule, String> {
    List<SessionSchedule> findByActiveTrue();
    List<SessionSchedule> findByMentorId(String mentorId);
    /* a schedule only ever points at a standing room, never at a per-session one */
    boolean existsByRoomId(String roomId);
}

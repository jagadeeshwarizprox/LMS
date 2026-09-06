package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface SessionScheduleRepository extends MongoRepository<SessionSchedule, String> {
    List<SessionSchedule> findByActiveTrue();
    List<SessionSchedule> findByMentorId(String mentorId);
}

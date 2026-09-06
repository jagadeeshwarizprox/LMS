package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface AttendanceRepository extends MongoRepository<Attendance, String> {
    List<Attendance> findBySlotId(String slotId);
    List<Attendance> findByLearnerId(String learnerId);
}

package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.time.LocalDate;
import java.util.*;

public interface StudySessionRepository extends MongoRepository<StudySession, String> {
    List<StudySession> findByLearnerIdAndClosedBy(String learnerId, String closedBy);
    List<StudySession> findByLearnerIdAndDay(String learnerId, LocalDate day);
    List<StudySession> findByClosedBy(String closedBy);
}

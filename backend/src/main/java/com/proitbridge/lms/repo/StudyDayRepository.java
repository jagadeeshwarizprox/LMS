package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.time.LocalDate;
import java.util.*;

public interface StudyDayRepository extends MongoRepository<StudyDay, String> {
    List<StudyDay> findByLearnerIdOrderByDayDesc(String learnerId);
    Optional<StudyDay> findByLearnerIdAndDay(String learnerId, LocalDate day);
    List<StudyDay> findByDay(LocalDate day);
}

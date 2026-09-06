package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface ProgressCallRepository extends MongoRepository<ProgressCall, String> {
    List<ProgressCall> findByLearnerIdOrderByScheduledForDesc(String learnerId);
    List<ProgressCall> findByMentorId(String mentorId);
}

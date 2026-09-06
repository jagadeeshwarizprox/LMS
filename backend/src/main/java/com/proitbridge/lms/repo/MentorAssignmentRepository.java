package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface MentorAssignmentRepository extends MongoRepository<MentorAssignment, String> {
    List<MentorAssignment> findByLearnerIdOrderByAtDesc(String learnerId);
}

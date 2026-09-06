package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface AssignmentRepository extends MongoRepository<Assignment, String> {
    List<Assignment> findByLearnerId(String learnerId);
    List<Assignment> findByLearnerIdIn(Collection<String> learnerIds);
    List<Assignment> findByLearnerIdInAndStatus(Collection<String> learnerIds, String status);
    Optional<Assignment> findFirstByLearnerIdAndChapterIdOrderBySubmittedAtDesc(String learnerId, String chapterId);
}

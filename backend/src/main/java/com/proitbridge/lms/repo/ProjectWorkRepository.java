package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface ProjectWorkRepository extends MongoRepository<ProjectWork, String> {
    List<ProjectWork> findByLearnerId(String learnerId);
    List<ProjectWork> findByLearnerIdIn(Collection<String> learnerIds);
    long countByLearnerIdAndStatus(String learnerId, String status);
}

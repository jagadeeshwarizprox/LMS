package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface ProgressRepository extends MongoRepository<Progress, String> {
    List<Progress> findByLearnerId(String learnerId);
    Optional<Progress> findByLearnerIdAndChapterId(String learnerId, String chapterId);
    List<Progress> findByLearnerIdAndModuleId(String learnerId, String moduleId);
    List<Progress> findByLearnerIdIn(Collection<String> learnerIds);
}

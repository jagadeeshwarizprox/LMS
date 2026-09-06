package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface LearnerFeatureOverrideRepository extends MongoRepository<LearnerFeatureOverride, String> {
    List<LearnerFeatureOverride> findByLearnerId(String learnerId);
    Optional<LearnerFeatureOverride> findByLearnerIdAndFeatureKey(String learnerId, String key);
}

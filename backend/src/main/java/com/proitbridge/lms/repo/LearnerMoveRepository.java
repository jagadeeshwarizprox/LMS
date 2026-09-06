package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface LearnerMoveRepository extends MongoRepository<LearnerMove, String> {
    List<LearnerMove> findByLearnerIdOrderByAtDesc(String learnerId);
}

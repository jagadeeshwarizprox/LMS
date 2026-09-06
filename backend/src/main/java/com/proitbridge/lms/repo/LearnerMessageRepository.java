package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface LearnerMessageRepository extends MongoRepository<LearnerMessage, String> {
    List<LearnerMessage> findByLearnerIdOrderBySentAtDesc(String learnerId);
    List<LearnerMessage> findByLearnerIdAndReadByLearnerFalse(String learnerId);
}

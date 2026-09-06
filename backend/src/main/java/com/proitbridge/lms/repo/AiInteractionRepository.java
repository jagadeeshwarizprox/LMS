package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface AiInteractionRepository extends MongoRepository<AiInteraction, String> {
    List<AiInteraction> findTop100ByOrderByCreatedAtDesc();
    List<AiInteraction> findByLearnerIdAndKind(String learnerId, String kind);
}

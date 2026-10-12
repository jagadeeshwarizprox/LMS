package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface WatchPositionRepository extends MongoRepository<WatchPosition, String> {
    Optional<WatchPosition> findByLearnerIdAndVideoRef(String learnerId, String videoRef);
    List<WatchPosition> findByLearnerIdOrderByUpdatedAtDesc(String learnerId);

    /* several videos' rows for one learner in a single indexed read */
    List<WatchPosition> findByLearnerIdAndVideoRefIn(String learnerId, Collection<String> videoRefs);
}

package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface LearnerRepository extends MongoRepository<Learner, String> {
    Optional<Learner> findByUserId(String userId);
    List<Learner> findByMentorId(String mentorId);
    List<Learner> findByBatchId(String batchId);
    List<Learner> findByTrackType(Learner.TrackType t);
    long countByTrackType(Learner.TrackType t);
    long countByBatchId(String batchId);
}

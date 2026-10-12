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

    /*
     * Ranking reads, all answered from the progress snapshot on the learner row and the
     * two compound indexes on it. A place is one more than the number strictly ahead, so
     * ties share a place, and these are the "strictly ahead" counts.
     */
    long countByBatchIdAndProgressPercentGreaterThan(String batchId, int percent);
    long countByBundleIdAndOnHoldFalse(String bundleId);
    long countByBundleIdAndOnHoldFalseAndProgressPercentGreaterThan(String bundleId, int percent);

    /** The batch leaderboard: the ten furthest along, straight off the batch index. */
    List<Learner> findTop10ByBatchIdOrderByProgressPercentDesc(String batchId);

    /* mentor visibility: every learner held by any of a set of mentors, over the mentorId
       index, and every learner in a set of batches, over the batch_progress index. One
       read each instead of one per mentor and one per batch */
    List<Learner> findByMentorIdIn(Collection<String> mentorIds);
    List<Learner> findByBatchIdIn(Collection<String> batchIds);
    long countByMentorId(String mentorId);
    boolean existsByBatchIdAndMentorIdIn(String batchId, Collection<String> mentorIds);
}

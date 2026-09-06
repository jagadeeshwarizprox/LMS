package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface MockRequestRepository extends MongoRepository<MockRequest, String> {
    List<MockRequest> findByLearnerId(String learnerId);
    List<MockRequest> findByLearnerIdIn(Collection<String> learnerIds);
    List<MockRequest> findByLearnerIdInAndStatus(Collection<String> learnerIds, String status);
    List<MockRequest> findByLearnerIdInAndStatusIn(Collection<String> learnerIds, Collection<String> statuses);
}

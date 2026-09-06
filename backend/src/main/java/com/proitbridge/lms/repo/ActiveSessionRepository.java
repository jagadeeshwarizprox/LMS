package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface ActiveSessionRepository extends MongoRepository<ActiveSession, String> {
    List<ActiveSession> findByUserIdAndCurrentTrue(String userId);
    List<ActiveSession> findByUserId(String userId);
}

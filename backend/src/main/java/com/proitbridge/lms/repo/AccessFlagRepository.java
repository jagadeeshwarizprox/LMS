package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface AccessFlagRepository extends MongoRepository<AccessFlag, String> {
    List<AccessFlag> findByClearedFalseOrderByCreatedAtDesc();
    List<AccessFlag> findByUserIdAndKindAndClearedFalse(String userId, String kind);
}

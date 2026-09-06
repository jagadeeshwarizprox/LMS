package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.time.Instant;
import java.util.*;

public interface PasswordResetRepository extends MongoRepository<PasswordReset, String> {
    Optional<PasswordReset> findByTokenHash(String tokenHash);
    List<PasswordReset> findByUserIdAndUsedFalse(String userId);
    List<PasswordReset> findByUserIdAndCreatedAtAfter(String userId, Instant since);
}

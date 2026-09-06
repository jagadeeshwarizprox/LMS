package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.time.Instant;
import java.util.*;

public interface LoginAttemptRepository extends MongoRepository<LoginAttempt, String> {
    List<LoginAttempt> findByEmailAndAtAfter(String email, Instant since);
    List<LoginAttempt> findByIpAndAtAfter(String ip, Instant since);
    List<LoginAttempt> findByAtAfter(Instant since);
}

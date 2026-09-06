package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface IntakeFormRepository extends MongoRepository<IntakeForm, String> {
    Optional<IntakeForm> findByLearnerId(String learnerId);
}

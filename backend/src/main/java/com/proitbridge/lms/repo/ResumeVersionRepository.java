package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface ResumeVersionRepository extends MongoRepository<ResumeVersion, String> {
    List<ResumeVersion> findByLearnerIdOrderByVersionDesc(String learnerId);
}

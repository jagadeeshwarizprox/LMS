package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface CaseStudyRepository extends MongoRepository<CaseStudy, String> {
    List<CaseStudy> findByModuleId(String moduleId);
}

package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface ChapterRepository extends MongoRepository<Chapter, String> {
    List<Chapter> findByModuleIdOrderByPositionAsc(String moduleId);
    List<Chapter> findByModuleIdIn(Collection<String> moduleIds);
    long countByModuleId(String moduleId);
}

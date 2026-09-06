package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface ModuleRepository extends MongoRepository<CourseModule, String> {
    Optional<CourseModule> findBySlug(String slug);
    List<CourseModule> findAllByOrderByPositionAsc();
}

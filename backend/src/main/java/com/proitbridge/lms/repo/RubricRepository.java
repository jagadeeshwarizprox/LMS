package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface RubricRepository extends MongoRepository<Rubric, String> {
    List<Rubric> findByScopeAndActiveTrue(String scope);
}

package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface FeatureRepository extends MongoRepository<Feature, String> {
    Optional<Feature> findByKey(String key);
}

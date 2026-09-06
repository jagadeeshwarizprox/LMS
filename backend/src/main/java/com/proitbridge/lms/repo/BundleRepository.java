package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface BundleRepository extends MongoRepository<Bundle, String> {
    Optional<Bundle> findByName(String name);
    List<Bundle> findByActiveTrue();
}

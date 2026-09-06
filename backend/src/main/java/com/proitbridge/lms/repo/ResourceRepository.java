package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface ResourceRepository extends MongoRepository<Resource, String> {
    List<Resource> findByTopicIdOrderByPositionAsc(String topicId);
    List<Resource> findByTopicIdAndActiveTrueOrderByPositionAsc(String topicId);
    long countByTopicId(String topicId);
}

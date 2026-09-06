package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface AssignmentEventRepository extends MongoRepository<AssignmentEvent, String> {
    List<AssignmentEvent> findByAssignmentIdOrderByAtAsc(String assignmentId);
}

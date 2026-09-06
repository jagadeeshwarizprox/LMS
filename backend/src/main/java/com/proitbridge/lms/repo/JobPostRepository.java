package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface JobPostRepository extends MongoRepository<JobPost, String> {
    List<JobPost> findAllByOrderByPostedAtDesc();
}

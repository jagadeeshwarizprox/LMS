package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.time.Instant;
import java.util.*;

public interface VideoAccessLogRepository extends MongoRepository<VideoAccessLog, String> {
    List<VideoAccessLog> findTop200ByOrderByIssuedAtDesc();
    List<VideoAccessLog> findByUserIdAndIssuedAtAfter(String userId, Instant since);
    List<VideoAccessLog> findByVideoId(String videoId);
}

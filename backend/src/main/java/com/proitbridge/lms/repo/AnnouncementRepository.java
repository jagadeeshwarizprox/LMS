package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface AnnouncementRepository extends MongoRepository<Announcement, String> {
    List<Announcement> findByBatchIdOrderByCreatedAtDesc(String batchId);
    List<Announcement> findTop20ByOrderByCreatedAtDesc();
}

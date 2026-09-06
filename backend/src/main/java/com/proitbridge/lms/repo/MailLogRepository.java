package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface MailLogRepository extends MongoRepository<MailLog, String> {
    List<MailLog> findTop100ByOrderBySentAtDesc();
    List<MailLog> findByToEmailOrderBySentAtDesc(String email);
}

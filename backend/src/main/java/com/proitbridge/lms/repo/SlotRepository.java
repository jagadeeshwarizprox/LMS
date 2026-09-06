package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface SlotRepository extends MongoRepository<Slot, String> {
    List<Slot> findByMentorId(String mentorId);
    List<Slot> findByKindAndOpenTrue(String kind);
    List<Slot> findByTrackScopeInAndOpenTrue(Collection<String> scopes);
    List<Slot> findByBatchId(String batchId);
}

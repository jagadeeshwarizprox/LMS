package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface RecordingRepository extends MongoRepository<Recording, String> {
    List<Recording> findByBatchId(String batchId);
    List<Recording> findAllByOrderByHeldOnDesc();
    Optional<Recording> findBySlotId(String slotId);
    List<Recording> findByModuleIdOrderByPartNumberAsc(String moduleId);
    List<Recording> findByChapterId(String chapterId);
    List<Recording> findByKindOrderByHeldOnDesc(String kind);

    /* the recording a video belongs to, by the videoRef index */
    Optional<Recording> findFirstByVideoRef(String videoRef);
    /* recordings of a set of sessions, by the slotId index */
    List<Recording> findBySlotIdIn(Collection<String> slotIds);
}

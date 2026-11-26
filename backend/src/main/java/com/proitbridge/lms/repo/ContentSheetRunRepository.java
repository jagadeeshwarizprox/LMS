package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.ContentSheetRun;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ContentSheetRunRepository extends MongoRepository<ContentSheetRun, String> {
    Optional<ContentSheetRun> findFirstByOrderByStartedAtDesc();
    List<ContentSheetRun> findTop30ByOrderByStartedAtDesc();
}

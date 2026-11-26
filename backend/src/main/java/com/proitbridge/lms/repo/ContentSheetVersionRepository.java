package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.ContentSheetVersion;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ContentSheetVersionRepository extends MongoRepository<ContentSheetVersion, String> {
    Optional<ContentSheetVersion> findFirstByOrderByCreatedAtDesc();
    List<ContentSheetVersion> findTop50ByOrderByCreatedAtDesc();
}

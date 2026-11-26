package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.ContentSheetRow;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ContentSheetRowRepository extends MongoRepository<ContentSheetRow, String> {
    Optional<ContentSheetRow> findByRowKey(String rowKey);
    List<ContentSheetRow> findByTopicId(String topicId);
}

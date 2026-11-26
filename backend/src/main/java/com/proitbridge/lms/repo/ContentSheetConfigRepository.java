package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.ContentSheetConfig;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ContentSheetConfigRepository extends MongoRepository<ContentSheetConfig, String> {
}

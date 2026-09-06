package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface StoredFileRepository extends MongoRepository<StoredFile, String> {
    List<StoredFile> findByLinkedId(String linkedId);
    List<StoredFile> findByOwnerId(String ownerId);
}

package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface FormSectionRepository extends MongoRepository<FormSection, String> {
    List<FormSection> findByActiveTrueOrderByPositionAsc();
    Optional<FormSection> findByKey(String key);
}

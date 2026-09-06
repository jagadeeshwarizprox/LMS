package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.FormField;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface FormFieldRepository extends MongoRepository<FormField, String> {
    List<FormField> findByOrderByPositionAsc();
    List<FormField> findBySectionKeyOrderByPositionAsc(String sectionKey);
    List<FormField> findByActiveTrueOrderByPositionAsc();
}

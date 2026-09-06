package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface BatchRepository extends MongoRepository<Batch, String> {
    Optional<Batch> findByCode(String code);
    List<Batch> findByOpenTrue();
    List<Batch> findAllByOrderByStartDateDesc();
}

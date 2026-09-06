package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface FaqRepository extends MongoRepository<Faq, String> {
    List<Faq> findByActiveTrueOrderByPositionAsc();
    List<Faq> findByPlacementAndActiveTrueOrderByPositionAsc(String placement);
}

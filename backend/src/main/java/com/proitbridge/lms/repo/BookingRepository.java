package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface BookingRepository extends MongoRepository<Booking, String> {
    List<Booking> findByLearnerId(String learnerId);
    List<Booking> findBySlotId(String slotId);
    Optional<Booking> findBySlotIdAndLearnerId(String slotId, String learnerId);
    long countBySlotId(String slotId);
}

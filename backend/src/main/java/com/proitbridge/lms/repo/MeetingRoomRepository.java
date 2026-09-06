package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface MeetingRoomRepository extends MongoRepository<MeetingRoom, String> {
    Optional<MeetingRoom> findByOwnerId(String ownerId);
}

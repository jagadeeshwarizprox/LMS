package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface MeetingRoomRepository extends MongoRepository<MeetingRoom, String> {
    /* every room a mentor owns: their standing room plus any pinned to one session.
       It was an Optional, which throws once there is more than one row. Callers that
       want the standing room filter out the pinned ones. */
    List<MeetingRoom> findByOwnerId(String ownerId);
}

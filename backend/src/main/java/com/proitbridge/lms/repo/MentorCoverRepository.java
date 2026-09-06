package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface MentorCoverRepository extends MongoRepository<MentorCover, String> {
    List<MentorCover> findByActiveTrue();
    List<MentorCover> findByCoveringMentorIdAndActiveTrue(String coveringMentorId);
    List<MentorCover> findByMentorIdAndActiveTrue(String mentorId);
}

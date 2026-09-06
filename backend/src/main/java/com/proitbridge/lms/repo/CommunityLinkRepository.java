package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface CommunityLinkRepository extends MongoRepository<CommunityLink, String> {
    List<CommunityLink> findByActiveTrueOrderByPositionAsc();
}

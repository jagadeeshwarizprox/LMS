package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface VideoRepository extends MongoRepository<Video, String> {
    List<Video> findByKind(String kind);
    List<Video> findByActiveTrue();
}

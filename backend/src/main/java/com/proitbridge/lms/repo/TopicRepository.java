package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface TopicRepository extends MongoRepository<Topic, String> {
    List<Topic> findByChapterIdOrderByPositionAsc(String chapterId);
    List<Topic> findByModuleIdOrderByPositionAsc(String moduleId);
    List<Topic> findByChapterIdIn(Collection<String> chapterIds);
    long countByChapterId(String chapterId);
    long countByModuleId(String moduleId);

    /* the topic a video belongs to, by the videoRef index, instead of scanning topics */
    Optional<Topic> findFirstByVideoRef(String videoRef);
}

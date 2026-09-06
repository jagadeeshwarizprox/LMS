package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface ChapterNoteRepository extends MongoRepository<ChapterNote, String> {
    Optional<ChapterNote> findByLearnerIdAndChapterId(String learnerId, String chapterId);
    List<ChapterNote> findByLearnerId(String learnerId);
}

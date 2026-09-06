package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface QuizAttemptRepository extends MongoRepository<QuizAttempt, String> {
    List<QuizAttempt> findByLearnerId(String learnerId);
    List<QuizAttempt> findByLearnerIdAndChapterId(String learnerId, String chapterId);
}

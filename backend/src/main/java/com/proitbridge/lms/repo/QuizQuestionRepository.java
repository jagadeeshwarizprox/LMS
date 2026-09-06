package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.*;
import org.springframework.data.mongodb.repository.MongoRepository;
import java.util.*;

public interface QuizQuestionRepository extends MongoRepository<QuizQuestion, String> {
    List<QuizQuestion> findByChapterId(String chapterId);
}

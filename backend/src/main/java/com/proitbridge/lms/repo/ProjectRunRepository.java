package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.ProjectRun;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRunRepository extends MongoRepository<ProjectRun, String> {
    List<ProjectRun> findByLearnerId(String learnerId);
    Optional<ProjectRun> findByLearnerIdAndProjectId(String learnerId, String projectId);
    List<ProjectRun> findByBatchId(String batchId);
    List<ProjectRun> findByMentorId(String mentorId);
    List<ProjectRun> findByLearnerIdIn(List<String> learnerIds);
    List<ProjectRun> findByProjectId(String projectId);
}

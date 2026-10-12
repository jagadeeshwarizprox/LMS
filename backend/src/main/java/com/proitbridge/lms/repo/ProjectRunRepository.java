package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.ProjectRun;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProjectRunRepository extends MongoRepository<ProjectRun, String> {
    List<ProjectRun> findByLearnerId(String learnerId);
    Optional<ProjectRun> findByLearnerIdAndProjectId(String learnerId, String projectId);
    List<ProjectRun> findByBatchId(String batchId);
    List<ProjectRun> findByMentorId(String mentorId);
    List<ProjectRun> findByLearnerIdIn(List<String> learnerIds);
    List<ProjectRun> findByProjectId(String projectId);
    /* one cohort on one project, rather than every run in the batch filtered by project */
    List<ProjectRun> findByBatchIdAndProjectId(String batchId, String projectId);
    /* runs still open for a set of learners: the review queue never needs finished ones */
    List<ProjectRun> findByLearnerIdInAndStatusNot(Collection<String> learnerIds, String status);
    List<ProjectRun> findByProjectIdAndLearnerIdIn(String projectId, Collection<String> learnerIds);
}

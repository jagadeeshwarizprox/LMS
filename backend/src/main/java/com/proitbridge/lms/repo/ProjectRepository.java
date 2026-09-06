package com.proitbridge.lms.repo;

import com.proitbridge.lms.domain.Project;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface ProjectRepository extends MongoRepository<Project, String> {
    List<Project> findByActiveTrue();
}

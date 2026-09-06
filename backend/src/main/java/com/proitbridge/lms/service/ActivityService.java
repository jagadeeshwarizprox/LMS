package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.ActivityLog;
import com.proitbridge.lms.repo.ActivityLogRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ActivityService {

    private final ActivityLogRepository repo;

    public ActivityService(ActivityLogRepository repo) {
        this.repo = repo;
    }

    public void log(String actorId, String actorEmail, String action, String entity, String detail) {
        ActivityLog a = new ActivityLog();
        a.setActorId(actorId);
        a.setActorEmail(actorEmail);
        a.setAction(action);
        a.setEntity(entity);
        a.setDetail(detail);
        repo.save(a);
    }

    public List<ActivityLog> recent() {
        return repo.findTop100ByOrderByCreatedAtDesc();
    }
}

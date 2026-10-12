package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.ActivityLog;
import com.proitbridge.lms.repo.ActivityLogRepository;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ActivityService {

    private final ActivityLogRepository repo;
    private final MongoTemplate mongo;

    public ActivityService(ActivityLogRepository repo, MongoTemplate mongo) {
        this.repo = repo;
        this.mongo = mongo;
    }

    public void log(String actorId, String actorEmail, String action, String entity, String detail) {
        ActivityLog a = new ActivityLog();
        a.setActorId(actorId);
        /* lowercased so the activity log search, which lowercases what is typed, finds it */
        a.setActorEmail(actorEmail == null ? null : actorEmail.toLowerCase());
        a.setAction(action);
        a.setEntity(entity);
        a.setDetail(detail);
        repo.save(a);
    }

    /**
     * The activity log, a page at a time and filtered on the server.
     *
     * It used to be the latest hundred rows with the filtering done in the browser, so a
     * question like "what did this admin change last month" could only be answered for
     * the last afternoon. Every filter here has an index that also carries the
     * newest-first sort: actor (actorEmail, createdAt), area (entity, createdAt) and the
     * date window on createdAt itself.
     *
     * The actor filter is a prefix match on the email, case-insensitive only in the sense
     * that the stored addresses are already lower case and the typed text is folded too.
     * Dates are whole days in the server's zone, both ends included.
     */
    public Map<String, Object> page(String actor, String area, String action,
                                    LocalDate from, LocalDate to, int page, int size) {
        List<Criteria> parts = new ArrayList<>();
        if (AdminQueries.hasText(actor)) {
            parts.add(Criteria.where("actorEmail").regex("^" + AdminQueries.literal(actor.toLowerCase())));
        }
        if (AdminQueries.hasText(area)) parts.add(Criteria.where("entity").is(area.trim()));
        if (AdminQueries.hasText(action)) parts.add(Criteria.where("action").is(action.trim()));
        ZoneId zone = ZoneId.systemDefault();
        if (from != null || to != null) {
            Criteria when = Criteria.where("createdAt");
            if (from != null) when = when.gte(from.atStartOfDay(zone).toInstant());
            if (to != null) when = when.lt(to.plusDays(1).atStartOfDay(zone).toInstant());
            parts.add(when);
        }
        Criteria where = AdminQueries.all(parts);
        long total = mongo.count(new Query(where), ActivityLog.class);
        List<ActivityLog> rows = mongo.find(new Query(where)
                .with(Sort.by(Sort.Direction.DESC, "createdAt"))
                .skip((long) page * size).limit(size), ActivityLog.class);
        return AdminQueries.pageOf(rows, total, page, size);
    }

    /** The areas the log has entries for, for the filter dropdown. Small and bounded. */
    public List<String> areas() {
        return mongo.findDistinct(new Query(), "entity", ActivityLog.class, String.class);
    }
}

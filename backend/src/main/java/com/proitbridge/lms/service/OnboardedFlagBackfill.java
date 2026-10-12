package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.Learner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Keeps the stored Learner.onboarded flag honest for rows Learner did not write.
 *
 * Every save computes the flag from the gates, so a learner touched since the upgrade
 * is already right. A row written before the field existed has none, and would read as
 * "still onboarding" for ever. This sets it from the gates with two multi-updates that
 * touch only the rows that disagree, so it runs on every start: a no-op once in step,
 * and a repair if anything ever wrote a gate past the entity.
 */
@Service
public class OnboardedFlagBackfill {

    private static final Logger log = LoggerFactory.getLogger(OnboardedFlagBackfill.class);

    private final MongoTemplate mongo;

    public OnboardedFlagBackfill(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        var on = mongo.updateMulti(new Query(new Criteria().andOperator(
                        AdminQueries.onboardedByGates(), Criteria.where("onboarded").ne(true))),
                new Update().set("onboarded", true), Learner.class);
        var off = mongo.updateMulti(new Query(new Criteria().andOperator(
                        AdminQueries.stillOnboardingByGates(), Criteria.where("onboarded").ne(false))),
                new Update().set("onboarded", false), Learner.class);
        long a = on == null ? 0 : on.getModifiedCount();
        long b = off == null ? 0 : off.getModifiedCount();
        if (a + b > 0) log.info("onboarded flag set on {} learners, cleared on {}", a, b);
    }
}

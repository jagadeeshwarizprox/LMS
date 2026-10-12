package com.proitbridge.lms.config;

import com.proitbridge.lms.service.ProgressSnapshotService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Works out the progress snapshot for every learner who does not have one yet.
 *
 * The snapshot fields on Learner are new, so every existing row starts without them.
 * Nothing breaks in the meantime: a read that reaches a learner without a snapshot
 * computes it there and then. This just gets ahead of the reads, so the first batch page
 * after a deploy is not the one that pays for the whole batch, and so the rank counts,
 * which read the stored figure, are right for everybody rather than for whoever has been
 * looked at.
 *
 * It must not hold startup up. At twenty thousand learners this is minutes of work, so
 * it is handed to the snapshot service's background worker, which pages through the
 * learners without a snapshot five hundred at a time, logging as it goes, while the
 * application serves requests. Idempotent: it only ever touches rows whose snapshot is
 * missing, so a second start with nothing missing reads one empty page and stops.
 */
@Configuration
public class ProgressSnapshotMigration {

    private static final Logger log = LoggerFactory.getLogger(ProgressSnapshotMigration.class);

    @Bean
    ApplicationRunner backfillProgressSnapshots(ProgressSnapshotService snapshots) {
        return args -> {
            log.info("Progress snapshots: backfilling any learner without one, in the background");
            snapshots.sweepInBackground("startup backfill");
        };
    }
}

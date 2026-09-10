package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.AppSetting;
import com.proitbridge.lms.domain.Batch;
import com.proitbridge.lms.domain.Learner;
import com.proitbridge.lms.repo.AppSettingRepository;
import com.proitbridge.lms.repo.BatchRepository;
import com.proitbridge.lms.repo.LearnerRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Every learner already in a batch gets that batch's mentor.
 *
 * Provisioning never wrote {@code mentorId}, so an account created from the sheet or the
 * register carried a batch and no mentor. Nothing downstream could tell that apart from a
 * learner who genuinely had nobody: the mentor's own roster came back empty, their learner
 * count read zero on the Mentors screen, the analytics mentor rollup folded every learner
 * into a single Unassigned row, and a one to one booking failed the check that the slot
 * belongs to the learner's own mentor.
 *
 * The batch already holds the answer, so this reads it rather than guessing. A learner who
 * has a mentor is never touched, including one an admin deliberately moved away from their
 * batch mentor: the fix is for the ones that were never given anybody, not a reshuffle.
 * Premium learners have no batch and are left alone; the register assigns those by hand.
 */
@Service
public class MentorIdBackfill {

    private static final String FLAG = "mentors.batchMentor.backfilled";
    private static final Logger log = LoggerFactory.getLogger(MentorIdBackfill.class);

    private final LearnerRepository learners;
    private final BatchRepository batches;
    private final AppSettingRepository settings;

    public MentorIdBackfill(LearnerRepository learners, BatchRepository batches,
                            AppSettingRepository settings) {
        this.learners = learners;
        this.batches = batches;
        this.settings = settings;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        if (settings.findById(FLAG).isPresent()) return;

        Map<String, String> mentorOfBatch = new HashMap<>();
        for (Batch b : batches.findAll()) {
            if (b.getMentorId() != null && !b.getMentorId().isBlank()) {
                mentorOfBatch.put(b.getId(), b.getMentorId());
            }
        }

        List<Learner> all = learners.findAll();
        int filled = 0;
        int stillNone = 0;
        for (Learner l : all) {
            if (l.getMentorId() != null && !l.getMentorId().isBlank()) continue;
            String mentorId = l.getBatchId() == null ? null : mentorOfBatch.get(l.getBatchId());
            if (mentorId == null) { stillNone++; continue; }
            l.setMentorId(mentorId);
            learners.save(l);
            filled++;
        }

        AppSetting done = new AppSetting();
        done.setKey(FLAG);
        done.setValue("yes");
        settings.save(done);

        log.info("Backfilled {} learners with their batch mentor. {} still have nobody "
                + "and need assigning by hand on the register.", filled, stillNone);
    }
}

package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.AppSetting;
import com.proitbridge.lms.domain.Bundle;
import com.proitbridge.lms.domain.Slot;
import com.proitbridge.lms.repo.AppSettingRepository;
import com.proitbridge.lms.repo.BundleRepository;
import com.proitbridge.lms.repo.SlotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Every course that already existed is treated as published.
 *
 * The publish flag used to be decorative: it was set, it was displayed, and no learner
 * path ever read it. Now that it gates the roadmap, a database written under the old
 * rules is full of courses that are live, being studied, and marked unpublished simply
 * because nobody ever had a reason to press the button. Switching the gate on without
 * this would empty every learner's roadmap on the morning of the upgrade.
 *
 * So the flag is backfilled once, from what was true rather than from what was recorded:
 * a course with modules in it was being taught. An empty shell is left unpublished,
 * because that is the case the gate exists for.
 */
@Service
public class PublishFlagBackfill {

    private static final String FLAG = "catalogue.published.backfilled";
    private static final Logger log = LoggerFactory.getLogger(PublishFlagBackfill.class);

    private final BundleRepository bundles;
    private final SlotRepository slots;
    private final AppSettingRepository settings;

    public PublishFlagBackfill(BundleRepository bundles, SlotRepository slots,
                               AppSettingRepository settings) {
        this.bundles = bundles;
        this.slots = slots;
        this.settings = settings;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        if (settings.findById(FLAG).isPresent()) return;

        List<Bundle> all = bundles.findAll();
        int published = 0;
        int left = 0;
        for (Bundle b : all) {
            if (b.isPublished()) continue;
            if (b.getModuleIds() == null || b.getModuleIds().isEmpty()) { left++; continue; }
            b.setPublished(true);
            bundles.save(b);
            published++;
        }

        /*
         * The same shape of bug on slots. Releasing a slot set it open and left published
         * false, and the learner's list filters on published, so every slot any mentor has
         * ever released is sitting there open, in scope, and invisible. Releasing now
         * publishes; these are the ones released before it did.
         */
        int slotsFixed = 0;
        for (Slot s : slots.findAll()) {
            if (s.isPublished() || !s.isOpen() || s.isCancelled()) continue;
            s.setPublished(true);
            slots.save(s);
            slotsFixed++;
        }

        AppSetting done = new AppSetting();
        done.setKey(FLAG);
        done.setValue("yes");
        settings.save(done);

        log.info("Publish gate is now live. {} existing courses marked published, {} left "
                + "unpublished because they have no modules. {} already released slots "
                + "made visible to learners.", published, left, slotsFixed);
    }
}

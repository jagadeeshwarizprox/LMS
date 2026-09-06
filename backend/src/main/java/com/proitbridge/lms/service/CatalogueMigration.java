package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * The one-time move from two content levels to three.
 *
 * Before this, a chapter carried the video and the assessment together, so every video
 * came with its own test and its own task. The chapter is now a grouping and the video
 * lives on a topic beneath it.
 *
 * The migration keeps every old chapter id as a chapter id. That is the whole trick:
 * quiz questions, quiz attempts, assignments, mock requests and progress rows are all
 * keyed on chapterId and all stay valid without being touched. What moves is the video
 * and the material, into one new topic per old chapter, and the resources are repointed
 * at it.
 *
 * It runs once, guarded by a setting, and is safe to leave in place afterwards.
 */
@Service
public class CatalogueMigration {

    private static final Logger log = LoggerFactory.getLogger(CatalogueMigration.class);
    private static final String DONE_KEY = "catalogue.threeLevel.migrated";

    private final MongoTemplate mongo;
    private final ChapterRepository chapters;
    private final TopicRepository topics;
    private final ResourceRepository resources;
    private final ModuleRepository modules;
    private final ProgressRepository progress;
    private final AppSettingRepository settings;

    public CatalogueMigration(MongoTemplate mongo, ChapterRepository chapters,
                              TopicRepository topics, ResourceRepository resources,
                              ModuleRepository modules, ProgressRepository progress,
                              AppSettingRepository settings) {
        this.mongo = mongo; this.chapters = chapters; this.topics = topics;
        this.resources = resources; this.modules = modules; this.progress = progress;
        this.settings = settings;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        if (settings.findByKey(DONE_KEY).isPresent()) return;

        /* The old collections were named for the old shape: "topics" held subjects and
           "chapters" held the lessons. The subject collection is now "modules". Renaming
           rather than copying means the ids people already hold keep working. */
        if (mongo.collectionExists("topics") && !mongo.collectionExists("modules")) {
            mongo.getDb().getCollection("topics").renameCollection(
                    new com.mongodb.MongoNamespace(mongo.getDb().getName(), "modules"));
            log.info("catalogue migration: renamed topics to modules");
        }

        int made = 0;
        for (Chapter c : chapters.findAll()) {
            /* already migrated chapters have topics under them */
            if (topics.countByChapterId(c.getId()) > 0) continue;

            Document legacy = raw(c.getId());
            String videoRef = legacy == null ? null : legacy.videoRef;
            int minutes = legacy == null ? 0 : legacy.durationMin;
            String codeRunner = legacy == null || legacy.codeRunner == null ? "NONE" : legacy.codeRunner;

            Topic t = new Topic();
            t.setChapterId(c.getId());
            t.setModuleId(c.getModuleId());
            t.setTitle(c.getTitle());
            t.setSummary(c.getSummary());
            t.setPosition(1);
            t.setVideoRef(videoRef);
            t.setDurationMin(minutes);
            t.setCodeRunner(codeRunner);
            t = topics.save(t);
            made++;

            /* material hung off the chapter; it belongs to the topic that now holds the video */
            for (Resource r : resources.findAll()) {
                if (c.getId().equals(r.getTopicId())) {
                    r.setTopicId(t.getId());
                    resources.save(r);
                }
            }

            /* the task brief was a bare string on the chapter; it is the assignment now */
            if (legacy != null && legacy.taskBrief != null && !legacy.taskBrief.isBlank()) {
                c.getAssignment().setBrief(legacy.taskBrief);
            }
            if (c.getAssignment().getTitle() == null) {
                c.getAssignment().setTitle(c.getTitle() + " assignment");
            }
            if (c.getTeachback().getPrompt() == null) {
                c.getTeachback().setPrompt("Explain " + c.getTitle().toLowerCase() + " in your own words.");
            }
            if (legacy != null) {
                c.getTest().setEnabled(legacy.hasQuiz);
                c.getAssignment().setEnabled(legacy.hasTask);
                c.getTeachback().setEnabled(legacy.hasConceptCheck);
            }
            chapters.save(c);

            /* anyone who had finished the old chapter had finished its only video */
            for (Progress p : progress.findAll()) {
                if (c.getId().equals(p.getChapterId()) && p.isWatched()
                        && !p.getWatchedTopicIds().contains(t.getId())) {
                    p.getWatchedTopicIds().add(t.getId());
                    progress.save(p);
                }
            }
        }

        /* modules had no code before; the console shows one in every table */
        int n = 1;
        for (CourseModule m : modules.findAllByOrderByPositionAsc()) {
            if (m.getCode() == null || m.getCode().isBlank()) {
                m.setCode(String.format("M%02d", n));
                modules.save(m);
            }
            n++;
        }

        AppSetting done = new AppSetting();
        done.setKey(DONE_KEY);
        done.setValue("yes");
        settings.save(done);
        log.info("catalogue migration: {} chapters split into chapter plus topic", made);
    }

    /** The fields the old Chapter had and the new one does not, read straight from Mongo. */
    private Document raw(String chapterId) {
        org.bson.Document d = mongo.getDb().getCollection("chapters")
                .find(new org.bson.Document("_id", new org.bson.types.ObjectId(chapterId)))
                .first();
        if (d == null) return null;
        Document out = new Document();
        out.videoRef = d.getString("videoRef");
        out.taskBrief = d.getString("taskBrief");
        out.codeRunner = d.getString("codeRunner");
        Object dm = d.get("durationMin");
        out.durationMin = dm instanceof Number ? ((Number) dm).intValue() : 0;
        out.hasQuiz = !Boolean.FALSE.equals(d.getBoolean("hasQuiz"));
        out.hasTask = !Boolean.FALSE.equals(d.getBoolean("hasTask"));
        out.hasConceptCheck = !Boolean.FALSE.equals(d.getBoolean("hasConceptCheck"));
        return out;
    }

    private static class Document {
        String videoRef, taskBrief, codeRunner;
        int durationMin;
        boolean hasQuiz = true, hasTask = true, hasConceptCheck = true;
    }
}

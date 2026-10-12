package com.proitbridge.lms.service;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Where each learner has got to, worked out when it changes rather than when it is read.
 *
 * The percentage, the topic counts, the test average and the approvals used to be
 * computed from scratch on every read: four or five queries per learner. That is fine on
 * one dashboard and ruinous on a batch page, which ranked the learner by computing the
 * same thing for everyone in the batch and everyone on the course. At twenty thousand
 * learners one page view was tens of thousands of reads.
 *
 * Now the numbers live on the learner row (see the snapshot fields on Learner) and this
 * is the one place that writes them. Everything that moves a number calls refresh() for
 * that learner. A catalogue edit, which moves the denominator for a whole course at
 * once, marks the course stale in one update instead, and a background sweep recomputes
 * the stale rows while reads compute any they reach first.
 *
 * Deliberately built on repositories only. LearnerService, AssignmentService,
 * MentorService and the rest all call in here, so if this took any of them as a
 * dependency Spring would find a cycle and refuse to start.
 */
@Service
public class ProgressSnapshotService {

    /** The learners collection, for the raw snapshot writes. */
    private static final String LEARNERS = "learners";

    /** A String @Id that looks like an ObjectId is stored as one; anything else as text. */
    private static List<Object> idForms(String id) {
        List<Object> out = new ArrayList<>();
        out.add(id);
        if (id != null && org.bson.types.ObjectId.isValid(id)) out.add(new org.bson.types.ObjectId(id));
        return out;
    }

    private static final Logger log = LoggerFactory.getLogger(ProgressSnapshotService.class);

    /** How many stale learners the sweep reads at a time. */
    static final int PAGE = 500;

    private final LearnerRepository learners;
    private final BundleRepository bundles;
    private final ChapterRepository chapters;
    private final TopicRepository topics;
    private final ProgressRepository progress;
    private final ProjectWorkRepository projects;
    private final MongoTemplate mongo;

    /*
     * One background worker for the sweep, so two catalogue edits in a row never run two
     * sweeps over the same rows at once. Daemon, so it never holds a shutdown up; a sweep
     * cut short just leaves rows stale, and stale rows recompute on their next read.
     */
    private final ExecutorService sweeper = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "progress-snapshot-sweep");
        t.setDaemon(true);
        return t;
    });
    /** A sweep is already queued and has not started: a second request has nothing to add. */
    private final AtomicBoolean sweepQueued = new AtomicBoolean(false);

    public ProgressSnapshotService(LearnerRepository learners, BundleRepository bundles,
                                   ChapterRepository chapters, TopicRepository topics,
                                   ProgressRepository progress, ProjectWorkRepository projects,
                                   MongoTemplate mongo) {
        this.learners = learners; this.bundles = bundles; this.chapters = chapters;
        this.topics = topics; this.progress = progress; this.projects = projects;
        this.mongo = mongo;
    }

    /* ---------------------------------------------------------------- reading */

    /**
     * The learner with a current snapshot. Free when it is already current, which is
     * almost always; computes and stores it when it has never been worked out or a
     * catalogue change marked it stale.
     */
    public Learner ensure(Learner l) {
        if (l != null && l.getProgressUpdatedAt() == null) refresh(l);
        return l;
    }

    /**
     * The snapshot in the shape LearnerService.stats() has always returned.
     *
     * Same keys and the same number types as before, because the dashboard, the mentor
     * roster, the call brief and the desk all read this map, and some of them cast what
     * they read. Counts are longs and percent and avgQuiz are ints, exactly as the old
     * computation produced them.
     */
    public Map<String, Object> stats(Learner l) {
        ensure(l);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalChapters", l.getTotalChapters());
        m.put("watched", l.getWatchedChapters());
        m.put("totalTopics", l.getTotalTopics());
        m.put("watchedTopics", l.getWatchedTopics());
        m.put("hasCourse", l.isCourseHasContent());
        m.put("percent", l.getProgressPercent());
        m.put("avgQuiz", (int) Math.round(l.getAvgQuiz() == null ? 0 : l.getAvgQuiz()));
        m.put("tasksApproved", l.getTasksApproved());
        m.put("projectsApproved", l.getProjectsApproved());
        return m;
    }

    /* ---------------------------------------------------------------- writing */

    /** Recompute one learner after something they did, or something done to their record. */
    public Learner refresh(String learnerId) {
        if (learnerId == null) return null;
        return learners.findById(learnerId).map(this::refresh).orElse(null);
    }

    /**
     * Recompute from the stored progress and write only the snapshot fields.
     *
     * Only those fields, through a targeted update, never a save of the whole row: a
     * save here would put back whatever else this copy of the learner held, and a gate
     * flag or a mentor change written a moment earlier by somebody else would be lost.
     *
     * Two refreshes of the same learner at once are fine, the last write wins and both
     * computed from real rows. The one race that matters is a catalogue edit landing
     * while this computes against the old course: the edit marks the row stale, and this
     * write must not then stamp it current with the old numbers. So when the row was
     * current as read, the write only lands if it is still current; if it was marked
     * stale in between, nothing is written and it stays stale for the next read.
     */
    public Learner refresh(Learner l) {
        boolean wasCurrent = l.getProgressUpdatedAt() != null;
        compute(l);
        Update u = new Update()
                .set("progressPercent", l.getProgressPercent())
                .set("watchedTopics", l.getWatchedTopics())
                .set("totalTopics", l.getTotalTopics())
                .set("watchedChapters", l.getWatchedChapters())
                .set("totalChapters", l.getTotalChapters())
                .set("courseHasContent", l.isCourseHasContent())
                .set("avgQuiz", l.getAvgQuiz())
                .set("tasksApproved", l.getTasksApproved())
                .set("projectsApproved", l.getProjectsApproved())
                .set("lastActiveAt", l.getLastActiveAt())
                .set("progressUpdatedAt", l.getProgressUpdatedAt());
        /* a raw update on the collection: the snapshot fields are read only to Spring
           Data's mapping (see Learner), so a typed update could skip them. A raw query
           does not convert ids, so the id is matched in both forms it can be stored in. */
        Criteria which = Criteria.where("_id").in(idForms(l.getId()));
        if (wasCurrent) which = which.and("progressUpdatedAt").ne(null);
        mongo.updateFirst(new Query(which), u, LEARNERS);
        return l;
    }

    /**
     * The computation itself, moved here unchanged from LearnerService.stats().
     *
     * Topics are the unit counted: a chapter is ticked only when every topic in it is
     * done, so counting chapters showed a learner nought per cent after a full video in a
     * five topic chapter. A learner with no published course has no denominator, which
     * is reported through courseHasContent rather than by pretending they have not
     * started.
     */
    private void compute(Learner l) {
        List<Progress> ps = progress.findByLearnerId(l.getId());
        Bundle bundle = l.getBundleId() == null ? null
                : bundles.findById(l.getBundleId()).filter(Bundle::isPublished).orElse(null);

        List<Chapter> courseChapters = bundle == null ? List.of()
                : chapters.findByModuleIdIn(bundle.getModuleIds());
        long totalChapters = courseChapters.size();

        Map<String, Long> topicsPerChapter = new LinkedHashMap<>();
        if (!courseChapters.isEmpty()) {
            Set<String> chapterIds = courseChapters.stream()
                    .map(Chapter::getId).collect(Collectors.toSet());
            for (Topic t : topics.findByChapterIdIn(chapterIds)) {
                if (!t.isActive()) continue;
                topicsPerChapter.merge(t.getChapterId(), 1L, Long::sum);
            }
        }
        long totalTopics = topicsPerChapter.values().stream().mapToLong(Long::longValue).sum();

        Map<String, Progress> progressByChapter = ps.stream()
                .filter(p -> p.getChapterId() != null)
                .collect(Collectors.toMap(Progress::getChapterId, p -> p, (a, b) -> a));
        long watchedTopics = 0;
        for (var e : topicsPerChapter.entrySet()) {
            Progress p = progressByChapter.get(e.getKey());
            if (p == null || p.getWatchedTopicIds() == null) continue;
            /* a topic archived after somebody watched it stays in their set, so the
               count is capped at what the chapter still holds rather than going over */
            watchedTopics += Math.min(e.getValue(), p.getWatchedTopicIds().size());
        }

        OptionalDouble quiz = ps.stream().filter(p -> p.getQuizScore() != null)
                .mapToDouble(Progress::getQuizScore).average();

        l.setTotalChapters(totalChapters);
        l.setWatchedChapters(ps.stream().filter(Progress::isWatched).count());
        l.setTotalTopics(totalTopics);
        l.setWatchedTopics(watchedTopics);
        l.setCourseHasContent(bundle != null && totalTopics > 0);
        l.setProgressPercent(totalTopics == 0 ? 0
                : (int) Math.round(watchedTopics * 100.0 / totalTopics));
        l.setAvgQuiz(quiz.isPresent() ? quiz.getAsDouble() : null);
        l.setTasksApproved(ps.stream().filter(p -> "APPROVED".equals(p.getTaskStatus())).count());
        l.setProjectsApproved(projects.countByLearnerIdAndStatus(l.getId(), "APPROVED"));
        /* the last time any of their progress rows moved: opened, watched, tested, reviewed */
        l.setLastActiveAt(ps.stream().map(Progress::getUpdatedAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null));
        l.setProgressUpdatedAt(Instant.now());
    }

    /* ---------------------------------------------------------------- catalogue changes */

    /**
     * A course's shape changed: topics added or archived, chapters removed, modules moved
     * in or out, the course published or pulled. Every learner on it now has the wrong
     * denominator, and there may be thousands of them, so they are marked stale in one
     * update and recomputed in the background rather than while the admin waits.
     */
    public void markBundleStale(String bundleId) {
        if (bundleId == null) return;
        markStale(Criteria.where("bundleId").is(bundleId), "course " + bundleId);
    }

    /** Every course that carries this module. A module can sit in several. */
    public void markModuleStale(String moduleId) {
        if (moduleId == null) return;
        /* an equality match on an array field matches any element: the courses carrying it */
        List<String> ids = mongo.find(Query.query(Criteria.where("moduleIds").is(moduleId)), Bundle.class)
                .stream().map(Bundle::getId).toList();
        if (ids.isEmpty()) return;
        markStale(Criteria.where("bundleId").in(ids), "module " + moduleId);
    }

    /** Everybody: for a migration that rewrote progress rows underneath the snapshots. */
    public void markAllStale(String why) {
        markStale(Criteria.where("progressUpdatedAt").ne(null), why);
    }

    private void markStale(Criteria which, String why) {
        mongo.updateMulti(new Query(which), new Update().set("progressUpdatedAt", null), LEARNERS);
        log.info("Progress snapshots marked stale: {}", why);
        sweepInBackground(why);
    }

    /* ---------------------------------------------------------------- the sweep */

    /**
     * Recompute every stale learner on the background worker. Returns at once.
     *
     * If a sweep is already waiting to start it will see these rows too, so nothing more
     * is queued. One that is already running may have passed them, which is why the flag
     * is cleared as a sweep starts rather than when it ends: a mark during a sweep queues
     * the next one.
     */
    public void sweepInBackground(String why) {
        if (!sweepQueued.compareAndSet(false, true)) return;
        sweeper.submit(() -> {
            sweepQueued.set(false);
            try {
                sweepStale(why);
            } catch (Exception e) {
                /* never fatal: whatever is left stale recomputes on its next read */
                log.warn("Progress snapshot sweep ({}) stopped early: {}", why, e.toString());
            }
        });
    }

    /**
     * Pages through the stale learners in id order, a page at a time.
     *
     * Keyed on the last id seen rather than on skip, for two reasons. Rows leave the
     * "stale" set as they are computed, so a skip would jump over the ones that slid
     * into the gap. And a learner whose computation throws stays stale, so a loop that
     * just asked for "the next five hundred stale" would fetch them forever.
     */
    void sweepStale(String why) {
        String lastId = null;
        int done = 0, failed = 0;
        while (true) {
            Criteria c = Criteria.where("progressUpdatedAt").is(null);
            if (lastId != null) c = c.and("id").gt(lastId);
            Query q = new Query(c).with(Sort.by(Sort.Direction.ASC, "id")).limit(PAGE);
            List<Learner> page = mongo.find(q, Learner.class);
            if (page.isEmpty()) break;
            for (Learner l : page) {
                try {
                    refresh(l);
                    done++;
                } catch (Exception e) {
                    failed++;
                    log.warn("Progress snapshot for learner {} could not be computed: {}", l.getId(), e.toString());
                }
            }
            lastId = page.get(page.size() - 1).getId();
            log.info("Progress snapshots ({}): {} computed so far", why, done);
            if (page.size() < PAGE) break;
        }
        if (done > 0 || failed > 0) {
            log.info("Progress snapshots ({}): finished, {} computed, {} failed", why, done, failed);
        }
    }
}

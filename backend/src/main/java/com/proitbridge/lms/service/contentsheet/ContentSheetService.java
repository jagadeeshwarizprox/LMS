package com.proitbridge.lms.service.contentsheet;

import com.proitbridge.lms.domain.*;
import com.proitbridge.lms.repo.*;
import com.proitbridge.lms.service.ActivityService;
import com.proitbridge.lms.service.CatalogueService;
import com.proitbridge.lms.service.FileService;
import com.proitbridge.lms.service.ResourceService;
import com.proitbridge.lms.service.video.VideoRefs;
import com.proitbridge.lms.service.contentsheet.ContentSheetParser.SheetRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * The master content sheet, kept in step with the catalogue.
 *
 * The sheet is the source; the catalogue is what learners use. A sync never re-uploads
 * a course. It compares each row with what it recorded last time (ContentSheetRow) and
 * touches only the cells that changed, through the same CatalogueService and
 * ResourceService calls the course builder makes, so every rule those enforce (drafts,
 * kind checks, stale progress snapshots, the activity log) applies unchanged.
 *
 * What it will never do:
 *   - delete anything. A row gone from the sheet is reported and its topic left alone;
 *     a link removed from a cell hides the material it made, it does not delete it.
 *   - change a topic's id. Watch history, progress, tests and assignments are keyed on
 *     ids, so they survive every sync.
 *   - overwrite what a topic already had when the sheet first meets it. A sheet row that
 *     names an existing topic adopts it and fills only what is empty; from then on,
 *     edits in the sheet are applied.
 *
 * One sync at a time. Deployment is a single API instance, so an in-memory guard is the
 * lock; a second request while one runs is refused rather than queued.
 */
@Service
public class ContentSheetService {

    private static final Logger log = LoggerFactory.getLogger(ContentSheetService.class);

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String AUTO_ACTOR = "content sheet (automatic)";
    private static final int MAX_ITEMS = 3000;

    /** The cells a row carries besides the three that identify it, and what to call them. */
    private static final List<String> CELLS = List.of(
            ContentSheetParser.COURSE, ContentSheetParser.ONELINE, ContentSheetParser.RUNNER, ContentSheetParser.VIDEO,
            ContentSheetParser.NOTES, ContentSheetParser.CODE, ContentSheetParser.DATASET,
            ContentSheetParser.SLIDES, ContentSheetParser.WRITTEN);
    private static final Map<String, String> LABEL = Map.of(
            ContentSheetParser.COURSE, "Course", ContentSheetParser.ONELINE, "One line", ContentSheetParser.RUNNER, "Code runner",
            ContentSheetParser.VIDEO, "Video link", ContentSheetParser.NOTES, "Notes",
            ContentSheetParser.CODE, "Code", ContentSheetParser.DATASET, "Dataset",
            ContentSheetParser.SLIDES, "Slides", ContentSheetParser.WRITTEN, "Written note");
    private static final List<String> FILE_CELLS = List.of(
            ContentSheetParser.NOTES, ContentSheetParser.CODE, ContentSheetParser.DATASET,
            ContentSheetParser.SLIDES);

    public record Actor(String id, String email) {}

    private final ContentSheetConfigRepository configs;
    private final ContentSheetVersionRepository versions;
    private final ContentSheetRowRepository rowStates;
    private final ContentSheetRunRepository runs;
    private final ModuleRepository modules;
    private final BundleRepository bundles;
    private final ChapterRepository chapters;
    private final TopicRepository topics;
    private final ResourceRepository resources;
    private final VideoRepository videos;
    private final CatalogueService catalogue;
    private final ResourceService resourceService;
    private final FileService files;
    private final LinkFetcher fetcher;
    private final ActivityService activity;

    private final AtomicReference<String> runningRunId = new AtomicReference<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "content-sheet-sync");
        t.setDaemon(true);
        return t;
    });

    public ContentSheetService(ContentSheetConfigRepository configs, ContentSheetVersionRepository versions,
                               ContentSheetRowRepository rowStates, ContentSheetRunRepository runs,
                               ModuleRepository modules, BundleRepository bundles,
                               ChapterRepository chapters, TopicRepository topics,
                               ResourceRepository resources, VideoRepository videos,
                               CatalogueService catalogue, ResourceService resourceService,
                               FileService files, LinkFetcher fetcher, ActivityService activity) {
        this.configs = configs; this.versions = versions; this.rowStates = rowStates; this.runs = runs;
        this.modules = modules; this.bundles = bundles; this.chapters = chapters; this.topics = topics;
        this.resources = resources; this.videos = videos;
        this.catalogue = catalogue; this.resourceService = resourceService;
        this.files = files; this.fetcher = fetcher; this.activity = activity;
    }

    /* =============================================================== reading */

    public ContentSheetConfig config() {
        return configs.findById(ContentSheetConfig.ID).orElseGet(ContentSheetConfig::new);
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        ContentSheetConfig c = config();
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("sheetUrl", c.getSheetUrl());
        cfg.put("autoSync", c.isAutoSync());
        cfg.put("intervalMinutes", c.getIntervalMinutes());
        cfg.put("lastCheckedAt", c.getLastCheckedAt());
        cfg.put("lastCheckError", c.getLastCheckError());
        m.put("config", cfg);
        String running = runningRunId.get();
        m.put("running", running != null);
        m.put("runningRunId", running);
        m.put("latestVersion", versions.findFirstByOrderByCreatedAtDesc().orElse(null));
        m.put("lastRun", runs.findFirstByOrderByStartedAtDesc().map(this::summary).orElse(null));
        m.put("trackedRows", rowStates.count());
        m.put("headers", ContentSheetParser.TEMPLATE_HEADERS);
        return m;
    }

    public List<Map<String, Object>> runList() {
        return runs.findTop30ByOrderByStartedAtDesc().stream().map(this::summary).collect(Collectors.toList());
    }

    public ContentSheetRun run(String id) {
        return runs.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such sync run."));
    }

    public List<ContentSheetVersion> versionList() { return versions.findTop50ByOrderByCreatedAtDesc(); }

    private Map<String, Object> summary(ContentSheetRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("versionId", r.getVersionId());
        m.put("trigger", r.getTrigger());
        m.put("preview", r.isPreview());
        m.put("actor", r.getActor());
        m.put("startedAt", r.getStartedAt());
        m.put("finishedAt", r.getFinishedAt());
        m.put("status", r.getStatus());
        m.put("message", r.getMessage());
        m.put("counts", r.getCounts());
        return m;
    }

    public byte[] template() {
        try {
            return ContentSheetParser.template();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "The template could not be built.");
        }
    }

    /* =============================================================== settings */

    /**
     * Save where the sheet lives. A link is tried straight away, so a sheet that is not
     * shared publicly is found out here rather than ten minutes later in a log.
     */
    public Map<String, Object> saveConfig(String sheetUrl, Boolean autoSync, Integer intervalMinutes, Actor actor) {
        ContentSheetConfig c = config();
        String url = sheetUrl == null ? null : sheetUrl.trim();
        Map<String, Object> out = new LinkedHashMap<>();
        if (url != null && !url.isEmpty()) {
            try {
                LinkFetcher.Fetched f = fetcher.fetchSheet(url, files.maxBytes());
                ContentSheetParser.Parsed p = ContentSheetParser.parse(f.bytes());
                if (!p.problems().isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", p.problems()));
                }
                out.put("check", Map.of("rows", p.rows().size(), "tabs", p.sheetsRead()));
            } catch (LinkFetcher.FetchException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
            } catch (IOException e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "The link answered, but not with an .xlsx workbook.");
            }
        }
        c.setSheetUrl(url == null || url.isEmpty() ? null : url);
        if (autoSync != null) c.setAutoSync(autoSync && c.getSheetUrl() != null);
        if (intervalMinutes != null) c.setIntervalMinutes(Math.max(5, Math.min(1440, intervalMinutes)));
        c.setUpdatedBy(actor.email());
        c.setUpdatedAt(Instant.now());
        c.setLastCheckError(null);
        configs.save(c);
        activity.log(actor.id(), actor.email(), "CONTENT_SHEET_CONFIG", "content-sheet",
                (c.getSheetUrl() == null ? "no link" : "link set") + ", automatic " + (c.isAutoSync() ? "on" : "off"));
        out.put("status", status());
        return out;
    }

    /* =============================================================== versions */

    private record Loaded(ContentSheetVersion version, boolean changed) {}

    /**
     * Keep a copy of the sheet, unless it says what the latest copy says.
     *
     * Compared on content, not bytes: a Google export of an unchanged sheet is not byte
     * for byte the same file twice (the zip carries timestamps), and comparing bytes
     * would make a new version every ten minutes.
     */
    private Loaded keepVersion(byte[] bytes, String filename, String source, String restoredFrom, Actor actor) {
        ContentSheetParser.Parsed parsed;
        try {
            parsed = ContentSheetParser.parse(bytes);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        if (!parsed.problems().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, String.join(" ", parsed.problems()));
        }
        String hash = contentHash(parsed);
        ContentSheetVersion latest = versions.findFirstByOrderByCreatedAtDesc().orElse(null);
        if (latest != null && hash.equals(latest.getSha256()) && restoredFrom == null) {
            return new Loaded(latest, false);
        }
        StoredFile f = files.store(filename, XLSX, bytes.length, new ByteArrayInputStream(bytes),
                actor.id(), "CONTENT_SHEET", null);
        ContentSheetVersion v = new ContentSheetVersion();
        v.setFileId(f.getId());
        v.setSha256(hash);
        v.setFilename(f.getFilename());
        v.setSource(source);
        v.setRestoredFromId(restoredFrom);
        v.setRowCount(parsed.rows().size());
        v.setCreatedBy(actor.email());
        return new Loaded(versions.save(v), true);
    }

    private static String contentHash(ContentSheetParser.Parsed parsed) {
        StringBuilder sb = new StringBuilder();
        for (SheetRow r : parsed.rows()) {
            sb.append(r.sheet()).append('\u0001').append(r.rowNumber());
            new TreeMap<>(r.cells()).forEach((k, v) -> sb.append('\u0001').append(k).append('=').append(v));
            sb.append('\n');
        }
        return sha256(sb.toString());
    }

    private Loaded pullFromLink(Actor actor) throws LinkFetcher.FetchException {
        ContentSheetConfig c = config();
        if (c.getSheetUrl() == null) {
            throw new LinkFetcher.FetchException("No sheet link is set.");
        }
        LinkFetcher.Fetched f = fetcher.fetchSheet(c.getSheetUrl(), files.maxBytes());
        String name = "content-sheet-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
                .withZone(ZoneId.of("Asia/Kolkata")).format(Instant.now()) + ".xlsx";
        return keepVersion(f.bytes(), name, "LINK", null, actor);
    }

    /* =============================================================== triggers */

    public Map<String, Object> upload(MultipartFile file, boolean preview, Actor actor) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attach the sheet.");
        }
        String name = file.getOriginalFilename() == null ? "content-sheet.xlsx" : file.getOriginalFilename();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The sheet must be an .xlsx file.");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The upload could not be read.");
        }
        requireIdle();
        Loaded l = keepVersion(bytes, name, "UPLOAD", null, actor);
        return start(l.version(), "UPLOAD", preview, actor);
    }

    /** Sync now: pull the linked sheet if there is one, then sync whatever is latest. */
    public Map<String, Object> syncNow(boolean preview, Actor actor) {
        requireIdle();
        ContentSheetConfig c = config();
        if (c.getSheetUrl() != null) {
            try {
                pullFromLink(actor);
                c.setLastCheckedAt(Instant.now());
                c.setLastCheckError(null);
            } catch (LinkFetcher.FetchException e) {
                c.setLastCheckedAt(Instant.now());
                c.setLastCheckError(e.getMessage());
                configs.save(c);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The sheet could not be read: " + e.getMessage());
            }
            configs.save(c);
        }
        ContentSheetVersion v = versions.findFirstByOrderByCreatedAtDesc().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload the sheet or set its link first."));
        return start(v, "MANUAL", preview, actor);
    }

    /** Retry: the latest version again. Only cells that did not finish are processed. */
    public Map<String, Object> retry(Actor actor) {
        requireIdle();
        ContentSheetVersion v = versions.findFirstByOrderByCreatedAtDesc().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "There is no sheet to retry."));
        return start(v, "RETRY", false, actor);
    }

    /**
     * Make an earlier version the current one, and sync it.
     *
     * The restore is itself a new version, so the history reads in order and nothing is
     * rewritten. Automatic sync is switched off, because otherwise the next check would
     * pull the Google Sheet straight back over the restore.
     */
    public Map<String, Object> restore(String versionId, Actor actor) {
        requireIdle();
        ContentSheetVersion old = versions.findById(versionId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No such version."));
        byte[] bytes = files.read(old.getFileId());
        Loaded l = keepVersion(bytes, old.getFilename(), "RESTORE", old.getId(), actor);
        ContentSheetConfig c = config();
        if (c.isAutoSync()) {
            c.setAutoSync(false);
            configs.save(c);
        }
        Map<String, Object> out = start(l.version(), "RESTORE", false, actor);
        out.put("autoSyncPaused", true);
        return out;
    }

    /**
     * The watcher. Runs every minute and acts only when automatic sync is on and the
     * interval has passed; a sheet whose content has not changed costs one download.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void watch() {
        ContentSheetConfig c;
        try {
            c = config();
        } catch (RuntimeException e) {
            return;
        }
        if (!c.isAutoSync() || c.getSheetUrl() == null || runningRunId.get() != null) return;
        if (c.getLastCheckedAt() != null && c.getLastCheckedAt()
                .plusSeconds(60L * Math.max(5, c.getIntervalMinutes())).isAfter(Instant.now())) return;

        Actor auto = new Actor(null, AUTO_ACTOR);
        try {
            Loaded l = pullFromLink(auto);
            c.setLastCheckedAt(Instant.now());
            c.setLastCheckError(null);
            configs.save(c);
            if (l.changed()) start(l.version(), "AUTO", false, auto);
        } catch (LinkFetcher.FetchException | ResponseStatusException e) {
            String msg = e instanceof ResponseStatusException rse ? rse.getReason() : e.getMessage();
            c.setLastCheckedAt(Instant.now());
            c.setLastCheckError(msg);
            configs.save(c);
            log.warn("Content sheet check failed: {}", msg);
        } catch (RuntimeException e) {
            log.error("Content sheet check failed", e);
        }
    }

    private void requireIdle() {
        if (runningRunId.get() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A sync is already running. Wait for it to finish.");
        }
    }

    private Map<String, Object> start(ContentSheetVersion v, String trigger, boolean preview, Actor actor) {
        ContentSheetRun run = new ContentSheetRun();
        run.setVersionId(v.getId());
        run.setTrigger(trigger);
        run.setPreview(preview);
        run.setActor(actor.email());
        run = runs.save(run);
        if (!runningRunId.compareAndSet(null, run.getId())) {
            run.setStatus("FAILED");
            run.setMessage("Another sync started at the same moment.");
            run.setFinishedAt(Instant.now());
            runs.save(run);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A sync is already running. Wait for it to finish.");
        }
        final ContentSheetRun started = run;
        worker.submit(() -> {
            try {
                execute(started, v, actor);
            } catch (Throwable t) {
                log.error("Content sheet sync failed", t);
                started.setStatus("FAILED");
                started.setMessage("The sync stopped: " + (t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage()));
                started.setFinishedAt(Instant.now());
                runs.save(started);
            } finally {
                runningRunId.set(null);
            }
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.getId());
        out.put("versionId", v.getId());
        return out;
    }

    /* =============================================================== the sync */

    /** Everything one run needs to remember while it walks the sheet. */
    private final class Pass {
        final ContentSheetRun run;
        final Actor actor;
        final Map<String, Integer> counts = new LinkedHashMap<>();
        final Map<String, CourseModule> moduleByName = new HashMap<>();
        final Map<String, Bundle> courseByName = new HashMap<>();
        final Map<String, Map<String, Chapter>> chapterByModule = new HashMap<>();
        final Set<String> chaptersWithNewTopics = new LinkedHashSet<>();
        final Map<String, String> topicByKey = new HashMap<>();

        Pass(ContentSheetRun run, Actor actor) {
            this.run = run;
            this.actor = actor;
            for (String k : List.of("added", "updated", "unchanged", "removed", "failed", "invalid")) counts.put(k, 0);
            for (CourseModule m : modules.findAllByOrderByPositionAsc()) {
                moduleByName.putIfAbsent(norm(m.getName()), m);
            }
            for (Bundle b : bundles.findAll()) courseByName.putIfAbsent(norm(b.getName()), b);
        }

        void count(String k) { counts.merge(k, 1, Integer::sum); }

        void add(ContentSheetRun.Item item) {
            if (run.getItems().size() < MAX_ITEMS) run.getItems().add(item);
            else run.setItemsTruncated(true);
        }
    }

    private void execute(ContentSheetRun run, ContentSheetVersion v, Actor actor) throws IOException {
        ContentSheetParser.Parsed parsed = ContentSheetParser.parse(files.read(v.getFileId()));
        Pass pass = new Pass(run, actor);

        /* ---- validate, and drop what cannot be a row */
        Map<String, SheetRow> byKey = new LinkedHashMap<>();
        for (SheetRow r : parsed.rows()) {
            List<String> missing = new ArrayList<>();
            if (r.get(ContentSheetParser.MODULE).isBlank()) missing.add("module name");
            if (r.get(ContentSheetParser.CHAPTER).isBlank()) missing.add("chapter name");
            if (r.get(ContentSheetParser.TITLE).isBlank()) missing.add("title");
            String course = r.get(ContentSheetParser.COURSE);
            if (missing.isEmpty() && !course.isBlank() && !pass.courseByName.containsKey(norm(course))) {
                ContentSheetRun.Item item = item(r);
                item.setAction("INVALID");
                item.setStatus("FAILED");
                item.getProblems().add("There is no course named \"" + course.trim() + "\". Courses are created in "
                        + "Courses and pricing; the sheet adds modules to them but never creates one.");
                pass.add(item);
                pass.count("invalid");
                continue;
            }
            if (!missing.isEmpty()) {
                ContentSheetRun.Item item = item(r);
                item.setAction("INVALID");
                item.setStatus("FAILED");
                item.getProblems().add("Missing " + String.join(", ", missing) + ".");
                pass.add(item);
                pass.count("invalid");
                continue;
            }
            String key = key(r);
            SheetRow first = byKey.get(key);
            if (first != null) {
                ContentSheetRun.Item item = item(r);
                item.setAction("INVALID");
                item.setStatus("FAILED");
                item.getProblems().add("Same module, chapter and title as " + first.where()
                        + ". Only the first is used, so nothing is duplicated.");
                pass.add(item);
                pass.count("invalid");
                continue;
            }
            byKey.put(key, r);
        }

        Map<String, ContentSheetRow> stateByKey = new HashMap<>();
        for (ContentSheetRow s : rowStates.findAll()) stateByKey.put(s.getRowKey(), s);
        Set<String> knownTopicIds = new HashSet<>();
        topics.findAllById(stateByKey.values().stream().map(ContentSheetRow::getTopicId)
                .filter(Objects::nonNull).collect(Collectors.toSet()))
                .forEach(t -> knownTopicIds.add(t.getId()));

        /* ---- each row, in the sheet's order */
        for (Map.Entry<String, SheetRow> e : byKey.entrySet()) {
            String key = e.getKey();
            SheetRow r = e.getValue();
            ContentSheetRow state = stateByKey.get(key);
            Map<String, String> hashes = cellHashes(r);

            boolean topicThere = state != null && state.getTopicId() != null && knownTopicIds.contains(state.getTopicId());
            if (topicThere && hashes.equals(state.getCellHashes())) {
                pass.count("unchanged");
                pass.topicByKey.put(key, state.getTopicId());
                continue;
            }
            ContentSheetRun.Item item = item(r);
            if (run.isPreview()) {
                previewRow(r, state, topicThere, hashes, item);
                pass.count("ADDED".equals(item.getAction()) ? "added" : "updated");
                pass.add(item);
                continue;
            }
            try {
                applyRow(pass, key, r, state, topicThere, hashes, item, stateByKey.keySet(), byKey.keySet());
            } catch (RuntimeException ex) {
                item.setStatus("FAILED");
                item.getProblems().add(reason(ex));
            }
            if ("FAILED".equals(item.getStatus()) && item.getTopicId() == null) {
                item.setAction("FAILED");
                pass.count("failed");
            } else {
                pass.count("ADDED".equals(item.getAction()) ? "added" : "updated");
                if (!"OK".equals(item.getStatus())) pass.count("failed");
            }
            pass.add(item);
        }

        /* ---- order: new topics take the place their S.no gives them */
        if (!run.isPreview()) {
            for (String chapterId : pass.chaptersWithNewTopics) {
                try {
                    reorder(pass, chapterId, byKey);
                } catch (RuntimeException ex) {
                    log.warn("Could not order chapter {}: {}", chapterId, ex.getMessage());
                }
            }
        }

        /* ---- rows the sheet no longer has: reported, never removed */
        for (ContentSheetRow s : stateByKey.values()) {
            if (byKey.containsKey(s.getRowKey())) continue;
            ContentSheetRun.Item item = new ContentSheetRun.Item();
            item.setModule(s.getModuleName());
            item.setChapter(s.getChapterName());
            item.setTitle(s.getTitle());
            item.setAction("REMOVED");
            item.setStatus("OK");
            item.setTopicId(s.getTopicId());
            item.setChapterId(s.getChapterId());
            item.getChanges().add("No longer in the sheet. The topic and its materials were left in the LMS; "
                    + "delete or hide it in Modules and chapters if that was intended.");
            pass.add(item);
            pass.count("removed");
        }

        run.setCounts(pass.counts);
        int bad = pass.counts.get("failed") + pass.counts.get("invalid");
        int good = pass.counts.get("added") + pass.counts.get("updated") + pass.counts.get("unchanged");
        run.setStatus(bad == 0 ? "OK" : good == 0 ? "FAILED" : "PARTIAL");
        run.setMessage((run.isPreview() ? "Preview, nothing was changed. " : "")
                + pass.counts.get("added") + " added, " + pass.counts.get("updated") + " updated, "
                + pass.counts.get("unchanged") + " unchanged, " + pass.counts.get("removed") + " no longer in the sheet, "
                + bad + " with problems.");
        run.setFinishedAt(Instant.now());
        runs.save(run);
        activity.log(actor.id(), actor.email(), run.isPreview() ? "CONTENT_SHEET_PREVIEW" : "CONTENT_SHEET_SYNC",
                "content-sheet", run.getTrigger() + ": " + run.getMessage());
    }

    private void previewRow(SheetRow r, ContentSheetRow state, boolean topicThere,
                            Map<String, String> hashes, ContentSheetRun.Item item) {
        item.setStatus("OK");
        if (!topicThere) {
            item.setAction("ADDED");
            item.getChanges().add(state == null ? "New row: the topic will be created, or matched to an "
                    + "existing one of the same title in that chapter." : "Its topic is gone from the LMS and will be recreated.");
        } else {
            item.setAction("UPDATED");
            item.setTopicId(state.getTopicId());
        }
        for (String col : CELLS) {
            String before = state == null ? null : state.getCellHashes().get(col);
            if (!Objects.equals(before, hashes.get(col)) && !(before == null && r.get(col).isBlank())) {
                item.getChanges().add(LABEL.get(col) + (before == null ? " to add" : " changed"));
            }
        }
        String video = r.get(ContentSheetParser.VIDEO);
        if (!video.isBlank()) {
            try {
                VideoRefs.parse(video, VideoRefs.detectProvider(video, "YOUTUBE"));
            } catch (ResponseStatusException ex) {
                item.setStatus("PARTIAL");
                item.getProblems().add("Video link: " + ex.getReason());
            }
        }
        String runner = r.get(ContentSheetParser.RUNNER);
        if (runnerValue(runner) == null) {
            item.setStatus("PARTIAL");
            item.getProblems().add("Code runner \"" + runner + "\" is not one of the allowed values.");
        }
    }

    private void applyRow(Pass pass, String key, SheetRow r, ContentSheetRow state, boolean topicThere,
                          Map<String, String> hashes, ContentSheetRun.Item item,
                          Set<String> allStateKeys, Set<String> sheetKeys) {
        Actor actor = pass.actor;
        List<String> problems = item.getProblems();
        List<String> changes = item.getChanges();

        String moduleName = r.get(ContentSheetParser.MODULE);
        String chapterName = r.get(ContentSheetParser.CHAPTER);

        /* ---- a row already linked to a live topic: the topic's own chapter is used, even
           if it has since been renamed or moved in the LMS, so no duplicate is made */
        Topic linked = topicThere ? topics.findById(state.getTopicId()).orElse(null) : null;
        Chapter linkedChapter = linked == null ? null : chapters.findById(linked.getChapterId()).orElse(null);

        /* ---- otherwise module and chapter, found by name or made as drafts. With a course
           named, a module of that name inside the course wins over one elsewhere. */
        String courseName = r.get(ContentSheetParser.COURSE);
        Bundle course = courseName.isBlank() ? null : pass.courseByName.get(norm(courseName));
        CourseModule module = null;
        if (linkedChapter != null) {
            module = modules.findById(linkedChapter.getModuleId()).orElse(null);
        } else if (course != null && course.getModuleIds() != null) {
            for (CourseModule m : modules.findAllById(course.getModuleIds())) {
                if (norm(m.getName()).equals(norm(moduleName))) { module = m; break; }
            }
        }
        if (module == null && linkedChapter == null) module = pass.moduleByName.get(norm(moduleName));
        if (module == null) {
            CourseModule m = new CourseModule();
            m.setName(moduleName.trim());
            module = catalogue.saveModule(m, actor.email());
            pass.moduleByName.put(norm(moduleName), module);
            changes.add("Created module \"" + module.getName() + "\" as a draft. Publish it in Modules and chapters.");
        }
        final String moduleId = module.getId();
        if (course != null && (course.getModuleIds() == null || !course.getModuleIds().contains(moduleId))) {
            List<String> ids = new ArrayList<>(course.getModuleIds() == null ? List.of() : course.getModuleIds());
            ids.add(moduleId);
            course = catalogue.setBundleModules(course.getId(), ids, actor.email());
            pass.courseByName.put(norm(courseName), course);
            changes.add("Added module \"" + module.getName() + "\" to course \"" + course.getName() + "\"."
                    + (module.isPublished() ? " The module is published, so learners on that course see it now."
                    : " The module is a draft, so learners do not see it until it is published."));
        }
        Map<String, Chapter> chapterIndex = pass.chapterByModule.computeIfAbsent(moduleId, id -> {
            Map<String, Chapter> idx = new HashMap<>();
            for (Chapter c : chapters.findByModuleIdOrderByPositionAsc(id)) idx.putIfAbsent(norm(c.getTitle()), c);
            return idx;
        });
        Chapter chapter = linkedChapter != null ? linkedChapter : chapterIndex.get(norm(chapterName));
        if (chapter == null) {
            chapter = catalogue.saveChapter(new CatalogueService.ChapterInput(null, moduleId, chapterName.trim(), null),
                    actor.email());
            chapterIndex.put(norm(chapterName), chapter);
            changes.add("Created chapter \"" + chapter.getTitle() + "\" as a draft. Publish it in Modules and chapters.");
        }
        item.setChapterId(chapter.getId());

        /* ---- the topic: the one this row made, an existing one it can adopt, or a new one */
        boolean firstMeeting = false;
        Topic topic = linked;
        if (topic == null) {
            String wanted = norm(r.get(ContentSheetParser.TITLE));
            Topic existing = topics.findByChapterIdOrderByPositionAsc(chapter.getId()).stream()
                    .filter(t -> norm(t.getTitle()).equals(wanted)).findFirst().orElse(null);
            if (state == null) state = new ContentSheetRow();
            state.setRowKey(key);
            state.setCellHashes(new HashMap<>());
            state.setResourceIds(new HashMap<>());
            if (existing != null) {
                topic = existing;
                firstMeeting = true;
                state.setAdopted(true);
                changes.add("Matched the topic already in this chapter. What it has is kept; empty fields are filled.");
                /* a row renamed in the sheet after the topic was renamed in the LMS: the old
                   row's record points at this topic too, and is retired */
                for (ContentSheetRow other : rowStates.findByTopicId(existing.getId())) {
                    if (!other.getRowKey().equals(key) && !sheetKeys.contains(other.getRowKey())) rowStates.delete(other);
                }
            } else {
                String runner = runnerValue(r.get(ContentSheetParser.RUNNER));
                topic = catalogue.saveTopic(new CatalogueService.TopicInput(null, chapter.getId(),
                        r.get(ContentSheetParser.TITLE).trim(), blankToNull(r.get(ContentSheetParser.ONELINE)),
                        15, null, null, runner == null ? "NONE" : runner), actor.email());
                state.setAdopted(false);
                pass.chaptersWithNewTopics.add(chapter.getId());
                changes.add(topicThere || allStateKeys.contains(key)
                        ? "Recreated the topic; it had been deleted in the LMS." : "Created the topic.");
                /* one line and runner went in with the topic */
                state.getCellHashes().put(ContentSheetParser.ONELINE, hashes.get(ContentSheetParser.ONELINE));
                if (runner != null) state.getCellHashes().put(ContentSheetParser.RUNNER, hashes.get(ContentSheetParser.RUNNER));
            }
            item.setAction("ADDED");
        } else {
            item.setAction("UPDATED");
        }
        state.setModuleId(moduleId);
        state.setChapterId(topic.getChapterId());
        state.setTopicId(topic.getId());
        state.setModuleName(moduleName.trim());
        state.setChapterName(chapterName.trim());
        state.setTitle(r.get(ContentSheetParser.TITLE).trim());
        item.setTopicId(topic.getId());
        pass.topicByKey.put(key, topic.getId());
        if (!topic.isActive()) changes.add("Note: this topic is archived in the LMS, so learners do not see it.");
        /* save early, so even a row that fails below is linked to its topic next time */
        state = rowStates.save(state);

        /* ---- cells */
        Map<String, String> done = state.getCellHashes();
        boolean partial = false;

        /* the course link was handled above, with the module */
        if (hashes.get(ContentSheetParser.COURSE) == null) done.remove(ContentSheetParser.COURSE);
        else done.put(ContentSheetParser.COURSE, hashes.get(ContentSheetParser.COURSE));

        /* one line and code runner: topic fields, written together */
        boolean summaryChanged = !Objects.equals(done.get(ContentSheetParser.ONELINE), hashes.get(ContentSheetParser.ONELINE));
        boolean runnerChanged = !Objects.equals(done.get(ContentSheetParser.RUNNER), hashes.get(ContentSheetParser.RUNNER));
        if (summaryChanged || runnerChanged) {
            String summary = topic.getSummary();
            String runner = topic.getCodeRunner();
            String sheetSummary = r.get(ContentSheetParser.ONELINE);
            String sheetRunner = runnerValue(r.get(ContentSheetParser.RUNNER));
            boolean write = false;
            if (summaryChanged) {
                if (firstMeeting && summary != null && !summary.isBlank()) {
                    if (!summary.trim().equals(sheetSummary)) changes.add("Kept the one line the topic already had.");
                } else if (!Objects.equals(blankToNull(sheetSummary), summary)) {
                    summary = blankToNull(sheetSummary);
                    write = true;
                    changes.add("One line " + (summary == null ? "cleared." : "set."));
                }
            }
            if (runnerChanged) {
                if (sheetRunner == null) {
                    problems.add("Code runner \"" + r.get(ContentSheetParser.RUNNER) + "\" is not one of the allowed values; left as it was.");
                    partial = true;
                } else if (firstMeeting && !"NONE".equals(runner) && !sheetRunner.equals(runner)) {
                    changes.add("Kept the code runner the topic already had.");
                } else if (!sheetRunner.equals(runner)) {
                    runner = sheetRunner;
                    write = true;
                    changes.add("Code runner set to " + runner.toLowerCase(Locale.ROOT) + ".");
                }
            }
            if (write) {
                topic = catalogue.saveTopic(new CatalogueService.TopicInput(topic.getId(), topic.getChapterId(),
                        topic.getTitle(), summary, topic.getDurationMin(), null, null, runner), actor.email());
            }
            if (summaryChanged) done.put(ContentSheetParser.ONELINE, hashes.get(ContentSheetParser.ONELINE));
            if (runnerChanged && sheetRunner != null) done.put(ContentSheetParser.RUNNER, hashes.get(ContentSheetParser.RUNNER));
        }

        /* video */
        if (!Objects.equals(done.get(ContentSheetParser.VIDEO), hashes.get(ContentSheetParser.VIDEO))) {
            String link = r.get(ContentSheetParser.VIDEO);
            boolean ok = true;
            if (link.isBlank()) {
                if (done.get(ContentSheetParser.VIDEO) != null && topic.getVideoRef() != null) {
                    changes.add("The video link was cleared in the sheet; the video on the topic was kept.");
                }
            } else {
                ok = applyVideo(topic, link, firstMeeting, changes, problems, actor);
                if (ok) topic = topics.findById(topic.getId()).orElse(topic);
            }
            if (ok) done.put(ContentSheetParser.VIDEO, hashes.get(ContentSheetParser.VIDEO));
            else partial = true;
        }

        /* notes, code, dataset, slides: files fetched from links */
        for (String col : FILE_CELLS) {
            if (Objects.equals(done.get(col), hashes.get(col))) continue;
            boolean ok = applyFiles(state, topic, chapter, col, r.get(col), firstMeeting, changes, problems, actor);
            if (ok) done.put(col, hashes.get(col));
            else partial = true;
            state = rowStates.save(state);
        }

        /* written note */
        if (!Objects.equals(done.get(ContentSheetParser.WRITTEN), hashes.get(ContentSheetParser.WRITTEN))) {
            applyWritten(state, topic, r.get(ContentSheetParser.WRITTEN), firstMeeting, changes, actor);
            done.put(ContentSheetParser.WRITTEN, hashes.get(ContentSheetParser.WRITTEN));
        }

        state.setLastStatus(partial ? "PARTIAL" : "OK");
        state.setLastError(problems.isEmpty() ? null : String.join(" ", problems));
        state.setUpdatedAt(Instant.now());
        rowStates.save(state);
        item.setStatus(partial || !problems.isEmpty() ? "PARTIAL" : "OK");
        if (changes.isEmpty() && problems.isEmpty()) changes.add("Checked; nothing needed changing.");
    }

    private boolean applyVideo(Topic topic, String link, boolean firstMeeting,
                               List<String> changes, List<String> problems, Actor actor) {
        String provider = VideoRefs.detectProvider(link, "YOUTUBE");
        if ("GOOGLE_DRIVE_FOLDER".equals(provider)) {
            problems.add("Video link is a Drive folder; link the video file itself.");
            return false;
        }
        String id;
        try {
            id = VideoRefs.parse(link, provider);
        } catch (ResponseStatusException ex) {
            problems.add("Video link: " + ex.getReason());
            return false;
        }
        if ("YOUTUBE".equals(provider)) {
            String st = fetcher.youtubeStatus(id);
            if ("MISSING".equals(st)) {
                problems.add("Video link: YouTube says that video does not exist or is private (broken link).");
                return false;
            }
            if ("NOT_EMBEDDABLE".equals(st)) {
                problems.add("Video link: that YouTube video does not allow embedding, so it cannot play in the LMS. "
                        + "Turn on \"Allow embedding\" for it in YouTube Studio.");
                return false;
            }
            if (st == null) changes.add("Could not reach YouTube to check the video; it was attached unchecked.");
        }
        if (topic.getVideoRef() != null) {
            Video current = videos.findById(topic.getVideoRef()).orElse(null);
            if (current != null && id.equals(current.getExternalId())) return true;
            if (firstMeeting && current != null && current.isActive()) {
                changes.add("Kept the video the topic already had; the sheet names a different one. "
                        + "Edit the video link in the sheet to replace it.");
                return true;
            }
        }
        catalogue.saveTopic(new CatalogueService.TopicInput(topic.getId(), topic.getChapterId(), topic.getTitle(),
                topic.getSummary(), topic.getDurationMin(), link, provider, topic.getCodeRunner()), actor.email());
        changes.add(topic.getVideoRef() == null ? "Video attached." : "Video replaced.");
        return true;
    }

    /**
     * One file cell. Each link in it becomes one material; a link already fetched is
     * not fetched again; a link removed from the cell hides its material.
     */
    private boolean applyFiles(ContentSheetRow state, Topic topic, Chapter chapter, String col, String value,
                               boolean firstMeeting, List<String> changes, List<String> problems, Actor actor) {
        String label = LABEL.get(col);
        List<String> links = new ArrayList<>();
        for (String token : value.split("[\\s,;]+")) {
            if (token.isBlank()) continue;
            if (!token.toLowerCase(Locale.ROOT).startsWith("http")) {
                problems.add(label + ": \"" + shorten(token) + "\" is not a link.");
                return false;
            }
            if (!links.contains(token)) links.add(token);
        }
        /* keyed on a hash of the link: Mongo refuses map keys with dots in them, and
           every link has one */
        String prefix = col + "|";
        Set<String> wanted = new HashSet<>();
        for (String link : links) wanted.add(prefix + linkKey(link));
        Map<String, String> made = state.getResourceIds();

        if (firstMeeting && !links.isEmpty()) {
            boolean has = resources.findByTopicIdOrderByPositionAsc(topic.getId()).stream()
                    .anyMatch(x -> x.isActive() && col.equals(x.getKind()));
            if (has) {
                changes.add("Kept the " + label.toLowerCase(Locale.ROOT) + " the topic already had.");
                return true;
            }
        }

        boolean ok = true;
        for (String link : links) {
            String existingId = made.get(prefix + linkKey(link));
            if (existingId != null) {
                resources.findById(existingId).ifPresent(res -> {
                    if (!res.isActive()) {
                        res.setActive(true);
                        resources.save(res);
                        changes.add(label + ": showed \"" + res.getTitle() + "\" again.");
                    }
                });
                continue;
            }
            try {
                LinkFetcher.Fetched f = fetcher.fetchFile(link, files.maxBytes());
                String kind = FileService.kindForFile(f.filename());
                if (kind == null) {
                    problems.add(label + ": " + f.filename() + " is not a file type the LMS keeps as material.");
                    ok = false;
                    continue;
                }
                StoredFile stored = files.store(f.filename(), f.contentType(), f.bytes().length,
                        new ByteArrayInputStream(f.bytes()), actor.id(), "RESOURCE", topic.getChapterId());
                Resource res;
                try {
                    res = resourceService.save(new ResourceService.ResourceInput(null, topic.getId(), kind,
                            stored.getFilename(), null, null, null, stored.getId(), null, null,
                            true, true, true, "BOTH"), actor.email());
                } catch (RuntimeException ex) {
                    files.delete(stored.getId());
                    throw ex;
                }
                made.put(prefix + linkKey(link), res.getId());
                changes.add(label + ": added " + stored.getFilename()
                        + (kind.equals(col) ? "" : " (filed under " + FileService.KIND_LABEL.get(kind) + ", its file type)") + ".");
            } catch (LinkFetcher.FetchException ex) {
                problems.add(label + " link " + shorten(link) + ": " + ex.getMessage());
                ok = false;
            } catch (ResponseStatusException ex) {
                problems.add(label + " link " + shorten(link) + ": " + ex.getReason());
                ok = false;
            }
        }

        /* links the cell no longer has: hidden, not deleted, so putting the link back restores them */
        for (Map.Entry<String, String> e : made.entrySet()) {
            if (!e.getKey().startsWith(prefix) || wanted.contains(e.getKey())) continue;
            resources.findById(e.getValue()).ifPresent(res -> {
                if (res.isActive()) {
                    res.setActive(false);
                    resources.save(res);
                    changes.add(label + ": hid \"" + res.getTitle() + "\"; its link was removed from the sheet.");
                }
            });
        }
        return ok;
    }

    private void applyWritten(ContentSheetRow state, Topic topic, String text, boolean firstMeeting,
                              List<String> changes, Actor actor) {
        String slot = "TEXT|note";
        String id = state.getResourceIds().get(slot);
        Resource existing = id == null ? null : resources.findById(id).orElse(null);
        if (text.isBlank()) {
            if (existing != null && existing.isActive()) {
                existing.setActive(false);
                resources.save(existing);
                changes.add("Written note cleared in the sheet; it is hidden.");
            }
            return;
        }
        if (existing == null && firstMeeting && resources.findByTopicIdOrderByPositionAsc(topic.getId()).stream()
                .anyMatch(x -> x.isActive() && "TEXT".equals(x.getKind()))) {
            changes.add("Kept the written note the topic already had.");
            return;
        }
        Resource saved = resourceService.save(new ResourceService.ResourceInput(
                existing == null ? null : existing.getId(), topic.getId(), "TEXT",
                existing == null ? "Note" : existing.getTitle(), null, null, null, null, null, text,
                null, null, null, null), actor.email());
        if (!saved.isActive()) {
            saved.setActive(true);
            resources.save(saved);
        }
        state.getResourceIds().put(slot, saved.getId());
        changes.add(existing == null ? "Written note added." : "Written note updated.");
    }

    /**
     * Topics this run added go where their S.no puts them, among the topics the sheet
     * manages. A topic made by hand in the builder keeps its slot: the sheet's topics are
     * dealt into the remaining places in sheet order, and nothing else moves.
     */
    private void reorder(Pass pass, String chapterId, Map<String, SheetRow> byKey) {
        List<Topic> current = topics.findByChapterIdOrderByPositionAsc(chapterId);
        Map<String, SheetRow> rowByTopic = new HashMap<>();
        for (Map.Entry<String, SheetRow> e : byKey.entrySet()) {
            String topicId = pass.topicByKey.get(e.getKey());
            if (topicId != null) rowByTopic.put(topicId, e.getValue());
        }
        List<String> managed = current.stream().map(Topic::getId).filter(rowByTopic::containsKey)
                .sorted(Comparator.comparing((String id) -> snoOrder(rowByTopic.get(id)))
                        .thenComparingInt(id -> rowByTopic.get(id).order()))
                .collect(Collectors.toList());
        List<String> next = new ArrayList<>();
        Iterator<String> it = managed.iterator();
        for (Topic t : current) next.add(rowByTopic.containsKey(t.getId()) ? it.next() : t.getId());
        List<String> before = current.stream().map(Topic::getId).collect(Collectors.toList());
        if (!next.equals(before)) catalogue.reorderTopics(chapterId, next, pass.actor.email());
    }

    private static double snoOrder(SheetRow r) {
        String s = r.get(ContentSheetParser.SNO).trim();
        try {
            return s.isEmpty() ? Double.MAX_VALUE : Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.MAX_VALUE;
        }
    }

    /* =============================================================== helpers */

    private static ContentSheetRun.Item item(SheetRow r) {
        ContentSheetRun.Item item = new ContentSheetRun.Item();
        item.setRow(r.where());
        item.setSno(r.get(ContentSheetParser.SNO));
        item.setModule(r.get(ContentSheetParser.MODULE));
        item.setChapter(r.get(ContentSheetParser.CHAPTER));
        item.setTitle(r.get(ContentSheetParser.TITLE));
        return item;
    }

    private static String key(SheetRow r) {
        return norm(r.get(ContentSheetParser.MODULE)) + "|" + norm(r.get(ContentSheetParser.CHAPTER))
                + "|" + norm(r.get(ContentSheetParser.TITLE));
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> cellHashes(SheetRow r) {
        Map<String, String> m = new HashMap<>();
        for (String col : CELLS) {
            /* a blank course is no course at all, so rows synced before the column
               existed are not all "changed" the day it appears */
            if (ContentSheetParser.COURSE.equals(col) && r.get(col).isBlank()) continue;
            m.put(col, sha256(r.get(col).trim()));
        }
        return m;
    }

    private static String runnerValue(String raw) { return ContentSheetParser.runnerValue(raw); }

    private static String linkKey(String link) { return sha256(link.trim()).substring(0, 24); }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String shorten(String s) {
        return s.length() > 60 ? s.substring(0, 57) + "..." : s;
    }

    private static String reason(RuntimeException ex) {
        if (ex instanceof ResponseStatusException rse && rse.getReason() != null) return rse.getReason();
        return ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
    }

    static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

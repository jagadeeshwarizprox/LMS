package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One synchronisation, and what it did to every row it touched.
 *
 * Unchanged rows are counted, not listed, so a run over a long sheet where one row
 * moved stays a short document that says exactly that.
 */
@Document("content_sheet_runs")
public class ContentSheetRun {
    @Id private String id;
    private String versionId;
    private String trigger;           // UPLOAD | MANUAL | AUTO | RETRY | RESTORE
    private boolean preview;          // checked and reported, nothing written
    private String actor;
    @Indexed private Instant startedAt = Instant.now();
    private Instant finishedAt;
    private String status = "RUNNING";  // RUNNING | OK | PARTIAL | FAILED
    private String message;
    private Map<String, Integer> counts = new LinkedHashMap<>();
    private List<Item> items = new ArrayList<>();
    private boolean itemsTruncated;

    public static class Item {
        private String row;           // "Sheet1!12"
        private String sno;
        private String module;
        private String chapter;
        private String title;
        private String action;        // ADDED | UPDATED | UNCHANGED | REMOVED | FAILED | INVALID
        private String status;        // OK | PARTIAL | FAILED
        private List<String> changes = new ArrayList<>();
        private List<String> problems = new ArrayList<>();
        private String topicId;
        private String chapterId;

        public String getRow() { return row; }
        public void setRow(String row) { this.row = row; }
        public String getSno() { return sno; }
        public void setSno(String sno) { this.sno = sno; }
        public String getModule() { return module; }
        public void setModule(String module) { this.module = module; }
        public String getChapter() { return chapter; }
        public void setChapter(String chapter) { this.chapter = chapter; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getAction() { return action; }
        public void setAction(String action) { this.action = action; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public List<String> getChanges() { return changes; }
        public void setChanges(List<String> changes) { this.changes = changes; }
        public List<String> getProblems() { return problems; }
        public void setProblems(List<String> problems) { this.problems = problems; }
        public String getTopicId() { return topicId; }
        public void setTopicId(String topicId) { this.topicId = topicId; }
        public String getChapterId() { return chapterId; }
        public void setChapterId(String chapterId) { this.chapterId = chapterId; }
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }
    public String getTrigger() { return trigger; }
    public void setTrigger(String trigger) { this.trigger = trigger; }
    public boolean isPreview() { return preview; }
    public void setPreview(boolean preview) { this.preview = preview; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
    public Map<String, Integer> getCounts() { return counts; }
    public void setCounts(Map<String, Integer> counts) { this.counts = counts; }
    public List<Item> getItems() { return items; }
    public void setItems(List<Item> items) { this.items = items; }
    public boolean isItemsTruncated() { return itemsTruncated; }
    public void setItemsTruncated(boolean itemsTruncated) { this.itemsTruncated = itemsTruncated; }
}

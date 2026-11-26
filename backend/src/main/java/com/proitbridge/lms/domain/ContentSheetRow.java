package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * What the sync knows about one row of the master sheet.
 *
 * The key is module, chapter and title, normalised. A cell's hash is recorded only
 * once that cell has been applied in full, so a cell whose download failed is simply
 * still "changed" on the next sync and is retried, while everything that worked is
 * left alone. That is the whole incremental mechanism.
 *
 * resourceIds maps "KIND|hash of the link" to the resource made from it: one per link
 * for the file columns (hashed, because Mongo map keys may not contain dots), and
 * "TEXT|note" for the written note. It is how a changed cell knows which
 * links are new and which were dropped.
 */
@Document("content_sheet_rows")
public class ContentSheetRow {
    @Id private String id;
    @Indexed(unique = true) private String rowKey;
    private String moduleId;
    @Indexed private String chapterId;
    @Indexed private String topicId;
    private String moduleName;
    private String chapterName;
    private String title;
    private boolean adopted;          // the topic existed before the sheet named it
    private Map<String, String> cellHashes = new HashMap<>();
    private Map<String, String> resourceIds = new HashMap<>();
    private String lastStatus;        // OK | PARTIAL | FAILED
    private String lastError;
    private Instant updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getRowKey() { return rowKey; }
    public void setRowKey(String rowKey) { this.rowKey = rowKey; }
    public String getModuleId() { return moduleId; }
    public void setModuleId(String moduleId) { this.moduleId = moduleId; }
    public String getChapterId() { return chapterId; }
    public void setChapterId(String chapterId) { this.chapterId = chapterId; }
    public String getTopicId() { return topicId; }
    public void setTopicId(String topicId) { this.topicId = topicId; }
    public String getModuleName() { return moduleName; }
    public void setModuleName(String moduleName) { this.moduleName = moduleName; }
    public String getChapterName() { return chapterName; }
    public void setChapterName(String chapterName) { this.chapterName = chapterName; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public boolean isAdopted() { return adopted; }
    public void setAdopted(boolean adopted) { this.adopted = adopted; }
    public Map<String, String> getCellHashes() { return cellHashes; }
    public void setCellHashes(Map<String, String> cellHashes) { this.cellHashes = cellHashes; }
    public Map<String, String> getResourceIds() { return resourceIds; }
    public void setResourceIds(Map<String, String> resourceIds) { this.resourceIds = resourceIds; }
    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}

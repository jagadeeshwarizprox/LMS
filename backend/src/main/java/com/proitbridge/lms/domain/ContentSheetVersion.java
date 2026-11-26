package com.proitbridge.lms.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * One copy of the master sheet, kept for audit and recovery.
 *
 * A version is written only when the bytes differ from the latest one, so the list is
 * the history of the sheet, not of how often anybody pressed Sync. The bytes are a
 * StoredFile like any upload (purpose CONTENT_SHEET), which learners can never read.
 */
@Document("content_sheet_versions")
public class ContentSheetVersion {
    @Id private String id;
    private String fileId;
    @Indexed private String sha256;
    private String filename;
    private String source;            // UPLOAD | LINK | RESTORE
    private String restoredFromId;    // RESTORE only
    private int rowCount;
    private String createdBy;
    @Indexed private Instant createdAt = Instant.now();

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }
    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }
    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getRestoredFromId() { return restoredFromId; }
    public void setRestoredFromId(String restoredFromId) { this.restoredFromId = restoredFromId; }
    public int getRowCount() { return rowCount; }
    public void setRowCount(int rowCount) { this.rowCount = rowCount; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
